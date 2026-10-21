# Development roadmap

[Русская версия](ROADMAP.ru.md)

The documentation target is the prepared **0.5.0** release (`versionCode` 9), not a date commitment. The implementation and validation status below is deliberately separated from features that still need a gate. 0.5.0 has not been published at documentation time; GitHub Releases and a personal F-Droid repository are planned.

## Delivered in 0.5.0

- Device details and the Details / Desktop / Terminal / Files / SSH / SFTP tab model.
- Mesh Agent terminal over the relay, with a local xterm.js renderer and one-shot lifecycle.
- Mesh Agent files: browse, SAF download, upload, mkdir, rename and delete.
- SSH terminal and SFTP through MeshCentral's `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx`, using the same server authentication cookie as the native control/relay path.
- Explicit handling of known denial versus unknown metadata; unknown permits an explicit attempt and the server remains authoritative.
- Readback SHA-256 plus listing verification for Agent/SFTP uploads; expected-size download checks and SFTP empty/exact-block handling.
- One selected tool at a time. Activity recreation retains the ViewModel and live session; tools do not automatically reconnect or replay input or mutations.
- 529 translated strings in 23 locale variants (22 languages), automatic system-language selection, and updated English/Russian documentation.

## Status at a glance

| Scope | Status | Evidence or still-open gate |
|---|---|---|
| Desktop, input, displays, clipboard | Implemented in earlier releases; preserved | Existing tests and documentation; physical-device desktop acceptance remains open. |
| Device details and tab availability | Implemented | JVM availability tests, Compose navigation checks and an opt-in real-Activity sequence. |
| Agent terminal | Implemented; bench-validated | Real isolated Linux Agent through npm MeshCentral 1.2.5; Windows Agent and physical phone not tested. |
| Agent files | Implemented; bench-validated | Browse/upload/download with readback checks on the isolated Linux Agent; cancellation and large-file edge cases remain open. |
| SSH terminal | Implemented; bench-validated | Isolated SSH host through npm MeshCentral 1.2.5; host-key policy and restrictive live accounts remain open. |
| SFTP | Implemented; bench-validated | Isolated SFTP host; empty and exact-block downloads and upload readback; directory permissions/cancellation remain open. |
| Local terminal renderer | Implemented | Bundled xterm 5.5.0 / addon-fit 0.10.0; synthetic parser tests and API 36.1 emulator checks. Physical IME, Android 8 WebView and TalkBack remain open. |
| Privacy/documentation | Updated for 0.5.0 | Endpoint and local-renderer boundaries documented; no native-speaker review yet. |
| Distribution | Pending | GitHub upload and personal F-Droid repository are not live at documentation time; official F-Droid inclusion is not promised. |
| GitLab workflow | Not implemented | Repository checks use GitHub Actions; no GitLab pipeline is claimed. |

## Remaining release gates

1. **Physical-phone acceptance** on a dedicated device: install/update, sign-in/2FA, device tabs, desktop, Agent terminal, Agent files, SSH/SFTP, WebView failure path, app lock, backup exclusion and uninstall behavior.
2. **Android 8 and vendor WebView coverage:** current System WebView, an outdated provider, large fonts, short landscape, hardware/software IMEs and TalkBack.
3. **Windows Agent:** terminal, file listing, upload, download and cancellation against a real Windows Mesh Agent, not only Linux.
4. **Live restrictive rights and consent:** `NOTERMINAL`/`NOFILES`, view-only/limited input, unknown/missing metadata, no-permission, Agent consent/refusal and online/offline transitions.
5. **SSH/SFTP policy:** real server host-key behavior, wrong host key, wrong password/key/passphrase, disabled SSH/SFTP features, restrictive file permissions and directory-delete semantics.
6. **Transfer failure matrix:** pending mkdir/rename/delete cancellation, upload cancellation, network loss during download, partial SAF documents, large files and 1 MiB/20,000-entry limits.
7. **Language review:** native-speaker review of priority languages; current translations are DeepSeek-produced and not native-verified.
8. **Distribution:** publish the signed APK, verify its version/certificate/checksum, finalize the README distribution text, and only then announce GitHub/F-Droid availability.

## Next work after the release gates

- Preserve the desktop path while adding broader acceptance evidence, not more parallel tool surfaces.
- Improve file-transfer resilience only with verified resume or cancellation semantics; do not claim either before live proof.
- Expand the device screen toward events and operations only where per-action rights, confirmation and visible results are available.
- Keep AMT, RDP, VNC, WebRTC, registry/software, chat and server administration as separate product decisions. The web-panel handoff remains the default for unsupported or risky operations.
- Consider a GitLab workflow separately; none exists today.

## Capability and lifecycle rules

- Known denial, unsupported capability, offline state and unknown metadata are separate states. Missing rights or capability fields must not silently become a grant or a known denial. The server makes the final decision.
- Agent terminal, Agent files, SSH and SFTP are distinct protocol adapters. The shared relay transport does not erase their different authentication, permission and framing rules.
- A disconnected terminal/file session is final. Opening it again is an explicit user action; no previous input, command or mutation is resent automatically.
- No speculative "resume from offset" UI until the server and protocol are demonstrated to support it.
- The terminal uses a bundled local renderer because it provides the remote I/O seam directly. Its isolation and provenance are audited in `app/src/main/assets/terminal/RENDERER-AUDIT.md`; this is not a claim that every native-terminal alternative was rejected on size or accessibility grounds.
- SAF is the only file picker. Keep credentials, session tokens, terminal content and sensitive paths out of logs and saved state.

## Protocol evidence

The protocol review inspected MeshCentral package **1.2.6**, commit [`60cea8731f88b91a09704ff9a00dab4992a98ef1`](https://github.com/Ylianst/MeshCentral/tree/60cea8731f88b91a09704ff9a00dab4992a98ef1). This is source inspection, not a live 1.2.6 server test.

- [Relay protocol numbers](https://github.com/Ylianst/MeshCentral/blob/60cea8731f88b91a09704ff9a00dab4992a98ef1/meshrelay.js#L17-L80): Agent terminal 1, desktop 2, files 5; SSH terminal/files use separate server relays.
- [Browser capability code](https://github.com/Ylianst/MeshCentral/blob/60cea8731f88b91a09704ff9a00dab4992a98ef1/views/default.handlebars#L8253-L8268): desktop 1, terminal 2, files 4, console 8.
- [SSH endpoints](https://github.com/Ylianst/MeshCentral/blob/60cea8731f88b91a09704ff9a00dab4992a98ef1/webserver.js#L7665-L7683) and [server-side SSH authentication](https://github.com/Ylianst/MeshCentral/blob/60cea8731f88b91a09704ff9a00dab4992a98ef1/apprelays.js#L1665-L1804).
- [Agent terminal permissions](https://github.com/Ylianst/MeshCentral/blob/60cea8731f88b91a09704ff9a00dab4992a98ef1/agents/meshcore.js#L3514-L3527) and [Agent files](https://github.com/Ylianst/MeshCentral/blob/60cea8731f88b91a09704ff9a00dab4992a98ef1/agents/meshcore.js#L4006-L4290).
- [Termux licence](https://github.com/termux/termux-app/blob/master/LICENSE.md), [xterm.js licence](https://github.com/xtermjs/xterm.js/blob/master/LICENSE), and [Android local-content loading](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content) informed the renderer audit. Termux code is not bundled.

The live bench used npm MeshCentral **1.2.5** with a real isolated Linux Agent, SSH terminal and SFTP host. npm 1.2.6 was unavailable. **No MeshCentral 1.2.6 server was tested and compatibility with it is not claimed.**

## UI acceptance matrix

Use representative combinations: widths 320/360/600/840 dp; portrait and short landscape; font scales 1.0/1.3/2.0; English, German, Russian, Arabic, Tamil, Hindi and Traditional Chinese. Cover setup/sign-in/2FA, device list, Details, Desktop, Terminal, Files, SSH/SFTP auth, Settings, lock, loading, offline, denied, unknown, view-only, long names/errors, keyboard and TalkBack. Keep all 23 locale catalogs checked for completeness and placeholders.

The device/terminal/files screens are newer than the earlier readability pass and still need the full matrix on real devices. Resizing a Compose viewport is not proof that Activity recreation, process death, IME or WebView lifecycle behaves correctly.

## Evidence rules

- Do not report a test, server version, platform or device run that was not observed.
- Do not turn test presence into a pass result or quote counts before the lead records them.
- Sanitize server names, addresses, account names, terminal output and transferred file names in screenshots and logs.
- Preserve the public package/certificate identity; do not treat a locally signed build as an update to a public release.
