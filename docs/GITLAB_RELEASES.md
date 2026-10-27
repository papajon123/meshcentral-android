# Private development and public release snapshots

Development is canonical in a private GitLab project. Its deployment host is pinned by a SHA-256 comparison in the release bridge; no private server address is needed in public documentation.
The old project **107** is not a publication source and is not modified by this bridge.
GitHub `papajon123/meshcentral-android` receives reviewed release trees, not private Git history.

## Release flow

1. Prepare a release in GitLab `main`. `app/build.gradle.kts` must contain one literal
   `versionName`, `versionCode` and the existing application ID. Increase both version numbers.
2. A maintainer creates a protected `vMAJOR.MINOR.PATCH` GitLab tag. The tag version must equal
   `versionName`. The protected pipeline first runs translation checks, Python fixture tests,
   Android unit tests, lint and a debug build, followed by a read-only snapshot check.
3. The `github-release` job exports exactly the tagged tree into a fresh, separate GitHub clone.
   It reads blobs directly from Git, never copies `.git`, checks out a private branch, or imports
   private commits, tags, authors, messages or deleted historical blobs. Working-tree changes,
   ignored files and untracked outputs are not part of the export.
4. A new public commit has **one parent: the observed public `main`**, a fixed automation identity
   and message `Release snapshot vMAJOR.MINOR.PATCH`. Both `main` and the new lightweight public tag
   are pushed in one `git push --atomic` transaction, using ordinary fast-forward rules.
   There is no force, mirror, automatic rebase, tag replacement, deletion or non-atomic fallback.
5. GitHub's existing `Signed APK release` workflow runs its independent checks, signs with GitHub's
   existing release credentials and publishes a downloadable prerelease after the signed APK verification succeeds.
   The bridge does not transfer APKs or signing keys. F-Droid delivery is managed separately.

The public and private tags have the **same name and tree, but different commit SHA values**.
Keep the canonical checkout's GitLab `origin/main` for development; `github/main` is snapshot history.
Set `git config remote.github.tagOpt --no-tags`. Do not fetch GitHub tags into canonical `refs/tags`:
inspect them via the API, a separate clone, or an explicit `refs/remotes/github/tags/*` namespace.
Accepted GitHub PR changes are ported into GitLab before the next snapshot release.

## GitLab configuration

The candidate configuration is installed by the project owner, not by the bridge script.
Use the pinned Android SDK 36 image and dedicated protected runner **15** with tag
`meshcentral-android`. The runner stays locked; shared runners and Auto DevOps are disabled for 124.
Serialize publication jobs with one `resource_group`.
Only the checks job caches Gradle data under a project-specific key; the publication job does not
restore this cache. GitLab's separate protected-ref cache scope should remain enabled.
The image includes platform 36. Checks retain Build Tools 35.0.0, installing them only on a cache
miss and caching them in `.android-sdk-cache/build-tools/` alongside Gradle data. Main/release and
review cache keys are separated and include `android-36`.

Configure these variables with **environment scope `github-release`**, so the normal build/Gradle
jobs cannot read the deployment credential:

| Variable | Settings and meaning |
| --- | --- |
| `GITHUB_DEPLOY_KEY_B64` | Protected, masked, raw/non-expanding; base64 of the dedicated unencrypted GitHub **repository-only write deploy key**. Never a personal account key or PAT. |
| `GITHUB_KNOWN_HOSTS` | Protected; explicit `github.com` public host-key lines verified against GitHub's official SSH metadata/fingerprints. Public pins, not a secret. |
| `PRIVATE_PUBLICATION_TEXT_B64` | Protected, masked and non-expanding, with scope `github-release`; base64 of a private newline-delimited exclusion policy. Keep its values out of source and logs. |
| `GITHUB_REPOSITORY` | `papajon123/meshcentral-android`; this is also the script's fixed allowlisted destination. |

The publish job must declare `environment: {name: github-release}`. The script additionally requires
the pinned GitLab host, project 124, namespace 154, private visibility, protected tag, matching
`CI_COMMIT_SHA`, runner 15 and environment name. CI debug tracing/services logging must be disabled.
No additional manual approval is required by this script after the protected tag's checks pass.
Protected-tag maintainers remain responsible for reviewing the tree and CI/script changes before
creating the tag; the tagged code itself executes on the trusted runner.

The private key is decoded in process memory and supplied to `ssh-add -` over stdin. It never enters
a file, command argument, report or subprocess environment. An isolated agent contains only that key
and is stopped in `finally`. The only temporary SSH file contains the **public host pins**.
SSH ignores user configuration, requires public-key authentication, checks pinned host keys strictly
and does not forward the agent. No runtime `ssh-keyscan`/TOFU is used. The runner image needs Python
3.10+, Git and OpenSSH client/agent tools; production publication is POSIX-only.

## Checks and retries

```sh
# Safe default: public HTTPS read, temporary local clone/objects, no remote ref changes or key needed.
python3 ci/publish_github_snapshot.py --source . --tag v0.5.2 --check-only

# Protected GitLab publication job only.
python3 ci/publish_github_snapshot.py --source . --tag "$CI_COMMIT_TAG" --publish

# Fully offline check using a prepared local bare public fixture.
python3 ci/publish_github_snapshot.py --source ./private-fixture --tag v0.5.2 \
  --check-only --fixture-public ./public-fixture.git

python3 -B -m unittest discover -s ci -p 'test_publish_github_snapshot.py' -v
```

An existing public tag is a no-op only if its complete tree matches the private tagged tree and its
commit belongs to public `main` ancestry. A conflicting tag stops the job. Public refs are checked
again immediately before push; races still receive the server's atomic fast-forward enforcement.
A connection failure can be ambiguous about whether the atomic transaction completed: rerun
`--check-only`, then retry the same protected release only after its exact tree/tag are verified.
Never move or delete a published tag to recover. A code change requires a new version and tag.

The export refuses traversal/control/case-colliding paths, symlinks, submodules, credential stores,
environment files (except placeholder examples), private/generated output directories, oversized
trees and unresolved Git LFS pointers. It preserves legitimate executable modes, UTF-8 source paths,
Gradle's tracked wrapper JAR, public images and CI/source files. A rejected file is not silently
omitted: fix the release tree explicitly. `.gitlab-ci.yml`, if tracked, is included like other source.

Content checks reject known private-key/token signatures and copies of actual secret environment
values. This is a bounded safeguard, **not proof that arbitrary prose, image metadata or an unknown
credential format contains no private information**. Human source review and normal secret scanning
remain important; no broad test-file or source-directory exemption is present.

The local bare-repository tests exercise exact tree/mode preservation, absence of private commit
objects/messages/deleted blobs, dirty-worktree exclusion, version guards, immutable-tag conflicts,
idempotence, an actual non-fast-forward race, atomic-capability failure, protected-context guards,
unsafe file modes/paths, secret patterns and stdin-only agent key delivery. A full prospective real
source-tree check includes the current CI, docs and gallery files without a content allowlist.

References: [Git atomic push](https://git-scm.com/docs/git-push),
[Git commit-tree](https://git-scm.com/docs/git-commit-tree),
[GitLab predefined variables](https://docs.gitlab.com/ci/variables/predefined_variables/),
[GitHub SSH fingerprints](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints).

## Workflow-file changes

The SSH deployment key is associated with an OAuth application that may lack the `workflow` scope. A push that changes `.github/workflows/*` can therefore be rejected even when ordinary read/write access works. Before a release containing a reviewed workflow change, apply that exact file through an authorized owner connection with workflow-write permission and compare its blob SHA with the canonical tag. Then use the normal protected snapshot publication. Do not expand key permissions, bypass branch protections or move published tags as a workaround.
