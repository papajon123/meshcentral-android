# Roadmap — 0.7.1

Items 1–8 are implemented. Local public build, JVM and API 35 checks passed; see [implementation evidence and limits](ROADMAP_IMPLEMENTATION.md). WebRTC remains a separate measured decision, and the optional plugin avoids a permanent core fork.

## Status at a glance

| # | Scope | Status in the 0.7.1 candidate | Still open |
|---|---|---|---|
| 1 | Search and recent devices | Implemented: Unicode-normalised, case-insensitive AND search over name/group/host/IP/OS; 20 recents per profile and account | TalkBack, large fonts and long real lists on a phone |
| 2 | Server profiles | Implemented: up to 20 profiles, migrated default profile, scoped credentials/favourites/recents, global PIN | Signed in-place upgrade and migration on a phone |
| 3 | Two sessions | Implemented: one desktop plus one tool, or two tools; explicit close; saved selection | Rotation, background, lock, network loss and process death on a phone |
| 4 | Share to a computer | Implemented: up to 20 content URIs, visible queue, foreground data-sync, cancel, retry from the beginning, SHA-256 readback | Network loss, SAF edge cases, disk space, large files, phone run |
| 5 | System, services and power | Implemented: cached system/network data; optional plugin for services/processes; power ACK is not execution | Windows Agent, systemd, real restart/shutdown |
| 6 | Templates, jobs and history | Implemented with the optional plugin: fixed allowlist, 7-day history, 40 jobs, 64 KiB output, dedupe, no replay, no cancel of a running job | Windows services, systemd, operator installation |
| 7 | Notifications and events | Implemented optionally: WorkManager 15-minute poll, quiet hours, filters, read-only same-origin TLS token, no FCM, tap routing | Real background delivery, Doze/OEM limits, battery |
| 8 | Quick Help | Implemented with the optional plugin: diagnostics, explanation, confirmed action, recheck, redacted report | Full phone and Windows scenario |
| 9 | WebRTC | Researched, **not implemented**; the WSS relay remains | Phone/NAT/TURN, CPU, battery, frame and recovery tests |
| 10 | Extensions and fork | Decision: no permanent fork; optional plugin works without a core patch | Operator deployment, Windows/systemd acceptance, multi-server |

## What the candidate adds

### Devices and profiles

Search runs entirely over the loaded device list; typing does not issue a server request. The recent
list stores only node ids and timestamps, capped at 20 per profile and account. Server profiles keep
separate addresses, credentials, favourites and recents; the app PIN and privacy switches stay global.
Switching or deleting a profile closes its sessions.

### Sessions

Two live sessions are admitted at a time. A third one is not created by evicting another; the user
must close one explicitly. Activity recreation keeps the selected device and tab. After process death
the app restores the selection, not a live transport, and does not replay input, commands or file
changes.

### Share and transfers

`ACTION_SEND` and `ACTION_SEND_MULTIPLE` are accepted only for `content://` documents, up to 20 per
batch. The queue is visible and cancels cleanly. Uploads are verified by readback SHA-256. Retry starts
from the beginning; there is no resume or offset claim. A failed or cancelled transfer is never shown
as success, and the source URI is runtime-only.

### System information and power

System, disk, memory and network values come from data MeshCentral already stores for the device.
Missing values stay unknown. Services and processes need the optional plugin. Restart, shutdown and
Wake-on-LAN are offered only under the reported rights and connectivity, and the result is verified by
polling; a server ACK alone is not treated as execution.

### Jobs, history and Quick Help

Only fixed templates are allowed: `diagnostics`, `services`, `processes`, `service-start`,
`service-stop` and `service-restart`. Jobs have a status, result, exit code and bounded output.
`operationId` prevents a silent repeat. A running job cannot be cancelled, and the app does not claim
the remote process was killed. Quick Help produces a short report without device name, IP, user,
hostname, node/job ids or raw output.

### Optional notifications

Notifications are opt-in and use a read-only bearer token with a 30-day TTL, stored in Android
Keystore-backed storage. Polling is same-origin HTTPS through WorkManager, at a 15-minute best-effort
interval. Cookies, redirects, query tokens and self-signed exceptions are rejected on this path. There
is no FCM or project push backend. Quiet hours, per-kind filters, dedupe, TTL and tap routing are
implemented.

## Optional server plugin

Jobs, services/processes, confirmed service actions and notifications use the optional
[`mobilecompanion`](../server/mobile-companion/README.md) 1.1.0 plugin on MeshCentral 1.3.0.
Standard desktop, terminal, files, SSH and SFTP do **not** need it.

- Jobs require `MESHRIGHT_REMOTECOMMAND (0x20000)`; agent-console right 24 is not sufficient.
- The plugin accepts fixed templates only, patches no core files and exposes no arbitrary command API.
- The app does not install, configure, deploy or administer the server or the plugin.
- Live checks used an isolated MeshCentral 1.3.0 with a Linux Agent. Windows Agent, systemd and
  systemd service mutations remain untested. Multi-server MeshCentral is not supported by this plugin.

## Acceptance and release record

1. **Public build and API 35 tests passed.** Signed publication is checked separately against the release artifact and certificate.
2. **Physical-phone acceptance:** install/update over a signed build, sign-in/2FA, two sessions,
   rotation/background/lock/network loss/process death, share from real apps, notifications, Quick Help,
   TalkBack, large fonts and battery.
3. **Windows Agent and systemd:** terminal, files, services/processes, confirmed service actions,
   restart/shutdown and failure paths.
4. **Optional plugin:** isolated ACL tests and reviewed reverse-proxy installation/token revoke/rollback checks passed; Windows and systemd acceptance remains.
5. **Publication checks:** signed artifact, anonymous download, certificate/hash and update from the
   previous public build.
6. **Translation review:** native-speaker review of priority languages; current translations are
   machine-produced.

## What is not in 0.7.1

- No WebRTC. The WSS relay remains; MeshCentral 1.3.0 does not accept the terminal RTC offer. The
  loopback WSS numbers recorded during research are not mobile or desktop performance evidence.
- No Intel AMT KVM, RDP, VNC, chat, registry/software management or server administration.
- No permanent MeshCentral fork.
- Publication evidence is recorded separately from local testing.

## Evidence rules

- Do not report a test, server version, platform or device run that was not observed.
- Do not turn test presence into a pass result for the public build.
- Do not copy development-line results into public acceptance; label them as development evidence.
- Sanitize server names, addresses, account names, terminal output, file names and notification data in
  screenshots and logs.
- Preserve the public package and certificate identity; a locally signed build is not a public update.

## Documentation

- [Changelog](../CHANGELOG.md) · [Validation](VALIDATION.md) · [Known limitations](KNOWN_LIMITATIONS.md)
- [Interface](UI.md) · [Architecture](ARCHITECTURE.md) · [Protocol](PROTOCOL.md)
- [Optional server plugin](../server/mobile-companion/README.md)
- [Privacy](../PRIVACY.md) · [Security policy](../SECURITY.md)
