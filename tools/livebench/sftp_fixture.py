"""Create or repair one synthetic SFTP fixture for the local Linux bench.

Run as root on Linux:
  python3 sftp_fixture.py create --root /srv/meshpilot-sftp-live-20261027/jail/fixture --uid 1000 --gid 1000
  python3 sftp_fixture.py repair --root /srv/meshpilot-sftp-live-20261027/jail/fixture

The root must be a missing (create) or existing (repair) directory named ``fixture``
whose parent is named ``jail``.  A ``meshpilot-sftp-live-20261027`` path component is
required.  Symlinked roots or ancestors are rejected.  Existing directories are never
removed; repair unlinks only the four fixed links and the fixed FIFO.  The owned marker
``.sftp-live-owned`` contains ``synthetic-v1``.  No credentials, secrets, network access,
or non-standard-library modules are used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys


BENCH_COMPONENT = "meshpilot-sftp-live-20261027"
ROOT_NAME = "fixture"
PARENT_NAME = "jail"
MARKER_NAME = ".sftp-live-owned"
MARKER_CONTENT = b"synthetic-v1"
BIG_SIZE = 24 * 1024 * 1024
CHUNK_SIZE = 1024 * 1024
MAX_SMALL_SIZE = 65_536
REPLACEMENT_TEXT = b"synthetic-v1 repaired placeholder\n"
NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)

LINK_PATHS = (
    "source/ab-links/good-file",
    "source/ab-links/good-dir",
    "source/ab-links/broken",
    "source/ab-links/loop",
)
DENIED_FILE = "source/ac-denied-file.bin"
DENIED_DIR = "source/ad-denied-dir"
FIFO_PATH = "source/zz-pipe"


class FixtureError(Exception):
    """A contract or filesystem condition that must stop the command."""


def _at(root, relative):
    return root.joinpath(*relative.split("/"))


def _validate_root(raw_root):
    if not raw_root or not os.path.isabs(raw_root):
        raise FixtureError("--root must be an absolute path")
    if ".." in Path(raw_root).parts:
        raise FixtureError("--root must not contain '..' components")
    root = Path(os.path.normpath(raw_root))
    if root.name != ROOT_NAME or root.parent.name != PARENT_NAME:
        raise FixtureError("--root must be a .../jail/fixture path")
    if BENCH_COMPONENT not in root.parts:
        raise FixtureError("--root must contain the %s component" % BENCH_COMPONENT)
    return root


def _require_root_user():
    geteuid = getattr(os, "geteuid", None)
    if geteuid is None or geteuid() != 0:
        raise FixtureError("create and repair must run as root")


def _check_ancestors(path, include_path):
    """Reject symlinks in the root or any ancestor and require real directories."""
    current = path if include_path else path.parent
    while True:
        try:
            info = os.lstat(current)
        except OSError as exc:
            raise FixtureError("cannot inspect fixture path %s: %s" % (current, exc)) from exc
        if stat.S_ISLNK(info.st_mode):
            raise FixtureError("fixture path must not contain a symlink: %s" % current)
        if not stat.S_ISDIR(info.st_mode):
            raise FixtureError("fixture path component is not a directory: %s" % current)
        if current == current.parent:
            break
        current = current.parent


def _check_real_ancestors(root, relative, include_final):
    """Check fixed-path parents below root without resolving symlinks."""
    parts = relative.split("/")
    if not include_final:
        parts = parts[:-1]
    current = root
    for part in parts:
        current = current / part
        try:
            info = os.lstat(current)
        except OSError as exc:
            raise FixtureError("cannot inspect fixture path %s: %s" % (current, exc)) from exc
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise FixtureError("fixture path must be a real directory: %s" % current)


def _walk_no_follow(base):
    """Yield (path, relative, lstat) below base without following symlinks."""
    stack = [(base, "")]
    while stack:
        directory, relative_dir = stack.pop()
        try:
            with os.scandir(directory) as scan:
                entries = []
                for entry in sorted(scan, key=lambda item: item.name):
                    try:
                        info = entry.stat(follow_symlinks=False)
                    except OSError as exc:
                        raise FixtureError(
                            "cannot inspect %s: %s" % (entry.path, exc)
                        ) from exc
                    entries.append((entry.path, entry.name, info))
        except FixtureError:
            raise
        except OSError as exc:
            raise FixtureError("cannot list %s: %s" % (directory, exc)) from exc
        for path, name, info in entries:
            relative = name if not relative_dir else relative_dir + "/" + name
            yield path, relative, info
            if stat.S_ISDIR(info.st_mode):
                stack.append((Path(path), relative))


def _hash_file(path):
    digest = hashlib.sha256()
    descriptor = os.open(path, os.O_RDONLY | NOFOLLOW)
    with os.fdopen(descriptor, "rb") as handle:
        while True:
            block = handle.read(CHUNK_SIZE)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def _snapshot_tree(base):
    try:
        root_info = os.lstat(base)
    except OSError as exc:
        raise FixtureError("cannot inspect %s: %s" % (base, exc)) from exc
    if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode):
        raise FixtureError("expected a real directory: %s" % base)
    snapshot = {}
    for path, relative, info in _walk_no_follow(base):
        if stat.S_ISLNK(info.st_mode):
            try:
                target = os.readlink(path)
            except OSError as exc:
                raise FixtureError("cannot read symlink %s: %s" % (path, exc)) from exc
            snapshot[relative] = ["symlink", target]
        elif stat.S_ISDIR(info.st_mode):
            snapshot[relative] = ["dir"]
        elif stat.S_ISREG(info.st_mode):
            snapshot[relative] = ["file", info.st_size, _hash_file(path)]
        elif stat.S_ISFIFO(info.st_mode):
            snapshot[relative] = ["fifo"]
        else:
            snapshot[relative] = ["other", stat.S_IFMT(info.st_mode)]
    return snapshot


def _ensure_dir(path):
    try:
        os.makedirs(path, mode=0o755, exist_ok=True)
    except OSError as exc:
        raise FixtureError("cannot create directory %s: %s" % (path, exc)) from exc
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise FixtureError("expected a real directory: %s" % path)


def _write_bytes(path, data, mode=0o644):
    _ensure_dir(path.parent)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW
    try:
        descriptor = os.open(path, flags, mode)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
        os.chmod(path, mode)
    except OSError as exc:
        raise FixtureError("cannot create file %s: %s" % (path, exc)) from exc


def _seed_for(relative):
    return hashlib.sha256(relative.encode("utf-8")).hexdigest()


def _small_size(relative):
    value = int.from_bytes(
        hashlib.sha256(("size:" + relative).encode("utf-8")).digest()[:4], "big"
    )
    return 1 + value % MAX_SMALL_SIZE


def _chunk(seed, offset, length):
    pattern = hashlib.sha256(("%s:%d" % (seed, offset)).encode("ascii")).digest()
    return (pattern * ((length + len(pattern) - 1) // len(pattern)))[:length]


def _write_payload(path, size, seed):
    """Write deterministic bytes in chunks so the 24 MiB files are never buffered."""
    _ensure_dir(path.parent)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o644)
        with os.fdopen(descriptor, "wb") as handle:
            for offset in range(0, size, CHUNK_SIZE):
                length = min(CHUNK_SIZE, size - offset)
                handle.write(_chunk(seed, offset, length))
        os.chmod(path, 0o644)
    except OSError as exc:
        raise FixtureError("cannot create file %s: %s" % (path, exc)) from exc


def _file_specs():
    specs = [("level0/subdir/big.bin", BIG_SIZE)]
    specs.extend(("level0/subdir/file-%03d.bin" % index, None) for index in range(0, 49))
    specs.extend(("level1/subdir/empty-%02d.bin" % index, 0) for index in range(8))
    specs.extend(("level1/subdir/file-%03d.bin" % index, None) for index in range(49, 91))
    specs.extend(
        [
            ("level2/subdir/space name one.txt", None),
            ("level2/subdir/space name two.txt", None),
            ("level2/subdir/кириллица-данные.bin", None),
            ("level2/subdir/кириллица-заметки.txt", None),
        ]
    )
    specs.extend(("level2/subdir/file-%03d.bin" % index, None) for index in range(91, 137))
    specs.extend(
        [
            ("level3/subdir/中文文件.bin", None),
            ("level3/subdir/中文-示例.txt", None),
        ]
    )
    specs.extend(("level3/subdir/file-%03d.bin" % index, None) for index in range(137, 185))
    result = []
    for relative, size in specs:
        result.append((relative, _small_size(relative) if size is None else size))
    if len(result) != 200 or len({relative for relative, _size in result}) != 200:
        raise FixtureError("internal file plan must contain exactly 200 unique files")
    return result


def _create_aa_good(root):
    aa_good = _at(root, "source/aa-good")
    for level in ("level0", "level1", "level2", "level3"):
        _ensure_dir(aa_good / level / "subdir")
    for relative, size in _file_specs():
        _write_payload(aa_good / relative, size, _seed_for(relative))


def _create_tree(root):
    source = root / "source"
    _ensure_dir(source)
    _create_aa_good(root)

    links = source / "ab-links"
    _ensure_dir(links)
    for name, target in (
        ("good-file", "../aa-good/level0/subdir/file-000.bin"),
        ("good-dir", "../aa-good"),
        ("broken", "missing"),
        ("loop", "loop"),
    ):
        try:
            os.symlink(target, links / name)
        except OSError as exc:
            raise FixtureError("cannot create symlink %s: %s" % (links / name, exc)) from exc

    _write_payload(source / "ac-denied-file.bin", 4096, _seed_for(DENIED_FILE))
    _write_bytes(links / "z-good.txt", b"after links\n")
    _write_bytes(source / "zzzz-after.txt", b"after denied or FIFO\n")
    _ensure_dir(source / "ad-denied-dir")
    _write_bytes(source / "ad-denied-dir" / "secret.txt", b"synthetic placeholder\n")
    try:
        os.mkfifo(source / "zz-pipe", 0o600)
        os.chmod(source / "zz-pipe", 0o600)
    except OSError as exc:
        raise FixtureError("cannot create FIFO: %s" % exc) from exc

    delete_test = root / "delete-test"
    _ensure_dir(delete_test)
    _write_bytes(delete_test / "x.txt", b"delete test\n")
    _ensure_dir(delete_test / "logs")
    _write_bytes(delete_test / "keep.txt", b"keep\n")
    _write_bytes(delete_test / "ordinary.txt", b"ordinary\n")
    _ensure_dir(delete_test / "empty-dir")

    cancel_test = root / "cancel-test"
    _ensure_dir(cancel_test)
    _write_payload(cancel_test / "a-big.bin", BIG_SIZE, _seed_for("cancel-test/a-big.bin"))
    _write_bytes(cancel_test / "b-later.txt", b"later\n")

    _write_bytes(root / MARKER_NAME, MARKER_CONTENT)


def _chown_tree(root, uid, gid):
    os.chown(root, uid, gid, follow_symlinks=False)
    for path, _relative, _info in _walk_no_follow(root):
        os.chown(path, uid, gid, follow_symlinks=False)


def _normalize_modes(root):
    os.chmod(root, 0o755)
    for path, _relative, info in _walk_no_follow(root):
        if stat.S_ISLNK(info.st_mode):
            continue
        if stat.S_ISDIR(info.st_mode):
            os.chmod(path, 0o755)
        elif stat.S_ISREG(info.st_mode):
            os.chmod(path, 0o644)
        elif stat.S_ISFIFO(info.st_mode):
            os.chmod(path, 0o600)
        else:
            raise FixtureError("unexpected file type in fixture: %s" % path)


def _manifest(root):
    _check_real_ancestors(root, "source/aa-good", include_final=True)
    aa_good = _at(root, "source/aa-good")
    info = os.lstat(aa_good)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise FixtureError("source/aa-good must be a real directory")
    manifest = {}
    for path, relative, info in _walk_no_follow(aa_good):
        if stat.S_ISLNK(info.st_mode):
            raise FixtureError("source/aa-good must not contain symlinks: %s" % relative)
        if stat.S_ISDIR(info.st_mode):
            continue
        if not stat.S_ISREG(info.st_mode):
            raise FixtureError("source/aa-good contains a non-regular file: %s" % relative)
        manifest["aa-good/" + relative] = {
            "size": info.st_size,
            "sha256": _hash_file(path),
        }
    if len(manifest) != 200:
        raise FixtureError("source/aa-good must contain exactly 200 regular files")
    sizes = [entry["size"] for entry in manifest.values()]
    if sum(1 for size in sizes if size == 0) != 8:
        raise FixtureError("source/aa-good must contain exactly 8 empty files")
    big = [key for key, entry in manifest.items() if entry["size"] == BIG_SIZE]
    if len(big) != 1 or not big[0].endswith("/big.bin"):
        raise FixtureError("source/aa-good must contain exactly one 24 MiB big.bin")
    for key, entry in manifest.items():
        size = entry["size"]
        if size not in (0, BIG_SIZE) and not 1 <= size <= MAX_SMALL_SIZE:
            raise FixtureError("unexpected deterministic file size for %s" % key)
    return manifest


def _hazards(root):
    hazards = []
    for relative in LINK_PATHS:
        info = os.lstat(_at(root, relative))
        if not stat.S_ISLNK(info.st_mode):
            raise FixtureError("expected a symlink hazard at %s" % relative)
        hazards.append({"kind": "symlink", "path": relative})

    info = os.lstat(_at(root, DENIED_FILE))
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0:
        raise FixtureError("expected a mode 000 regular file at %s" % DENIED_FILE)
    hazards.append({"kind": "denied-file", "mode": "000", "path": DENIED_FILE})

    info = os.lstat(_at(root, DENIED_DIR))
    if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0:
        raise FixtureError("expected a mode 000 directory at %s" % DENIED_DIR)
    hazards.append({"kind": "denied-dir", "mode": "000", "path": DENIED_DIR})

    info = os.lstat(_at(root, FIFO_PATH))
    if not stat.S_ISFIFO(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise FixtureError("expected a mode 600 FIFO at %s" % FIFO_PATH)
    hazards.append({"kind": "fifo", "mode": "600", "path": FIFO_PATH})
    return hazards


def create_fixture(raw_root, uid, gid):
    _require_root_user()
    if not 0 <= uid <= 0xFFFFFFFF or not 0 <= gid <= 0xFFFFFFFF:
        raise FixtureError("--uid and --gid must be valid non-negative IDs")
    root = _validate_root(raw_root)
    if os.path.lexists(root):
        raise FixtureError("create requires a missing root: %s" % root)
    _check_ancestors(root.parent, include_path=True)
    try:
        os.mkdir(root, 0o755)
    except OSError as exc:
        raise FixtureError("cannot create root %s: %s" % (root, exc)) from exc

    _create_tree(root)
    _chown_tree(root, uid, gid)
    _normalize_modes(root)
    manifest = _manifest(root)
    _apply_denied_modes(root)
    hazards = _hazards(root)
    return {"count": len(manifest), "hazards": hazards, "manifest": manifest}


def _apply_denied_modes(root):
    os.chmod(_at(root, DENIED_DIR), 0)
    os.chmod(_at(root, DENIED_FILE), 0)


def _check_marker(root):
    path = root / MARKER_NAME
    try:
        info = os.lstat(path)
    except FileNotFoundError as exc:
        raise FixtureError("owned marker is missing: %s" % path) from exc
    if not stat.S_ISREG(info.st_mode):
        raise FixtureError("owned marker must be a regular file")
    descriptor = os.open(path, os.O_RDONLY | NOFOLLOW)
    with os.fdopen(descriptor, "rb") as handle:
        content = handle.read(64)
    if content.strip() != MARKER_CONTENT:
        raise FixtureError("owned marker content is not recognized")


def _fixed_info(root, relative):
    _check_real_ancestors(root, relative, include_final=False)
    path = _at(root, relative)
    try:
        info = os.lstat(path)
    except FileNotFoundError as exc:
        raise FixtureError("fixed fixture path is missing: %s" % relative) from exc
    return path, info


def _plan_repair(root):
    plan = []
    for relative in LINK_PATHS:
        path, info = _fixed_info(root, relative)
        if stat.S_ISLNK(info.st_mode):
            plan.append(("replace-symlink", path, relative))
        elif not stat.S_ISREG(info.st_mode):
            raise FixtureError("link path is neither a symlink nor a regular file: %s" % relative)

    path, info = _fixed_info(root, DENIED_FILE)
    if not stat.S_ISREG(info.st_mode):
        raise FixtureError("denied file path is not a regular file: %s" % DENIED_FILE)
    if stat.S_IMODE(info.st_mode) != 0o644:
        plan.append(("chmod-file", path, DENIED_FILE))

    path, info = _fixed_info(root, DENIED_DIR)
    if not stat.S_ISDIR(info.st_mode):
        raise FixtureError("denied directory path is not a directory: %s" % DENIED_DIR)
    if stat.S_IMODE(info.st_mode) != 0o755:
        plan.append(("chmod-dir", path, DENIED_DIR))

    path, info = _fixed_info(root, FIFO_PATH)
    if stat.S_ISFIFO(info.st_mode):
        plan.append(("replace-fifo", path, FIFO_PATH))
    elif not stat.S_ISREG(info.st_mode):
        raise FixtureError("FIFO path is neither a FIFO nor a regular file: %s" % FIFO_PATH)
    return plan


def _replace_with_text(path, expected_kind, uid, gid):
    info = os.lstat(path)
    if expected_kind == "symlink" and not stat.S_ISLNK(info.st_mode):
        raise FixtureError("refusing to unlink a non-symlink: %s" % path)
    if expected_kind == "fifo" and not stat.S_ISFIFO(info.st_mode):
        raise FixtureError("refusing to unlink a non-FIFO: %s" % path)
    try:
        os.unlink(path)
    except OSError as exc:
        raise FixtureError("cannot unlink fixed hazard %s: %s" % (path, exc)) from exc
    _write_bytes(path, REPLACEMENT_TEXT)
    try:
        os.chown(path, uid, gid, follow_symlinks=False)
        os.chmod(path, 0o644)
    except OSError as exc:
        raise FixtureError("cannot set owner/mode on %s: %s" % (path, exc)) from exc


def _chmod_checked(path, expected_kind, mode):
    info = os.lstat(path)
    if expected_kind == "file" and not stat.S_ISREG(info.st_mode):
        raise FixtureError("refusing to chmod a non-regular file: %s" % path)
    if expected_kind == "dir" and (
        stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode)
    ):
        raise FixtureError("refusing to chmod a non-directory: %s" % path)
    os.chmod(path, mode)


def repair_fixture(raw_root):
    _require_root_user()
    root = _validate_root(raw_root)
    _check_ancestors(root, include_path=True)
    _check_marker(root)
    root_info = os.lstat(root)
    uid, gid = root_info.st_uid, root_info.st_gid

    _check_real_ancestors(root, "source/aa-good", include_final=True)
    aa_good = _at(root, "source/aa-good")
    before = _snapshot_tree(aa_good)
    plan = _plan_repair(root)
    repaired = []
    for action, path, relative in plan:
        if action == "replace-symlink":
            _replace_with_text(path, "symlink", uid, gid)
            repaired.append({"action": action, "path": relative})
        elif action == "replace-fifo":
            _replace_with_text(path, "fifo", uid, gid)
            repaired.append({"action": action, "path": relative})
        elif action == "chmod-file":
            _chmod_checked(path, "file", 0o644)
            repaired.append({"action": action, "mode": "644", "path": relative})
        elif action == "chmod-dir":
            _chmod_checked(path, "dir", 0o755)
            repaired.append({"action": action, "mode": "755", "path": relative})

    if _snapshot_tree(aa_good) != before:
        raise FixtureError("source/aa-good changed during repair")
    return {"count": len(repaired), "repaired": repaired}


def _parse_id(value):
    try:
        number = int(value, 10)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("not an integer: %s" % value) from exc
    if not 0 <= number <= 0xFFFFFFFF:
        raise argparse.ArgumentTypeError("ID must be between 0 and 4294967295")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Create or repair only the synthetic SFTP livebench fixture.",
        allow_abbrev=False,
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create", help="create a new missing fixture root")
    create.add_argument("--root", required=True, help="absolute .../jail/fixture path")
    create.add_argument("--uid", required=True, type=_parse_id, help="fixture owner UID")
    create.add_argument("--gid", required=True, type=_parse_id, help="fixture owner GID")

    repair = subparsers.add_parser("repair", help="repair only the fixed hazard paths")
    repair.add_argument("--root", required=True, help="absolute .../jail/fixture path")

    args = parser.parse_args(argv)
    try:
        if args.command == "create":
            payload = create_fixture(args.root, args.uid, args.gid)
        else:
            payload = repair_fixture(args.root)
    except (FixtureError, OSError) as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 1
    print(json.dumps(payload, sort_keys=True, ensure_ascii=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
