# MeshCentral Wire Protocol Notes

These notes describe the subset of the MeshCentral protocol used by this client. They were read from the public MeshCentral 1.2.5 sources and observed on the wire against a local MeshCentral 1.2.5 test instance with a real Mesh Agent.

Upstream source: <https://github.com/Ylianst/MeshCentral>.

Line numbers below refer to MeshCentral 1.2.5 and can drift between releases. This is not a stable public API: verify the behaviour against the server version you run before relying on a detail. The client reports server refusals instead of guessing when a response does not match.

## 1. Control channel

`wss://<host>/control.ashx`, one JSON object per text frame.

### Authentication

| Method | Detail | Source |
|---|---|---|
| `x-meshauth` header | `base64(user),base64(password)[,base64(2fa)]`; split on `,`, each part base64-decoded; 2 or 3 parts accepted | `webserver.js:9165-9175` |
| `?user=&pass=` | alternative query form | `webserver.js` WebSocket upgrade |
| `?auth=<cookie>` | encrypted cookie, 1 hour timeout; used for the relay viewer socket | `webserver.js:9135-9158` |
| Login token | user name starting `~t:`, used exactly like a password | `webserver.js` authenticate path |

A native client should not send an `Origin` header.

Failure frames: `{action:'close', cause, msg}` followed by `ws.close()` without a status code.
OkHttp reports that empty close frame as 1005, a reserved code that must not be echoed; answer with
1000. The WebSocket upgrade succeeds before the credentials are checked, so an open socket is not an
accepted sign-in: `userinfo` is. Verified against MeshCentral 1.3.1.

| `msg` | Meaning |
|---|---|
| `noauth-2a` / `noauth-2d` | user name or password rejected |
| `noauth-2b` | bad cookie |
| `noauth-2c` | malformed `x-meshauth` header (wrong number of parts) |
| `tokenrequired` | two-factor code required; extra fields `email2fa`, `sms2fa`, `msg2fa`, `twoFactorCookieDays` |
| `nokey` | domain URL access key missing |
| `notools` | account right `SITERIGHT_NOMESHCMD` forbids tool/API clients |
| `emailvalidation` | e-mail address must be validated |

Passing `**email**`, `**sms**`, or `**msg**` as the third `x-meshauth` part asks the server to send a new code; the answer is `tokenrequired` with `email2fasent`, `sms2fasent`, or `msg2fasent`.

### Remember this device (MeshCentral 1.3.1)

The following references are to the audited **1.3.1 npm package**, not the 1.2.5 references above:

| Exchange or condition | Source in MeshCentral 1.3.1 |
|---|---|
| Request `{"action":"twoFactorCookie"}` after accepted `userinfo`; response `{"action":"twoFactorCookie","cookie":"<opaque value>"}` | `meshuser.js:5154-5168` |
| No response when `req.session.loginToken != null` or `domain.twofactorcookiedurationdays === 0`; no `responseid` support | `meshuser.js:5156-5160` |
| Missing/non-numeric duration or a value below 1 falls back to 30 days; payload contains `userid` and `expire` in minutes (`days * 24 * 60`); IP binding is commented out | `meshuser.js:5163-5166` |
| `tokenrequired.twoFactorCookieDays` advertises the configured duration, default 30 | `webserver.js:9462-9470`, `webserver.js:9481-9500` |
| `checkUserOneTimePasswordRequired` splits `Cookie` on `; `, decodes `twofactor=`, checks user id and optional IP; a valid cookie skips the code | `webserver.js:1117-1145` (cookie check: `1126-1135`) |
| Two-part `x-meshauth` still validates username/password, then consults that cookie check before `tokenrequired` | `webserver.js:9442-9470`, `webserver.js:9514-9528` |
| Cookie encoding uses AES-GCM; creation time is added by the server; base64 replaces `+` with `@` and `/` with `$` | `meshcentral.js:3954-3962` |
| Server expiration uses the embedded `expire` minutes, if present | `meshcentral.js:4009-4019` |

The native client requests the cookie only after a sign-in with a nonempty verification code and the
checked remember-device option. Code-delivery markers (`**email**`, `**sms**`, `**msg**`) do not qualify.
The default checked option displays the advertised number of days; duration zero hides it and disables
issuance. Invalid durations use 30 days; fractional days are rounded down to avoid retaining the cookie
past the advertised lifetime. Silent refusal does not block sign-in. A one-time code is discarded after
`userinfo`, so automatic reconnects cannot replay it.

The cookie, normalized server endpoint (including scheme, port and domain path), exact trimmed username,
creation time, duration, expiry and issuing TLS trust mode are one encrypted record per profile in
`SecureVault`. Trust is either platform validation without a pin or the exact pinned leaf SPKI.
The cookie can be sent only under that same mode/key. The immutable transport supplies the scope
both when requesting the control connection and when receiving the cookie; an old callback cannot
relabel it after a trust change. Legacy records without trust metadata are discarded. Only the
active account is retained. `MeshCookieJar` merges `twofactor` into its single `Cookie` header only for
that account's exact `control.ashx` URL over HTTPS (HTTP is allowed only in debug builds). It rejects
ordinary `Set-Cookie: twofactor=...` storage. Relays, other origins/ports/paths, WebView and notification
requests cannot inherit this cookie; control redirects remain disabled. Debug HTTP has its own
isolated scope; the debug self-signed TLS bypass cannot issue or send remembered cookies.

Local expiry or a clock rollback discards the record. Logout, credential clearing, profile deletion or
switch, server/account change, explicit pin approval/replacement/removal, certificate rejection,
bad credentials and **Forget this device** also discard it. Restoring an unchanged saved pin after
process restart preserves it. A later
`tokenrequired` discards a server-rejected cookie and preserves the ordinary code step without an
automatic retry. Delayed cookie replies after forgetting or replacing a socket are ignored. Forgetting
is local deletion, not a server-side revocation API. No TOTP secret or MeshCentral login token is stored.

**Revocation limit in MeshCentral 1.3.1:** there is no per-device server-side revocation for this
stateless cookie. Its payload has no device id, TOTP version or password-change timestamp
(`meshuser.js:5163-5166`); validation checks its user id and lifetime, not a revocation list
(`webserver.js:1126-1135`, `meshcentral.js:4009-4019`). Changing/resetting TOTP therefore does not
invalidate it. After losing a phone, change the account password from a trusted device immediately:
the native login still authenticates that password first (`webserver.js:9442-9470`), so the old
password stops working, although the cookie itself remains valid. Ask the administrator to terminate
active sessions. Rotating `loginCookieEncryptionKey` invalidates cookies encrypted with the old key
server-wide, including this one; it is an administrator operation with wider effects, not a feature
of this Android client. Local **Forget this device** cannot revoke a stolen copy.

### After connect

1. `{action:'serverinfo', serverinfo:{domain, name, port, …}}`
2. `{action:'userinfo', userinfo:{_id, name, siteadmin, links}}` (`siteadmin` is an unsigned 32-bit value; `4294967295` means full rights)
3. The device list is **not** pushed. Send `{"action":"meshes"}`, `{"action":"nodes"}`, and `{"action":"authcookie"}`.

### Payload shapes

- `meshes`: array of `{_id:'mesh/<domain>/<hash>', name, desc, mtype, links:{'user/<domain>/<name>':{rights}}}`.
  Rights bits (`meshrelay.js:210-220`): 1 edit mesh, 2 manage users, 4 manage computers, **8 remote control**,
  16 agent console, 32 server files, 64 wake device, 128 set notes, **256 remote view (view-only)**.
- `nodes`: object keyed by **meshid**, each value an **array** of nodes:
  `{"nodes":{"mesh/<domain>/<hash>":[{_id, name, conn, pwr, osdesc, ip, mtype, agent:{id,ver,caps}}]}}`.
  `conn` is a bitmask (1 = agent, 2 = CIRA, 4 = AMT local, 8 = relay); `0` means offline.
- `event`: `{action:'event', event:{action:'nodeconnect'|'changenode'|'addnode'|'removenode', …}}` keeps the
  list live without re-requesting it.
- `authcookie`: `{action:'authcookie', cookie, rcookie}`. `cookie` is used as `?auth=` on the relay socket,
  `rcookie` as `&rauth=` in the tunnel request (`meshuser.js:6462`).
- Routing answers carry `{action:'msg', result:'OK'|'Unable to route', tag, responseid}` when the request
  included a `responseid` (`meshuser.js:1086`).

## 2. Desktop relay (KVM, usage 2)

### Opening a session

1. The viewer asks the agent to dial the relay:
   `{"action":"msg","type":"tunnel","nodeid":"node/<domain>/<hash>","usage":2,"value":"*/meshrelay.ashx?p=2&nodeid=<nodeid>&id=<id>[&rauth=<rcookie>]"}`
   `meshuser.js:1023-1037` rejects the request when the `nodeid` inside `value` differs from `command.nodeid`,
   and requires remote-control (8) or remote-view (256) rights.
2. The viewer connects to
   `wss://<host>/meshrelay.ashx?browser=1&p=2&nodeid=<nodeid>&id=<id>&auth=<cookie>`
   (`meshrelay.js` viewer path; the `auth` cookie authenticates the socket when no browser session exists).

### Handshake

1. The relay sends the **text** frame `c` (or `cr` when the session is recorded).
2. The viewer sends its **protocol number as text**: `2` for KVM
   (`agent-redir-ws-0.1.1.js:150-157`, `obj.protocol = 2` in `agent-desktop-0.0.2.js:37`).
3. Only then does binary KVM data flow.

### Initialisation after the first screen-size command

`ProcessScreenMsg` (`agent-desktop-0.0.2.js:202-217`) and `case 7` (`:236-256`) send, in this order:

| Frame | Command |
|---|---|
| `00 05 00 0A 01 3C 04 00 00 32` | 5 compression: JPEG, quality 60, scaling 1024, frame timer 50 ms |
| `00 08 00 05 00` | 8 unpause |
| `00 57 00 05 02` | 87 input-lock query |
| `00 01 00 06 01 10/11/12/5B/5C/10` | 1 key-up for VK 16, 17, 18, 91, 92, 16 |
| `00 0E 00 04` | 14 KVM_INIT_TOUCH (enables touch input on the agent) |

`00 0B 00 04` (11 get displays) is sent separately to enumerate monitors.

### Binary framing

Header: `command:u16`, `size:u16` (total length, including the header). All integers are big-endian.

The official Android agent 1.0.23 also sends a **standalone binary `00` keepalive** on tunnel startup
and periodically (`MeshCentralAndroidAgent` tag `1.0.23`, `MeshTunnel.kt:377-386`). It is not part
of the KVM stream. MeshCentral 1.3.1's viewer skips short standalone messages before accumulation
(`public/scripts/agent-redir-ws-0.1.1.js:207`). On the isolated 1.3.1/1.0.23 bench the observed sequence
was `00`, then `00 07 00 08 04 38 09 60` (1080 x 2400), then a jumbo image. The client ignores only
that single-zero message when no command is pending; zero bytes completing an already fragmented
command remain data. Verified by JVM framing tests, a socket fixture asserting decoded pixels,
and the real Android capture session.

- A WebSocket payload may contain **several** commands and may end in a **partial** command; accumulate
  until the declared size is available (`agent-redir-ws-0.1.1.js:196-231`).
- **Jumbo**: `command == 27 && size == 8` means the wrapped command starts at offset **8** and its real length
  is a big-endian 32-bit value at offset **4**; the wrapped command's own `size` field is written as `0` and
  must be ignored (`agent-desktop-0.0.2.js:230-232` writes it, `agent-redir-ws-0.1.1.js:224` reads it back).
  Total wire length is `8 + realSize`.

### Server to client

| Cmd | Payload |
|---|---|
| 3 | Tile: `x:u16, y:u16`, then a complete image (JPEG by default) |
| 4 | Copy rect: `sx:u16, sy:u16, dx:u16, dy:u16, w:u16, h:u16` |
| 7 | Screen size: `width:u16, height:u16` |
| 11 | Displays: `count:u16`, `count` × `id:u16`, `selected:u16` (`65535` = all displays) |
| 12 | Display switch acknowledged |
| 14 | Touch initialisation acknowledged |
| 17 | Console message (UTF-8) |
| 18 | Keyboard LED state: size must be 5, payload byte: 1 NumLock, 2 ScrollLock, 4 CapsLock |
| 27 | Jumbo wrapper |
| 65 | Alert text (a leading `.` means "log quietly") |
| 82 | Display geometry: 10-byte records `id:u16, x:u16, y:u16, w:u16, h:u16` |
| 87 | Input lock state: size must be 5, payload byte 0/1 |
| 88 | Mouse cursor index: size must be 5 |

### Client to server

| Cmd | Payload (byte-exact, from `agent-desktop-0.0.2.js`) |
|---|---|
| 1 | Key: `action:u8` (0 down, 1 up, 3 extended up, 4 extended down), `vk:u8` |
| 2 | Mouse: `00, buttons:u8, x:u16, y:u16` (size 10); wheel: `00, 00, x:u16, y:u16, delta:i16` (size 12) |
| 5 | Compression: `type:u8` (1 JPEG, 2 PNG, 3 TIFF, 4 WebP), `quality:u8`, `scaling:u16`, `frameTimer:u16` |
| 6 | Refresh |
| 8 | Pause: `paused:u8` |
| 10 | Ctrl+Alt+Del |
| 11 | Get displays |
| 12 | Set display: `display:u16` |
| 85 | Unicode key: `action:u8` (0 down, 1 up), `codepoint:u16` (UTF-16 code unit) |
| 87 | Input lock: 0 unlock, 1 lock, 2 query |

Mouse buttons: left `0x02`/`0x04`, right `0x08`/`0x10`, middle `0x20`/`0x40`, double click `0x88`.
Mouse coordinates are **absolute framebuffer pixels** (`obj.SendMouseMsg` uses canvas coordinates directly).
Negative wheel deltas use MeshCentral's complement form (`255 - (abs >> 8)`, `255 - (abs & 0xFF)`), not
two's complement.

### Stylus / S Pen (M8, 0.8.1)

Audited against MeshCentral **1.3.1**, `public/scripts/agent-desktop-0.0.2.js:561–580`
(`SendTouchMsg1` / `SendTouchMsg2`) and `582–650` (`SendMouseMsg`). The mouse format remains
KVM 2, ten bytes: `00 02 00 0A 00 flags x:u16 y:u16`, with absolute framebuffer pixels.
Hover and drag movement use `flags=00`; left down/up `02/04`, right `08/10`, middle `20/40`.
The client reuses `KvmInputEncoder`; it introduces no pen command or touch-protocol extension.
Neither this mouse message nor the audited touch messages carry pen pressure, tilt or eraser data.

Android `TOOL_TYPE_STYLUS` bypasses the selected computer touch/trackpad/scroll/pan mode when
“Stylus as a precise mouse” is enabled (the default). Hover enter/move sends only a mouse move.
Contact presses left; moves retain the press until release. `BUTTON_STYLUS_PRIMARY` selects right,
`BUTTON_STYLUS_SECONDARY` selects middle (secondary wins if both are pressed). `TOOL_TYPE_ERASER`
selects right, ahead of both side buttons: this is a context-menu convention, **not remote erasing**.
The button is selected at contact and latched until release, including when the side button is let go.
Coordinates use `RemoteViewport` for fit/zoom/pan; presses and hover outside the image are ignored,
and an existing drag clamps to the frame edges. Cancellation attempts to release only its own press.

Palm contacts cannot click, move the remote cursor or scroll while a pen hovers or touches. A rejected
finger gesture stays rejected until all its fingers lift. Two actual fingers still zoom/pan the local
picture; a pen plus one palm is not a pinch. Two fingers cancel an active pen stroke. Disabling the
setting restores the existing touch-mode behavior for the pen. The preference and first-use hint
flag are non-secret, global local settings; they do not change profile credentials or server state.
Android can synthesize a hover exit immediately before a palm contact. Finger gestures beginning
within 250 ms of hover exit are also rejected; after that guard, ordinary finger input resumes.

For an Android phone, only the existing `remoteInput == enabled` capability plus session/rights
checks permit input. Pen/eraser/side-button contact all mean a left-button touch. Hover is never sent.
Completed gestures reuse `PhoneTouchGesture`'s bounded timing/trajectory replay and cancellation
rules below; the phone owner's consent is still mandatory. Pressure and tilt are ignored locally.
See [implementation and validation](STYLUS.md) and the
[Android MotionEvent reference](https://developer.android.com/reference/android/view/MotionEvent).

### Keepalive and close

Text frames `{"ctrlChannel":102938,"type":"rtt","time":<ms>}` every 10 s; the echo carries the same `time`,
which yields the round-trip time. Answer `{"ctrlChannel":102938,"type":"ping"}` with a `pong`. Before closing
send `{"ctrlChannel":102938,"type":"close"}`.

## 3. Clipboard over the control channel

```json
{"action":"msg","type":"setclip","nodeid":"node/<domain>/<hash>","data":"text"}
{"action":"msg","type":"getclip","nodeid":"node/<domain>/<hash>","tag":"optional"}
```

Responses (observed on the wire and in `agents/meshcore.js:1604-1666`):

```json
{"action":"msg","type":"setclip","success":true,"nodeid":"…"}
{"action":"msg","type":"getclip","data":"text","tag":"…","nodeid":"…"}
```

Notes:

- The domain settings `clipboardget` / `clipboardset` can disable the feature (`meshuser.js:1022-1023`);
  when disabled the server silently drops the command.
- The agent only answers when it runs as a service (`require('MeshAgent').isService`) and only sends a
  `getclip` reply when the remote clipboard is not empty, so a client must apply its own timeout.
- Remote control (8) or remote view (256) rights are required to route the command (`meshuser.js:287`).

## 4. Terminal and file tools (0.5.1)

These paths were additionally reviewed against upstream commit
`60cea8731f88b91a09704ff9a00dab4992a98ef1` (package 1.2.6). Live acceptance used npm
MeshCentral **1.2.5**, an isolated Linux Mesh Agent and an SSH/SFTP host; it does not certify 1.2.6.

| Tool | Endpoint and handshake |
| --- | --- |
| Mesh Agent terminal | `meshrelay.ashx?p=1`; `c`/`cr`, terminal options, then protocol `1`; raw UTF-8 bytes and terminal-size controls |
| Mesh Agent files | `meshrelay.ashx?p=5`; `c`/`cr`, then protocol `5`; JSON commands and bounded binary download/upload frames |
| SSH terminal | `sshterminalrelay.ashx` with the authenticated server cookie and node; `sshauth`/`sshautoauth`, `c`, then `~`-prefixed shell output |
| SFTP | `sshfilesrelay.ashx`; its own authentication and file-framing adapter, not the Agent files handshake |

Agent files **application JSON** is binary UTF-8, even though the options, protocol number `5`,
and relay control messages use WebSocket text. The viewer's `sendText` encodes UTF-8 and calls its
binary `send` (`MeshCentral 1.3.1`, `public/scripts/agent-redir-ws-0.1.1.js:259-284`). The official
Android agent 1.0.23 ignores text after the handshake and processes JSON only in binary `onMessage`
(`MeshTunnel.kt:185-268`). Linux tolerates text commands, which concealed the Android failure.
The transport now encodes AgentFiles strings as binary; SSH/SFTP framing is unchanged.

Android's virtual root is `path:""`, with `Audio`, `Images`, `Sdcard`, `Videos` directories. Their
paths are **relative**, e.g. `Images` or `Sdcard/Download`, and a category's parent is `""`, not `/`
(`MeshCentralAndroidAgent` tag `1.0.23`, `MeshTunnel.kt:407-422,554-559`). Windows drive roots stay
`C:/`; Linux reports `/` as its initial directory and keeps absolute paths. A socket fixture checks
the binary request, UTF-8 reply, category opening and return to the root.

### Download destination lifecycle (Android client, livebench 1.3.1)

Choosing a SAF destination is part of the active transfer, before `download/start` is sent.
Previously `FilesController.prepareDownload` only retained the selection, while the foreground
service was started after opening the output stream (`FilesScreen.kt` download action and
`MeshCentralApp.kt:160-167`). With the picker covering the Activity, the bench recorded a control
`SocketException` (`Software caused connection abort`); the returned URI therefore reached a closed
file session. No download request had reached Linux. This was a client lifecycle failure, not a
different Linux download format. Android also documents that [frozen cached processes lose their
TCP sockets](https://source.android.com/docs/core/perf/cached-apps-freezer).

The selection now reserves `busy`/`transferName` and explicitly starts the existing
`FileTransferService` before launching SAF. The reservation lasts through output-stream opening;
cancellation or launch failure releases it. A late URI after a real disconnect is still neither
opened nor deleted, and transfers are never automatically retried. The captured Linux response was
`01 00 00 01` plus 26 file bytes, matching `agents/meshcore.js:4231-4235`; `download/startack` and the
size check are unchanged. Live acceptance compares the saved document byte-for-byte and by SHA-256
with `/opt/meshcentral-livebench/fixture/hello.txt`.

Server SSH relays terminate SSH on the MeshCentral server. For `mtype=2`, they use an Agent TCP
tunnel; direct `localrelay` applies to `mtype=3` without an alternate relay. Thus an explicitly
offline Agent node without an alternate relay is unavailable, while missing metadata permits an
explicit server-authorized attempt. Server authorization remains authoritative.

Upstream can send SSH `c` before the shell callback is attached. The client enables input only
after nonempty shell output; a silent shell times out after 30 seconds. It also bounds the pre-`c`
handshake, excluding time spent entering credentials. It never reconnects or replays terminal input.

Both upload adapters verify success by reading back SHA-256 and refreshing the directory. SFTP's
upstream `uploaddone` can occur after a write failure; this frame alone is not success. Downloads
check the expected byte count; the SFTP adapter also handles empty and exact-block files where the
server can omit a final EOF block. A remote change after verification is still possible: these
protocols do not provide an atomic snapshot or an atomic no-overwrite operation.

Directory responses are capped at 1 MiB and 20,000 entries. Terminal queues apply bounded backpressure
outside locks; closing releases waiting producers. Individual frames above 1 MiB fail explicitly.
File changes already sent when cancelled remain unconfirmed. Failed downloads close the output and
delete their newly created SAF destination; if the provider refuses deletion, the client attempts a
`.partial` rename and reports deletion, marking, or failure of both. Successful downloads are retained.
Unknown picker callbacks and upload sources are not deleted. See the protocol/controller tests and
[validation](VALIDATION.md) for actual coverage.

## 5. Official Android phones (MeshCentral 1.3.1)

This section is a static source audit plus synthetic JVM/Compose/transport tests, not live phone
acceptance. Server references below are **MeshCentral 1.3.1**, independent of the older sections.
Android references are `app/src/main/java/com/meshcentral/agent/` on the agent repository's `main`,
commit `5ba4560f749447afa4b90d52fc52f394c3c8ff40`. The checked-out experimental input branch is not the
compatibility target.

### Identity, capabilities and screen

`agent.id == 14` identifies Android (`agenttypes.js:19`). Capability mask 13 advertises desktop (1),
files (4), and console (8); terminal bit 2 is absent. Existing capability checks disable the terminal.
Older official agents remain view-only. The remote-control agent described below can explicitly
advertise input support. Phone viewing never sends keyboard/touch initialisation commands, even
when capability is enabled: opening a viewer must not trigger the agent's control-consent prompt.

### Phone remote input (M4.7)

Audited agent source: repository `meshcentral-android-agent`, branch `feature/remote-control`, commit
`c2872c94452be2816886376ae68a0286129c5906`. These are local, unpublished changes, not a claim that the
official upstream release supports input. Read an immutable source with
`git show c2872c94452be2816886376ae68a0286129c5906:<path>` in that repository:

| Source path | Evidence |
| --- | --- |
| `docs/remote-control.md`, Capability discovery / Input and limitations | Capability, consent and limitations |
| `app/src/main/java/com/meshcentral/agent/MeshAgent.kt`, `sysinfo` branch / `sendInputState` | `data.remoteInput`, hash, `sysinfocheck` |
| `app/src/main/java/com/meshcentral/agent/RemoteInput.kt`, `RemoteInputProtocol.parse` / `InputGestures` | Exact packet sizes, unsigned coordinates, wheel encoding and 40 ms gesture steps |
| `app/src/main/java/com/meshcentral/agent/MeshInputService.kt`, `mouse` / `key` / `editText` | Touch/hold/drag, navigation, UTF-16 surrogate assembly, password rejection |
| `app/src/main/java/com/meshcentral/agent/RemoteControlSession.kt` | Rights and per-tunnel consent |

The agent sends `sysinfo.data.remoteInput`: `unsupported`, `disabled` or `enabled`. MeshCentral 1.3.1
stores that complete data document (`meshagent.js:1505-1520`) and returns `remoteInput` at the **root**
of `getsysinfo` (`meshuser.js:6961-7044`), alongside `hardware`. The client parses only exact strings;
absent, malformed, unknown and `noinfo` replies keep input disabled. DEVICEDETAILS (1048576) is needed
to read this capability. Opening a phone desktop refreshes it; `sysinfohash` invalidates and reloads
it. Replies are correlated to the node/request and superseded replies cannot restore capability.

Input additionally requires a current online agent connection, right 8 and no right 256, 4096 or
65536. The server's full-rights sentinel is handled explicitly. Unknown rights, lost control-channel
state, a locked input stream and an incomplete desktop handshake cannot enable input. The relay
rechecks the latest device rights before sending; the agent still enforces owner consent separately.

All integers are big endian. KVM 2 uses `00 02 00 0A 00 flags x:u16 y:u16` with absolute **framebuffer**
pixels: left down `flags=02`, move `00`, left up `04`. Tap and hold use down/up with their elapsed
time; swipe adds moves. The client maps the picture through `RemoteViewport` (fit, zoom and pan),
ignores presses outside it and uses swipe for scrolling. The agent converts wheel packets to swipes
anyway, so direct trajectories avoid a second distance/direction conversion. Continued drag needs
API 26+; API 24–25 agent support is limited to buffered tap/hold.

Phone gestures are buffered until finger release so a second finger can cancel **all** remote input
and only zoom/pan the local picture. A completed single-finger gesture then replays its timing and
trajectory; this deliberately adds latency (especially to long holds). Sampling is bounded to 40 ms,
201 recorded points and 8 seconds, below the agent's 10-second watchdog. A running replay is not
overlapped with another; disposal, rotation or capability loss cancels it and attempts a release.
Computer trackpad, direct-touch, two-finger and right-click policies are unchanged.

Panel keys use KVM 1: `00 01 00 06 action key`, down `action=00`, up `01`:

| Button | Virtual key | Hex |
| --- | --- | --- |
| Back | Esc / 27 | `1B` |
| Home | Home / 36 | `24` |
| Recents | F9 / 120 | `78` |
| Notifications | F10 / 121 | `79` |
| Quick settings | F11 / 122 | `7A` |

Text uses KVM 85: `00 55 00 07 action codeunit:u16`, down/up for **each UTF-16 code unit**. For example
😀 produces `D83D` then `DE00`; the agent combines the surrogate pair. Text appends using
`ACTION_SET_TEXT`; it does not preserve selection or emulate an IME. The draft is not persisted.
The UI warns that **all input is rejected while a password field has focus**. PIN/lock-screen input
is unavailable. Capability is not a control grant: the phone owner must approve the agent's local
prompt, and can stop control using its overlay. No client action bypasses those checks.

For live input acceptance, capture the **agent** with `adb exec-out screencap -p`. Do not run
`adb shell uiautomator dump` on that device while controlling it: its UI automation connection can
suppress the input accessibility service, revoking the tunnel's grant and disturbing capture/consent.
An instrumentation observer must use `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, as in
the audited agent's `app/src/androidTest/java/com/meshcentral/agent/RemoteControlDeviceTest.kt`.
UI automation on a separate client emulator does not suppress the agent's service.

### Live battery and cached information

| Data | Wire shape / handling | Source (1.3.1 unless stated) |
| --- | --- | --- |
| Battery snapshot | `nodes` → `node.sessions.battery: {level, state}` | `meshuser.js:859-861` |
| Battery event | `{action:"event", event:{action:"devicesessions", nodeid, sessions:{battery:{level,state}}}}` | `meshagent.js:1587-1594`, `1959-1967` |
| System | `{action:"getsysinfo",nodeid,nodeinfo:true}` → top-level `hardware`, optionally `node` | `meshuser.js:6961-7044` |
| Network | `{action:"getnetworkinfo",nodeid}` → `netif2`/legacy `netif` | `meshuser.js:6936-6958` |
| Android system fields | `hardware.mobile.model`, `.androidrelease`; storage under `hardware.identifiers.storage_devices` | Android `MeshAgent.kt:365-387`, `601-635` |
| Android addresses | `netif2` maps interface names to arrays with `address` and `family` | Android `MeshAgent.kt:638-669` |

Battery `level` is a number in 0..100 (including fractions); `state` is `ac` or `dc`. `ac` displays
charging. Missing/malformed fields remain unknown, not zero. An empty `devicesessions` clears the
battery; `changenode` preserves it because that event contains no sessions (`meshagent.js:874`).
Losing the agent connectivity bit clears it; stale device snapshots do not display it. The battery
is not persisted. System/network information requires DEVICEDETAILS (1048576), and may be cached or
unavailable; no Companion extension is needed for phone information.

### Phone actions

Every phone action requires a **fresh** device snapshot, known online agent bit (`conn & 1`), and
REMOTE_CONTROL (8), checked again immediately before sending. Unknown rights and view-only (256)
are not grants, even though the server's generic `msg` router accepts 8 **or** 256
(`meshuser.js:277-323`, `1021-1104`). No command is replayed after reconnection.
Console actions (message, notice, link and dial pad) additionally require AGENT_CONSOLE (16).
Revoking that permission closes an open console-action dialog; vibration/flashlight still require only 8.

| Action | Exact request fields | Source |
| --- | --- | --- |
| Vibrate 5 s | `{action:"poweraction",nodeids:[nodeid],actiontype:401,time:5000}` | `meshuser.js:3402-3462`, especially `3419`; Android `MeshAgent.kt:916-954` |
| Flashlight 10 s | `{action:"poweraction",nodeids:[nodeid],actiontype:400,time:10000}` | `meshuser.js:3418`; Android `MeshAgent.kt:956-991` |
| Message | `{action:"msg",type:"console",nodeid,value:'alert "message" "title"'}` | `views/default.handlebars:14252-14255`; Android `MeshAgent.kt:750-774` |
| Brief notice | same console envelope, `value:'toast "message"'` | Android `MeshAgent.kt:776-789` |
| Open link | console envelope, `value:'openbrowser "https://example.com/"'` | Android 1.0.23/main `MeshAgent.kt:821-842`; `msg/openUrl` is not supported |
| Dial pad | console envelope, `value:'dial +123*45%23'` | Android `MeshAgent.kt:791-798`, `Uri.parse("tel:...")` |

Forms limit message/title to 1000/80 UTF-16 code units, URL to 2048, number to 40. URLs must be
absolute URLs beginning with lowercase `http://` or `https://` (the agent checks case), with a host,
without whitespace, ASCII double quotes or embedded credentials. Reject literal spaces/quotes rather
than inventing an escape syntax the agent does not implement; percent-encoded `%20`/`%22` remain valid.
Dial strings accept only ASCII digits, `+`, `*`, `#`; encode each `#` as `%23` so it stays in the `tel:`
scheme-specific part instead of starting a fragment. The person holding the phone initiates the call.

**Quoting limitation:** Android `parseArgString` (`MeshAgent.kt:715-729`) splits only on unquoted ASCII
spaces and consumes every ASCII double quote as a delimiter. It implements **no backslash escape**.
Lossless ASCII quotation marks are therefore impossible through this console API. The client replaces
them with U+201D, explains this visibly and previews the exact rendered message/title before the user
confirms. Newlines, backslashes, Cyrillic and emoji remain literal argument content; JSON serialization
escapes the enclosing wire message. Alert always supplies a nonempty title, avoiding the agent's
one-argument branch defect at `MeshAgent.kt:759`. Other control characters are rejected. Message
contents may be logged by the server/agent (`MeshAgent.kt:739-743`).

All actions have confirmation. The result says **Command sent; execution not confirmed** when the
local WebSocket accepts the message. `poweraction`'s `ok` is not an agent execution acknowledgement
(`meshuser.js:3455-3460`); generic `msg` results confirm routing only (`1098-1100`).

### Android file adapter

Android's p5 protocol differs from desktop Mesh Agents:

- The handshake still sends text `5`; subsequent listing/upload/delete JSON must be **binary UTF-8**
  (`MeshTunnel.kt:187-260`, `262-307`, `400-540`). Listings echo `reqid`. Virtual roots are `Sdcard`,
  `Images`, `Audio`, `Videos`, with **no leading slash** (`407-424`, `545-610`). The adapter keeps
  those relative paths in listing responses, matching `AgentFilesProtocol` and its empty virtual
  root. Outgoing legacy absolute paths are still accepted and stripped at the transport boundary.
  Binary commands use `sendBinary`; direct AgentFiles callers use the same binary wire format via
  `ManagedRelayConnection.sendText`. The adapter does not call both, so each command is sent once.
- `mkdir`, `rename`, and `uploadcancel` are not implemented in the p5 command switch. Create/rename
  controls are hidden. Cancellation closes the session and reports an uncertain/partial result,
  never a proven removal. Virtual roots cannot be deleted or used as upload destinations.
- `rm` requires a string `reqid` (`839-842`). The client waits for the response (which may require a
  phone-side permission dialog) before rereading the listing; a response alone is not success.
- Download uses **a separate usage 10 tunnel**: text options `{type:"options",file:"Sdcard/file.bin"}`
  followed by text `10`. Released **1.0.23** (`527ca04`, `MeshTunnel.kt:712-805`) streams raw binary
  bytes and closes; **main** (header added in `eadd6d5`) first sends text `{op:"ok",size:N}`.
  Both advertise version 1.0.23, so the adapter detects the format from the first non-service frame,
  never from the version or binary file contents. For a headerless download the fresh preflight
  directory size is the required bound, including zero-length files. A header must agree with that
  size. There are no p5 `download/startack/ack` messages on the wire. The adapter preserves JSON-looking
  bytes, rejects truncation/excess bytes and emits EOF only after a normal close and the exact size.
  FilesController also compares fresh pre/post-transfer listings and SHA-256 during upload readback.
- Both agents send file data in WebSocket frames of at most 65,535 bytes. The 1 MiB limit is per frame,
  not a cumulative download limit. On the local Android emulator, the `10.0.2.2` network path produced
  a TLS `BAD_DECRYPT` / `DECRYPTION_FAILED_OR_BAD_RECORD_MAC` after 19 full frames for a 1,347,662-byte
  fixture. The same client APK and released agent transferred the exact file through
  `adb -s emulator-5632 reverse tcp:4443 tcp:4443` and the loopback HTTPS endpoint. This isolates an
  environment-dependent transport failure; it does not establish a defect in the p10 framing or a
  particular TLS implementation. Cryptographic errors remain fatal. Socket fixtures replay the
  observed frame boundaries and an abnormal close; controller fixtures verify deletion of a partial
  content document after transport failure, short EOF, cancellation, owner cancellation and close.
- Standalone binary `00` is discarded before parsing on p5 and p10, at every stage. The Android
  connection timer sends it immediately and every 120 seconds on p5 (`MeshTunnel.kt:377-386`).
  Relay `ctrlChannel:102938` metadata (including `pong`/RTT), and plain ping/pong text, are not p10 data.
  A p5 disconnect cancels p10 and releases the picker/transfer reservation; failure handlers never
  suspend sending into a closed channel. A partial download is not reported as success.
  **Known ambiguity imposed by the keepalive contract:** upstream 1.0.23 actually exempts usage 10
  from its timer. A single-byte zero file (or zero as the last one-byte p10 block) is indistinguishable
  from the mandated standalone `00` service frame. It is discarded and the size check fails safely;
  the client never reconstructs a byte from the advertised size or claims that download succeeded.
- Media listings in both versions contain only `n/t/s/d` (`MeshTunnel.kt:545-592`), **no full path or ID**.
  Duplicate names remain separate indexed rows. Their download/delete actions are disabled with an
  explanation to choose the actual `Sdcard` folder: the agent addresses media by name and would select
  an arbitrary match. Directory duplicates outside media categories remain malformed. The 1 MiB
  frame/JSON budget remains: an oversized response to an outstanding Android media listing produces
  a bounded, correlated error and keeps p5 connected, so the user can browse a smaller folder.
- The virtual phone root rejects queued uploads before opening a local stream; Send here is disabled
  with a category-selection hint. Picker preparation, failure and completion all hold the controller
  mutex. Success, cancellation, disconnect, stream-open failure and owner cancellation release the
  foreground-transfer reservation.
- Upload remains p5 with escaped binary chunks and `uploadstart/uploadack/uploaddone`
  (`269-298`, `430-535`). Success still requires a p10 SHA-256 readback and fresh listing. Android
  storage permissions, supported media extensions and MediaStore naming rules apply. A different
  destination/name or duplicate media display name cannot be reported as verified success.
  MediaStore can still advertise a stale length immediately after `uploaddone`; for a unique media
  entry the client re-reads that metadata up to five times, one second apart, before starting
  readback. It never resends file bytes or accepts the stale size. An unchanged mismatch still fails.

Real device acceptance must cover consent, MediaStore permissions, vibration/flashlight, notifications,
link/dial behaviour, portrait rotation and file transfers on the target server/Android versions.

### KVM message padding

MeshCentral 1.3.1 `agent-redir-ws-0.1.1.js:224-235` trims a complete Agent command to its declared
length. The native WebSocket decoder also discards an optional tail to the next 8-byte boundary,
including jumbo-wrapped commands. Padding bytes have no semantic value and cannot seed the next
command. The generic streaming mode still accepts concatenated/fragmented commands without this
message-boundary rule. Regression fixtures follow padded packets with a screen-size packet.

## 6. Computer parity additions (MeshCentral 1.3.1)

This section was checked against the **npm MeshCentral 1.3.1 package**, not the 1.2.5
line numbers above. Paths and line numbers below refer to that package. Static source inspection
and synthetic tests do not establish live agent compatibility.

### Windows security metadata

- `nodes` includes `wsc` and `av`: `meshuser.js:827-877` removes selected private fields, then returns
  the node. `meshagent.js:2012-2022` copies `av`, `wsc`, and `lsc` from the agent; there is no schema
  validation there. The `changenode` broadcast carries a safe node copy (`meshagent.js:2073`).
- `wsc` is an object containing `antiVirus`, `autoUpdate`, `firewall`; the web client compares each
  with `"OK"` (`views/default.handlebars:7965-7969`). `av` is an array of objects with `product`,
  boolean `enabled`, boolean `updated` (`views/default.handlebars:7998-8006`).
- The Windows agent enumerates `OK`, `WARNING`, `PROBLEM`, `UNKNOWN` in
  `agents/modules_meshcore/win-securitycenter.js:39-60` (health 0, 1/3, 2, other). `BAD` is a web
  display label, not a Windows wire value. Missing/null/malformed/future values stay **unknown**.
  The attention filter matches WSC WARNING/PROBLEM, or a nonempty antivirus list in which every
  product is explicitly disabled, or an enabled product with explicitly stale definitions.
  A disabled Defender alongside an enabled, current product is not by itself a problem.
  Unknown flags remain unknown. Both the card and filter apply only to Windows OS/agent types;
  Linux ClamAV `av` data (`agents/modules_meshcore/linux-info.js:52-86`) does not make a Windows card.
- A partial `changenode` that omits `wsc` or `av` preserves that field's last known value. An explicit
  null clears it. A present WSC object replaces the previous object; absent subfields become unknown.
  The inspector labels these as last reported, potentially stale data. It retains at most 100
  antivirus entries and 256 characters per product name. `lsc` is not interpreted by this feature.

### Message to the computer user

```json
{"action":"toast","nodeids":["node//example"],"title":"Maintenance","msg":"Please save your work."}
```

`meshuser.js:3464-3509`: message length 1–4096 UTF-16 code units, title length 1–512 (otherwise the
server substitutes its default), right `MESHRIGHT_CHATNOTIFY = 16384` (`meshuser.js:54,3495`).
The client additionally rejects blank text, unknown rights, stale connectivity, non-agents,
Android agent id 14 and a missing agent connection bit (`conn & 1`). Both fields are previewed before
the final Send action; the current device and rights are checked again when dispatching.

The existing request broker adds unique `tag`/`responseid` fields. The server answers
`{action:'toast', responseid, result:'ok'}` outside its asynchronous per-node callback, even if the
agent is offline; the callback may additionally return `Access Denied` (`meshuser.js:3495-3509`).
Therefore **ok is not a delivery receipt**: the UI says sent, delivery not confirmed. A negative
first response or timeout is an error. The broker retains up to 64 toast acknowledgement ids until
disconnect/eviction; a later correlated `Access Denied` replaces the inspector notice with the
actual refusal. It never reports delivered, logs the content, or retries it. Preview and Send use
different buttons/positions; Send is armed after 750 ms and accepts at most one dispatch.

### Profile keyboard shortcuts

This adds no MeshCentral command. A shortcut is local profile metadata: label (1–40 characters),
a supported key and Ctrl/Alt/Shift/Win booleans, at most 12 per profile. Older profiles decode with an
empty list; malformed entries are ignored and oversized stored lists are capped at 12.

`RemoteInputBridge` maps Android keys to the existing `KvmInputEncoder.key` packet:
`00 01 00 06 action vk`; `action=0/1` for down/up and `4/3` for extended down/up.
Verified in `public/scripts/agent-desktop-0.0.2.js:506-524`; extended-key handling is at `:411-418`,
and Windows-key handling at `:549-551`. Modifier presses are Ctrl, Alt, Shift, Win, followed by the
main key; releases are in reverse order. Failed input is not replayed; releases are attempted for
every attempted key, including after a partial failure. The relay's existing view-only/input-lock
checks remain authoritative. Ctrl+Alt+Del retains its existing dedicated fixed action (command 10).

### Device history

```json
{"action":"events","nodeid":"node//example","limit":50}
{"action":"powertimeline","nodeid":"node//example"}
```

- `events`: `meshuser.js:1134-1164`, response `{action:'events', nodeid, events:[...], tag}`.
  Node access and `MESHRIGHT_LIMITEVENTS` filtering are performed by the server. The client only retains
  bounded `time`, `action`, `msg` display fields (50 entries; message 1024 characters), never the raw
  event object. ISO dates are parsed as instants; numeric event timestamps are milliseconds.
  Web display of the event timestamp is at `views/default.handlebars:13152`.
- `powertimeline`: `meshuser.js:7149-7184`, visible node required; response
  `{action:'powertimeline', nodeid, timeline:[oldPower,startTimeSeconds,newPower,deltaSeconds,power,...],tag}`.
  The no-records fallback incorrectly uses `Date.now()` milliseconds (`:7181`); the parser normalises
  that first timestamp and still treats subsequent deltas as seconds. Equal previous/current states
  (including `[p, Date.now(), p]`) are not transitions. Invalid shape, negative delta,
  nonnumeric time or overflow fails the section. M5b retains up to 10,000 transitions for the chart;
  only the newest 100 appear in the recent list. Earlier unobserved time stays unknown.
  States follow `views/default.handlebars:6053-6054`: 1 on, 2/3/4 sleep S1/S2/S3, 5 hibernation,
  6/8 off, 0/7 unknown power status.
- Both reads use unique tags and node-id matching through the existing broker; no events from another
  device satisfy the request. Empty arrays, errors and missing replies are distinct. The server can
  omit a reply when both retained power history and current connectivity are absent (`:7178-7182`;
  default power-event retention is 10 days, `db.js:32`). A timeout with a still-current offline
  device becomes **no data**; online/stale-state timeouts and transport/protocol errors remain errors.
  Events and timeline load concurrently without locking the other inspector tabs. No background
  polling or mutation is used by history. Chart rows start at local midnight for today and six
  preceding dates, including 23/25-hour DST days (`views/default.handlebars:9069-9092`).

### Sleep

```json
{"action":"poweraction","nodeids":["node//example"],"actiontype":4}
```

`meshuser.js:3401-3462`: `MESHRIGHT_RESETOFF = 262144` (`:58,3423`); action 4 means sleep
(`:3426`, also the normal desktop action list at `views/default.handlebars:8928`). The client
requires fresh known connectivity, the agent connection bit, a non-Android Mesh Agent and an explicit
Reset/Off grant. It uses the existing confirmation and 60-second connectivity observation path.
`ok` acknowledges the request, not agent execution (`meshuser.js:3455-3460`). Disconnect, reconnect
and unchanged connectivity are reported separately; none proves sleep. The command is never retried.
Existing restart/shutdown codes remain 3/2, and Wake retains its separate `wakedevices` command
(whose single-node reply is a packet-count message, `meshuser.js:3159`). Wake errors and `Used 0
device(s) to send wake packets` are shown verbatim as bounded server responses; a positive count
only starts observation and does not prove wake-up.

### Connectivity snapshots and effective rights

`meshuser.js:801-820` strips database conn/pwr and adds them only when connectivity exists.
Consequently a complete `nodes` entry with no `conn` is known offline (conn=0). This restores
Wake and permits an uninstall observer to report disconnection. A partial `changenode` with no
`conn` still preserves the previously observed connection; losing the socket makes the list stale.

Site-admin status alone does not grant device rights. FULL requires site admin **and**
`serverinfo.manageAllDeviceGroups` (emitted by `meshuser.js:639-640`) or explicit FULL links.
User, mesh and user-group grants are combined, then `userinfo.removeRights` is applied exactly
as `webserver.js:9792-9818,9850-9895` and `views/default.handlebars:21380-21416`: restrictive bits
are added and permissions subtracted. Even an explicit zero mask converts FULL to the server's
base permission set. Absent links resolve to zero. Actions are recalculated on user/server updates.

## M5b native management audit (MeshCentral 1.3.1)

Source: the read-only npm package snapshot supplied with the M5b brief, under
`state/mc131-src/pkg/package` in the coordinator workspace. All references below are to that
snapshot, not an assumed current server version. No production server was contacted.

### Device removal and agent uninstall

```json
{"action":"uninstallagent","nodeids":["node//example"]}
{"action":"removedevices","nodeids":["node//example"],"responseid":"unique-request-id"}
```

- `meshuser.js:3383-3401`: uninstall accepts `nodeids`, requires `MESHRIGHT_UNINSTALL` (32768,
  constant at `:55`, check at `:3390`) and forwards `{action:'uninstallagent'}` only to a connected
  agent. It has no response/responseid handling and does not remove the database entry.
- `meshuser.js:2965-3079`: remove accepts `nodeids`, validates node/domain (`:2992-2995`), requires
  the same right (`:3005`), removes the record and associated inventory/history (`:3011-3053`),
  dispatches `removenode` (`:3060`) and disconnects the agent (`:3068-3070`). It does not uninstall
  the agent software. Per-device `responseid` responses are `ok`, `Denied` or validation errors
  (`:2998,3006,3073`). Neither `ok` nor a disconnected agent proves uninstallation.
- Android exclusion follows the desktop client's agent-id-14 action branch
  (`views/default.handlebars:8918-8947`). This is an explicit mobile-client restriction, not a
  claim that the server enforces it. Unknown agents/rights and stale lists also fail closed.
- Both actions require a review dialog followed by exact current device-name entry. The command
  builder revalidates rights/name immediately before sending. Uninstall also requires known online
  agent connectivity. Remove can apply to an offline computer record.
- Observation re-reads `{action:'nodes',tag,responseid}` with a fresh request id up to six times;
  `meshuser.js:879` returns both ids and the `nodes` map. A missing device is reported only as absent
  from this user's refreshed list (access changes can also cause it); a disconnect is not called an
  uninstall. Read failure leaves the outcome unknown. Mutations are never automatically retried.

### Hardware and last boot

```json
{"action":"getsysinfo","nodeid":"node//example","nodeinfo":true}
```

`meshuser.js:6961-6975` requires a visible node and `MESHRIGHT_DEVICEDETAILS` (1048576, `:60,6966`),
returns stored inventory with `tag`, and reports `noinfo` on failure. `nodeinfo:true` adds the node
record (`:7017-7040`). The app retains only these extra fields:

| Response path | Evidence |
| --- | --- |
| `hardware.identifiers.bios_vendor`, `bios_version`, `bios_date`, `bios_mode` | `meshuser.js:5567-5578`; `views/default.handlebars:13543-13550` |
| `hardware.identifiers.board_name`, `board_vendor`, `board_version` | `meshuser.js:5579-5583`; `views/default.handlebars:13553-13563` |
| `hardware.tpm.SpecVersion`, `ManufacturerId`, `ManufacturerVersion`, `IsActivated`, `IsEnabled`, `IsOwned` | `meshuser.js:5593-5603`; `views/default.handlebars:13573-13582` |
| `node.lastbootuptime` (milliseconds since epoch) | `meshagent.js:2032-2034`; `agents/meshcore.js:2367-2368,2379-2380,2391-2392`; `views/default.handlebars:5176-5177` |

The `nodes` and `changenode` parser also retains last-boot time, so it remains visible without a
system-inventory document (`meshuser.js:827-879`; `views/default.handlebars:3773`). Uptime is an
estimate from that reported time, shown only with current online agent connectivity and a nonfuture
timestamp. Cached times may be stale; no live OS uptime is claimed. Strings are bounded, boolean TPM
fields require actual JSON booleans, and missing/unknown fields remain unknown. Serial numbers,
UUIDs, recovery material and unknown fields are not added to the display model. Raw SMBIOS has no
user WebSocket read action; it is not requested (`meshagent.js:1339-1357`).

### Installed software

```json
{"action":"software","nodeid":"node//example","type":"installedapps"}
```

- `meshuser.js:1002-1019`: `MESHRIGHT_NOSOFTWARE` is a deny bit (8388608, constant `:63`); FULL
  administrator rights override it. Routing also requires RemoteControl 8 or RemoteViewOnly 256
  (`meshuser.js:278-294`). The client checks both, online connectivity and non-Android agent type.
- **Do not send responseid**: `meshuser.js:1003` unconditionally sends `Denied` if it is present,
  then continues processing. The broker omits it for this action.
- `agents/meshcore.js:1773-1802` returns `{action:'software',value:<JSON string>,sessionid:...}` with
  no tag or responseid. `webserver.js:10480-10498` routes to the requesting session, inserts nodeid
  and removes sessionid. `meshagent.js:1302-1306` does not persist software for offline access.
- The value is a JSON-encoded array; supported display fields are `name`, `version`, `publisher`
  (`views/default.handlebars:2828-2846,13934-13990`). The mobile parser omits uninstall commands,
  paths and other fields, bounds source length to 2,000,000 characters and displays at most 1000
  entries. Empty list, unavailable/malformed response and unknown row fields are distinct.
- Requests are serialized per node. After timeout/cancellation, Refresh sends a new request without
  requiring reconnect. Matching is by action and node only; 1.3.1 omits correlation ids, so an old
  untagged response during a retry cannot be distinguished from a new one. The UI shows an observed list,
  never an execution result. No software-install/uninstall command is exposed.

### Native CPU and memory samples

```json
{"action":"msg","type":"cpuinfo","nodeid":"node//example","tag":"unique-request-id"}
```

`agents/meshcore.js:1729-1747` implements this without any plugin and returns
`{action:'msg',type:'cpuinfo',cpu:{total:...},memory:{percentConsumed:...},tag,sessionid,...}`.
The browser consumes `cpu.total` and `memory.percentConsumed` as percentages
(`views/default.handlebars:2878-2883`); the request is at `:13279`. Routing checks 8/256 and inserts
session identity (`meshuser.js:278-294`); replies gain nodeid (`webserver.js:10492-10495`).

The broker binds tag/responseid, node and message type for samples. A correlated
`{action:"msg",result:"Unable to route",tag,responseid}` has no nodeid/type (`meshuser.js:1100`)
and immediately reports the server refusal. Only finite JSON numbers in 0..100 are displayed.
Linux CPU is an average since the previous sysinfo sample, including requests from other sessions
(`agents/modules_meshcore/sysinfo.js:109-146`); the UI explicitly labels that interval.
Samples also have a local receipt time and refresh only on request; missing values are unknown. Support
depends on the connected agent/platform. No claim is made that Mobile Companion is required for
these native samples, and no synthetic load is derived from static RAM capacity or CPU model.

### Quick commands with output

```json
{"action":"runcommands","nodeids":["node//example"],"type":0,"cmds":"echo example","runAsUser":0,"reply":true,"responseid":"unique-request-id"}
```

`meshuser.js:3216-3328` validates the node/domain, requires `MESHRIGHT_REMOTECOMMAND` (131072,
`:3274-3276`), accepts `reply` as boolean and `responseid` as string (`:3279-3280`). `type:0` selects
Windows command shell (1) or Unix shell (3) based on agent id (`:3283-3293`); `runAsUser:0` uses the
agent account. The forwarded request includes the session id (`:3297`). The client requires current
known online computer-agent connectivity, permission and a separate preview/confirmation.

`agents/meshcore.js:1974-2037` combines stdout/stderr and, on process exit, emits
`{action:'msg',type:'runcommands',result:<output string>,sessionid,responseid}` (`:1999,2014,2030`).
Routing adds nodeid and removes sessionid (`webserver.js:10480-10498`). There is **no exit code**.
An agent busy with another command can omit the response (`agents/meshcore.js:1975`).

The broker accepts output only when type, responseid and node match; untyped `runcommands:OK`
acknowledgements never complete the request. Correlated server denials fail it and preserve their
bounded reason; only a timeout is described as potentially still running. Incoming text is decoded
once. Command/console frames longer than 131,072 UTF-16 code units use a bounded scanner before
JSONObject, retaining at most 65536 output characters plus correlation metadata, with a visible
truncation notice. Typed command output never becomes a routing error or `MeshClientState.message`.
OkHttp has already buffered the raw WebSocket message before this decoder: this is a parsing/storage
bound, not a hard network-memory limit. A 20 MiB regression exercises that path without building
another full decoded JSON string. Output is plain text in controller memory, never logged or saved.
Inputs are bounded to 4096 characters. The server logs submitted commands
(`meshuser.js:3305-3309,3316-3320`), and the agent broadcasts stdout/stderr via `sendConsoleText`
without sessionid (`agents/meshcore.js:1994-1995,2009-2010,2025-2026,6844-6847`); the server
routes it to every user with nonzero device rights (`webserver.js:10528-10540`). The confirmation
warns that all users with device access can see output and that commands must not contain secrets.
A 60-second timeout neither cancels
the process nor proves failure; the UI warns to inspect the terminal before manually repeating it.

### Terminal favorites and existing gestures

Favorites are profile metadata (up to 20 named snippets), not a new MeshCentral command. Selecting
one changes a local text field only. A separate insert action sends exactly that single line through
the existing terminal transport, without CR/LF/Enter. Control characters, Unicode line separators,
empty and oversized values are rejected. Secrets must not be entered/saved; snippets do not use
Android saved-instance state. Profile persistence and reload isolation are tested.

Two-finger right click already existed in M5's base: `SessionGesturePolicy.onUp` and the
`RemoteDesktopScreen` multi-touch release path, mapped using `RemoteViewport.toRemote`. It is not
reimplemented. Tests cover direct touch/trackpad, scroll/pinch suppression, PanZoom/Scroll modes and
preventing a duplicate right click after long press. Physical touch acceptance remains separate.

## Compatibility

The client supports native Mesh Agent desktop, terminal and files, plus server-mediated SSH/SFTP.
Intel AMT KVM, Web-RDP, Web-VNC and WebRTC remain unimplemented.

A server that requires a domain URL access key (`?key=`), blocks tool clients with `notools`, or requires
browser SSO may refuse the native sign-in path. The app surfaces the server's message; it does not bypass
these policies. Verify protocol details against MeshCentral 1.2.5 or the upstream source for the version you
run; support for other versions is not claimed.
