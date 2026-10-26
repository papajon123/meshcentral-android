# AI Policy

This page describes how MeshCentral Mobile is written and checked, and the rules for contributions made with AI tools.

## How the project is written

Most code, tests and documentation are written by AI coding agents — Claude Code (Anthropic) and OpenAI Codex — under the maintainer's direction.

The app's translations are machine-produced (DeepSeek) and have not been proofread by native speakers.

## The maintainer's role

In the maintainer's words:

> Most code, tests and docs are written by AI coding agents under my direction. I personally review every change: I read the code and the architecture in the merge request and check the behaviour myself before it ships.

## Automated checks

- **Every change in GitLab.** Development happens in a private GitLab repository. Every branch with a change runs a check pipeline: the translation check, tests of the CI scripts, JVM tests, lint and a debug build.
- **Every signed release.** Before publication, GitHub Actions runs JVM tests, lint and instrumented tests on Android 8 (API 26) and Android 15 (API 35) emulators. [`ci/verify_apk.py`](ci/verify_apk.py) then checks the signed APK: its package name, its version and the signing certificate pinned in [`ci/release-identity.json`](ci/release-identity.json).
- **Test suite.** About 250 JVM tests and about 150 instrumented tests. Some optional live tests are skipped.

## Security rules

The repository rules for AI agents are in [AGENTS.md](AGENTS.md). The security rules are the same for agents and people:

- The repository contains no credentials, keys or tokens.
- The app stores credentials only in Android Keystore-backed storage.
- The app does not log passwords, cookies, PINs or tokens.
- Release signing keys are kept only in CI secrets.

## Contributions made with AI

A contribution made with AI tools is accepted if:

- the pull request description says that AI tools were used;
- the pull request includes the commands you ran to check the change and their result;
- you take responsibility for licensing: the contribution must be distributable under the Apache License 2.0;
- it contains no data from real servers: use reserved example domains such as `https://meshcentral.example.com/` and synthetic device data.

The general rules in [CONTRIBUTING.md](CONTRIBUTING.md) also apply.

## Reporting a problem

- Report a security vulnerability privately, as described in [SECURITY.md](SECURITY.md).
- Report bugs and other problems in [GitHub issues](https://github.com/papajon123/meshcentral-android/issues). Never post server addresses, credentials or private device data.
