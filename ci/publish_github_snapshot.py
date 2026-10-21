#!/usr/bin/env python3
"""Export a protected release tree, never private Git history. Default: check only."""
from __future__ import annotations

import argparse
import base64
import contextlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from typing import Iterator, Mapping

GITHUB_REPOSITORY = "papajon123/meshcentral-android"
APPLICATION_ID = "io.github.papajon123.meshcentral"
GITLAB_PROJECT_ID = "124"
GITLAB_NAMESPACE_ID = "154"
GITLAB_HOST = "gitlab.papajon1234.ru"
GITLAB_PROJECT_PATH = "meshcentral_android/meshcentral-android"
AUTHOR_NAME = "MeshCentral release automation"
AUTHOR_EMAIL = "release-automation@users.noreply.github.com"
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_TREE_BYTES = 128 * 1024 * 1024
TAG_RE = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\Z")
SECRET_ENV_RE = re.compile(r"TOKEN|SECRET|PASSWORD|PRIVATE_KEY|DEPLOY_KEY|KEYSTORE", re.I)
SECRET_PATTERNS = (
    re.compile(rb"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{40,}\b"),
    re.compile(rb"\bglpat-[A-Za-z0-9_-]{20,}\b"),
    re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
)


class BridgeError(RuntimeError):
    """Safe fixed diagnostics: never include subprocess stderr, URLs with credentials or content."""


def git_environment() -> dict[str, str]:
    keep = {"PATH", "HOME", "USERPROFILE", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT",
            "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL"}
    env = {key: value for key, value in os.environ.items() if key in keep}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0",
               GIT_NO_REPLACE_OBJECTS="1", GIT_LFS_SKIP_SMUDGE="1", LC_ALL="C")
    return env


def run_git(repo: Path | None, *args: str, env: Mapping[str, str], data: bytes | None = None,
            accepted: tuple[int, ...] = (0,)) -> subprocess.CompletedProcess:
    command = ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "commit.gpgSign=false"]
    if repo is not None:
        command += ["-c", "safe.directory=" + str(repo.resolve()), "-C", str(repo)]
    command.extend(args)
    try:
        result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=dict(env), timeout=180, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise BridgeError("Git operation could not complete; no stderr or credential data is emitted") from None
    if result.returncode not in accepted:
        raise BridgeError("Git operation rejected; inspect refs with check-only before retrying")
    return result


def git_text(repo: Path, *args: str, env: Mapping[str, str], data: bytes | None = None) -> str:
    return run_git(repo, *args, env=env, data=data).stdout.decode("utf-8", "strict").strip()


def validate_tag(tag: str) -> tuple[int, int, int]:
    match = TAG_RE.fullmatch(tag)
    if not match:
        raise BridgeError("Release tag must be vMAJOR.MINOR.PATCH without leading zeroes")
    return tuple(int(part) for part in match.groups())


def validate_path(raw: bytes) -> str:
    try:
        path = raw.decode("utf-8", "strict")
    except UnicodeDecodeError:
        raise BridgeError("Release paths must be valid UTF-8") from None
    parts = path.split("/")
    if (not path or path.startswith("/") or "\\" in path or ":" in path or
            any(ord(char) < 32 or ord(char) == 127 for char in path) or
            any(part in ("", ".", "..") or part.endswith((".", " ")) for part in parts)):
        raise BridgeError("Unsafe or non-portable release path")
    lower = [part.casefold() for part in parts]
    if any(part in {".git", ".ssh", ".gradle", "node_modules", "__pycache__", ".private", "private", "backups"} for part in lower):
        raise BridgeError("Private/control directory is forbidden in a release tree")
    if lower[0] in {"state", "artifacts", "dist", "out", "build", "target"} or lower[:2] == ["app", "build"]:
        raise BridgeError("Generated/private output directory is forbidden in a release tree")
    leaf = lower[-1]
    if (leaf in {"local.properties", ".gitmodules", "credentials", "credentials.json", "kubeconfig",
                 "id_rsa", "id_ecdsa", "id_ed25519", "id_dsa"} or
            leaf.endswith((".keystore", ".jks", ".p12", ".pfx", ".kdbx", ".apk", ".aab"))):
        raise BridgeError("Credential store or generated release artifact is forbidden")
    if (leaf == ".env" or leaf.startswith(".env.")) and not leaf.endswith((".example", ".sample", ".template")):
        raise BridgeError("Environment file is forbidden; use a placeholder example instead")
    return path


def environment_secret_values(env: Mapping[str, str]) -> tuple[bytes, ...]:
    # Catch accidental copies of the job's actual credentials without printing their values.
    values = {value.encode() for name, value in env.items() if SECRET_ENV_RE.search(name) and len(value) >= 16}
    encoded = env.get("GITHUB_DEPLOY_KEY_B64")
    if encoded:
        with contextlib.suppress(ValueError):
            values.add(base64.b64decode(encoded, validate=True))
    return tuple(value for value in values if len(value) >= 16)


def validate_content(data: bytes, secret_values: tuple[bytes, ...] = ()) -> None:
    if any(pattern.search(data) for pattern in SECRET_PATTERNS) or any(value in data for value in secret_values):
        raise BridgeError("Secret material detected in the release tree; content is not emitted")
    if data.startswith(b"version https://git-lfs.github.com/spec/v1\n"):
        raise BridgeError("Git LFS pointers need an explicit external-object publication design")


@dataclass(frozen=True)
class Entry:
    path: str
    mode: str
    data: bytes = field(repr=False)


def read_tree(repo: Path, commit: str, env: Mapping[str, str], secret_values: tuple[bytes, ...] = ()) -> list[Entry]:
    rows = run_git(repo, "ls-tree", "-r", "-z", "--full-tree", commit, env=env).stdout.split(b"\0")
    records = []
    seen = set()
    for row in filter(None, rows):
        metadata, raw_path = row.split(b"\t", 1)
        mode, kind, oid = metadata.decode("ascii").split(" ")
        path = validate_path(raw_path)
        if mode not in ("100644", "100755") or kind != "blob":
            raise BridgeError("Symlinks, submodules and special Git entries are not exportable")
        if path.casefold() in seen:
            raise BridgeError("Case-colliding paths are not portable")
        seen.add(path.casefold())
        records.append((path, mode, oid))
    if not records or len(records) > 10_000:
        raise BridgeError("Unexpected release tree entry count")
    command = ["git", "-c", "safe.directory=" + str(repo.resolve()), "-C", str(repo), "cat-file", "--batch"]
    entries, total = [], 0
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          env=dict(env)) as process:
        try:
            for path, mode, oid in records:
                process.stdin.write((oid + "\n").encode("ascii")); process.stdin.flush()
                header = process.stdout.readline().split()
                if len(header) != 3 or header[0].decode() != oid or header[1] != b"blob":
                    raise BridgeError("Unexpected Git blob response")
                size = int(header[2])
                total += size
                if size > MAX_FILE_BYTES or total > MAX_TREE_BYTES:
                    raise BridgeError("Release source exceeds the bounded export size")
                data = process.stdout.read(size)
                if len(data) != size or process.stdout.read(1) != b"\n":
                    raise BridgeError("Truncated Git blob")
                validate_content(data, secret_values)
                entries.append(Entry(path, mode, data))
        finally:
            process.stdin.close()
            if process.poll() is None:
                process.terminate()
    return entries


@dataclass(frozen=True)
class Version:
    name: str
    code: int
    application_id: str


def parse_version(data: bytes) -> Version:
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError:
        raise BridgeError("Gradle version metadata must be UTF-8") from None
    values = []
    for name, pattern in (
        ("versionName", r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$'),
        ("versionCode", r'^\s*versionCode\s*=\s*(\d+)\s*$'),
        ("applicationId", r'^\s*applicationId\s*=\s*"([a-zA-Z0-9_.]+)"\s*$'),
    ):
        matches = re.findall(pattern, text, re.MULTILINE)
        if len(matches) != 1:
            raise BridgeError("Gradle must define exactly one literal " + name)
        values.append(matches[0])
    version = Version(values[0], int(values[1]), values[2])
    validate_tag("v" + version.name)
    if version.code < 1 or version.application_id != APPLICATION_ID:
        raise BridgeError("Unexpected application identity or versionCode")
    return version


def validate_publish_context(tag: str, commit: str, env: Mapping[str, str]) -> None:
    expected = {"CI_SERVER_HOST": GITLAB_HOST, "CI_PROJECT_ID": GITLAB_PROJECT_ID,
                "CI_PROJECT_NAMESPACE_ID": GITLAB_NAMESPACE_ID, "CI_PROJECT_PATH": GITLAB_PROJECT_PATH,
                "CI_PROJECT_VISIBILITY": "private", "CI_COMMIT_REF_PROTECTED": "true",
                "CI_COMMIT_TAG": tag, "CI_COMMIT_SHA": commit, "CI_RUNNER_ID": "15",
                "CI_ENVIRONMENT_NAME": "github-release"}
    if any(env.get(name) != value for name, value in expected.items()):
        raise BridgeError("Publish requires the expected private project, protected tag and dedicated runner")
    if env.get("CI_DEBUG_TRACE", "false") != "false" or env.get("CI_DEBUG_SERVICES", "false") != "false":
        raise BridgeError("Debug tracing must be disabled for the deployment-key job")
    if env.get("GITHUB_REPOSITORY", GITHUB_REPOSITORY) != GITHUB_REPOSITORY:
        raise BridgeError("Unexpected GitHub repository")


@contextlib.contextmanager
def deployment_agent(job_env: Mapping[str, str]) -> Iterator[dict[str, str]]:
    encoded = job_env.get("GITHUB_DEPLOY_KEY_B64", "")
    try:
        key = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise BridgeError("Deployment key must be valid single-line base64") from None
    if not 32 <= len(key) <= 32_768 or not SECRET_PATTERNS[0].search(key):
        raise BridgeError("Deployment key is missing or not a supported private-key encoding")
    known_hosts = job_env.get("GITHUB_KNOWN_HOSTS", "")
    lines = known_hosts.strip().splitlines()
    if not lines:
        raise BridgeError("Pinned GitHub known_hosts is required")
    for line in lines:
        parts = line.split()
        if len(parts) != 3 or parts[0] != "github.com" or parts[1] not in {"ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa"}:
            raise BridgeError("known_hosts must contain explicit github.com public host keys only")
        try:
            raw = base64.b64decode(parts[2], validate=True)
            length = int.from_bytes(raw[:4], "big")
            if raw[4:4 + length].decode("ascii") != parts[1]:
                raise ValueError()
        except (ValueError, UnicodeError):
            raise BridgeError("Invalid pinned host public key") from None
    env = git_environment()
    agent = subprocess.run(["ssh-agent", "-s"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           env=env, timeout=15, check=False)
    socket = re.search(rb"SSH_AUTH_SOCK=([^;\r\n]+);", agent.stdout)
    pid = re.search(rb"SSH_AGENT_PID=(\d+);", agent.stdout)
    if agent.returncode or not socket or not pid:
        raise BridgeError("Could not start an isolated SSH agent")
    env.update(SSH_AUTH_SOCK=socket[1].decode(), SSH_AGENT_PID=pid[1].decode(), SSH_ASKPASS_REQUIRE="never")
    try:
        added = subprocess.run(["ssh-add", "-"], input=key.rstrip(b"\r\n") + b"\n", stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, env=env, timeout=15, check=False)
        if added.returncode:
            raise BridgeError("Could not load the dedicated deployment key into the isolated agent")
        key = b""
        with tempfile.TemporaryDirectory(prefix="meshcentral-public-hosts-") as directory:
            known = Path(directory).resolve() / "known_hosts"
            known.write_text("\n".join(lines) + "\n", encoding="ascii")  # Public host pins, never the private key.
            known.chmod(0o600)
            options = ["ssh", "-F", os.devnull, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                       "-o", "UserKnownHostsFile=" + str(known), "-o", "GlobalKnownHostsFile=" + os.devnull,
                       "-o", "IdentityFile=none", "-o", "IdentitiesOnly=no", "-o", "IdentityAgent=" + env["SSH_AUTH_SOCK"],
                       "-o", "PreferredAuthentications=publickey", "-o", "PasswordAuthentication=no",
                       "-o", "KbdInteractiveAuthentication=no", "-o", "ForwardAgent=no", "-o", "ConnectTimeout=10"]
            env["GIT_SSH_COMMAND"] = shlex.join(options)
            yield env
    finally:
        key = b""
        subprocess.run(["ssh-agent", "-k"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       env=env, timeout=15, check=False)


def remote_refs(repo: Path, tag: str, env: Mapping[str, str]) -> dict[str, str]:
    raw = git_text(repo, "ls-remote", "--refs", "origin", "refs/heads/main", "refs/tags/" + tag, env=env)
    return {line.split()[1]: line.split()[0] for line in raw.splitlines() if line}


@dataclass(frozen=True)
class Plan:
    repo: Path
    tag: str
    parent: str
    commit: str
    tree: str
    version: Version
    initial_refs: dict[str, str]
    env: dict[str, str] = field(repr=False)
    already_published: bool = False

    def result(self, published: bool = False) -> dict:
        return {"decision": "already-published" if self.already_published else "published" if published else "would-publish",
                "tag": self.tag, "public_parent": self.parent, "public_commit": self.commit,
                "tree": self.tree, "versionName": self.version.name, "versionCode": self.version.code}

    def publish(self) -> dict:
        if remote_refs(self.repo, self.tag, self.env) != self.initial_refs:
            raise BridgeError("Public refs changed after validation; stop and run check-only again")
        if self.already_published:
            return self.result()
        parents = git_text(self.repo, "show", "-s", "--format=%P", self.commit, env=self.env)
        if parents != self.parent or git_text(self.repo, "rev-parse", self.commit + "^{tree}", env=self.env) != self.tree:
            raise BridgeError("Prepared commit does not have the exact tree and sole public parent")
        # Explicit refs only. No mirror, follow-tags, force, deletion, history rewrite or fallback.
        run_git(self.repo, "-c", "push.followTags=false", "push", "--atomic", "--porcelain", "origin",
                self.commit + ":refs/heads/main", self.commit + ":refs/tags/" + self.tag, env=self.env)
        expected = {"refs/heads/main": self.commit, "refs/tags/" + self.tag: self.commit}
        if remote_refs(self.repo, self.tag, self.env) != expected:
            raise BridgeError("Post-push refs differ; inspect without moving or deleting any ref")
        return self.result(published=True)


@contextlib.contextmanager
def prepare_snapshot(source: Path, tag: str, public_url: str, *, env: Mapping[str, str] | None = None,
                     secrets: tuple[bytes, ...] = (), local_fixture: bool = False) -> Iterator[Plan]:
    validate_tag(tag)
    env = dict(env or git_environment())
    source = source.resolve()
    commit = git_text(source, "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}", env=env)
    entries = read_tree(source, commit, env, secrets)
    source_files = {entry.path: entry.data for entry in entries}
    if "app/build.gradle.kts" not in source_files:
        raise BridgeError("Tagged tree has no Android version metadata")
    version = parse_version(source_files["app/build.gradle.kts"])
    if tag != "v" + version.name:
        raise BridgeError("Release tag and versionName differ")
    if local_fixture:
        target = Path(public_url).resolve()
        if not target.is_dir() or git_text(target, "rev-parse", "--is-bare-repository", env=env) != "true":
            raise BridgeError("Offline fixture must be an existing local bare repository")
        public_url = str(target)
    elif public_url not in ("git@github.com:" + GITHUB_REPOSITORY + ".git", "https://github.com/" + GITHUB_REPOSITORY + ".git"):
        raise BridgeError("Public repository destination is not allowlisted")
    with tempfile.TemporaryDirectory(prefix="meshcentral-release-snapshot-") as directory:
        public = Path(directory).resolve() / "public"
        run_git(None, "-c", "protocol.file.allow=" + ("always" if local_fixture else "never"),
                "clone", "--no-local", "--no-checkout", "--no-tags", "--single-branch", "--branch", "main",
                public_url, str(public), env=env)
        parent = git_text(public, "rev-parse", "refs/heads/main", env=env)
        refs = remote_refs(public, tag, env)
        if refs.get("refs/heads/main") != parent:
            raise BridgeError("Public main changed during clone")
        source_tree = git_text(source, "rev-parse", commit + "^{tree}", env=env)
        if "refs/tags/" + tag in refs:
            run_git(public, "fetch", "--no-tags", "origin", "refs/tags/" + tag + ":refs/tags/" + tag, env=env)
            existing = git_text(public, "rev-parse", "refs/tags/" + tag + "^{commit}", env=env)
            existing_tree = git_text(public, "rev-parse", existing + "^{tree}", env=env)
            ancestor = run_git(public, "merge-base", "--is-ancestor", existing, parent, env=env, accepted=(0, 1))
            if existing_tree != source_tree or ancestor.returncode:
                raise BridgeError("Immutable public tag conflicts with the exact release tree or public ancestry")
            yield Plan(public, tag, parent, existing, existing_tree, version, refs, env, True)
            return
        previous = parse_version(run_git(public, "show", parent + ":app/build.gradle.kts", env=env).stdout)
        if version.code <= previous.code or validate_tag(tag) <= validate_tag("v" + previous.name):
            raise BridgeError("A new release must increase both versionCode and semantic version")
        run_git(public, "read-tree", "--empty", env=env)
        index = bytearray()
        for entry in entries:
            oid = git_text(public, "hash-object", "-w", "--stdin", env=env, data=entry.data)
            index.extend((entry.mode + " " + oid + "\t" + entry.path).encode("utf-8") + b"\0")
        run_git(public, "update-index", "-z", "--index-info", env=env, data=bytes(index))
        tree = git_text(public, "write-tree", env=env)
        if tree != source_tree:
            raise BridgeError("Export did not preserve the exact tagged tree")
        author_env = dict(env)
        timestamp = datetime.now(timezone.utc).isoformat()
        author_env.update(GIT_AUTHOR_NAME=AUTHOR_NAME, GIT_AUTHOR_EMAIL=AUTHOR_EMAIL,
                          GIT_COMMITTER_NAME=AUTHOR_NAME, GIT_COMMITTER_EMAIL=AUTHOR_EMAIL,
                          GIT_AUTHOR_DATE=timestamp, GIT_COMMITTER_DATE=timestamp)
        public_commit = git_text(public, "commit-tree", tree, "-p", parent, env=author_env,
                                 data=("Release snapshot " + tag + "\n").encode())
        yield Plan(public, tag, parent, public_commit, tree, version, refs, env)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path.cwd())
    parser.add_argument("--tag", required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-only", action="store_true", help="Validate/build a temporary plan; never update remote refs (default)")
    mode.add_argument("--publish", action="store_true", help="Only protected GitLab 124 release pipelines on runner 15")
    parser.add_argument("--fixture-public", type=Path, help="Check-only offline local bare destination")
    args = parser.parse_args(argv)
    try:
        validate_tag(args.tag)
        if args.publish and args.fixture_public:
            raise BridgeError("CLI fixture mode is check-only")
        env = git_environment()
        if args.publish:
            commit = git_text(args.source.resolve(), "rev-parse", "--verify", "refs/tags/" + args.tag + "^{commit}", env=env)
            validate_publish_context(args.tag, commit, os.environ)
            if os.name != "posix":
                raise BridgeError("Production publication requires the POSIX GitLab runner")
        secret_values = environment_secret_values(os.environ)
        auth = deployment_agent(os.environ) if args.publish else contextlib.nullcontext(env)
        with auth as auth_env:
            destination = str(args.fixture_public.resolve()) if args.fixture_public else (
                "git@github.com:" + GITHUB_REPOSITORY + ".git" if args.publish else "https://github.com/" + GITHUB_REPOSITORY + ".git")
            with prepare_snapshot(args.source, args.tag, destination, env=auth_env, secrets=secret_values,
                                  local_fixture=args.fixture_public is not None) as plan:
                print(json.dumps(plan.publish() if args.publish else plan.result(), sort_keys=True))
        return 0
    except (BridgeError, OSError, ValueError, UnicodeError, subprocess.SubprocessError) as error:
        message = str(error) if isinstance(error, BridgeError) else "Bridge operation failed; sensitive diagnostics were suppressed"
        print("ERROR: " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
