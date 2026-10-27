# MeshCentral Wire Protocol Notes

These notes describe the subset of the MeshCentral protocol used by this client. They were read from the public MeshCentral 1.2.5 sources and observed on the wire against a local MeshCentral 1.2.5 test instance with a real Mesh Agent.

Upstream source: <https://github.com/Ylianst/MeshCentral>.

Line numbers below refer to MeshCentral 1.2.5 and can drift between releases. This is not a stable public API: verify the behaviour against the server version you run before relying on a detail. The client reports server refusals instead of guessing when a response does not match.

## 1. Control channel

### Large device lists (audited against MeshCentral 1.3.1)

`meshuser.js:122,661,723-912` advertises `serverinfo.maxdeviceview` and accepts
`{action:"nodes",responseid:"unique-id",skip:25,limit:25,filter:"workstation"}`.
`filter` is a trimmed, case-insensitive **name substring**, truncated to 128 UTF-16 characters;
it is not the richer offline search and there is no separate `search` or online/favorites option.
`skip`, `limit` and `filter` persist on the control socket. Send `filter:""` to clear it and
`skip:0` when changing the query. `limit` is capped by the domain limit. `meshid`/`meshname`
and singular `id` are supported; an array of favorite IDs is not.

Responses contain `nodes` grouped by mesh ID, echoed `responseid`, optional `skip`, `limit`,
`filter`, and `totalcount`. A short first page omits `totalcount`; a short later page reports
`skip + returned count`. A full page can omit the count if the database cannot count:
the client keeps Next available until a short/empty page, without inventing a total.
NeDB filters before sorting by name and applying skip/limit (`db.js:3125-3140`);
literal search escaping is defined at `db.js:4469-4472`. This is offset pagination, not a
transactionally consistent snapshot: simultaneous inserts/deletes can shift page boundaries.

The web UI uses the same state (`views/default.handlebars:2360,2785,6725-6733`).
This client serializes nodes requests, including inspector reads: MeshCentral's asynchronous
DB callbacks read the socket's mutable paging state, so response IDs alone cannot prevent
concurrent requests from producing the wrong offsets. Superseded search results are discarded.
Pages replace the visible page; selected detail devices, open chats and active relay devices are retained outside its membership so
changing pages cannot revoke Android input or invalidate an existing file/terminal session.
Favorites/recents issue sequential singular `id` reads, not a full scan. Online/attention filters
collect an ID-deduplicated snapshot on query changes or explicit refresh, never on a polling clock.
Structural group/permission events also trigger the shared coalesced snapshot refresh; its nodes
read joins the same paging queue. Administration verification uses an isolated singular `id`
read with an explicit zero offset and empty filter, so it cannot verify the wrong page.
A normalized query
equal to the latest requested query never resets the current offset, including screen re-entry.
Repeating an identical in-flight intention also preserves collection progress. Saved UI filter
and text are copied into a fresh client before later screen entries rely on its state.
If a nonempty filter is not echoed (for example MeshCentral 1.3.0), collect all pages and filter
names locally; the final count is the matching local count, not the unfiltered server count.
An empty final page moves back. Untagged snapshots remain accepted only for unpaged legacy
servers. Error replies retain the previous result; retry uses the last requested query.
Cancelling or timing out an inspector read keeps its wire slot occupied until the late response
is drained. A cancelled inspector which never sent a read does not restart an ongoing collection.
A 30-second list timeout keeps the slot and actionable error; a new queued intent rearms its
deadline without clearing the error. Only explicit UI Retry may replace that stuck control
connection. Automatic inspector refresh never reconnects it. A disconnect completes a pending
read with `ControlRequestException("disconnected")`, allowing the observation loop to finish.

Events use `{action:"event",event:{action:"nodeconnect"|"changenode"|"addnode"|"removenode",...}}`.
Connectivity/removal identify `nodeid`; add/change carry `node._id`. Partial changes retain
agent capabilities and connectivity when absent. A single row is inserted at its sorted
position instead of parsing or sorting the complete device list again.
**Server limitation:** `meshuser.js:436,8978-8986` drops events whose node IDs are outside
`visibleDevices`, including new nodes. The last collection page would otherwise leave that
subscription narrowed. Before publishing a collected/ID snapshot, the client sends a serialized
`nodes` read with a fresh absent `id`, `skip:0`, and `filter:""`. A zero-row first page clears
`visibleDevices` (`meshuser.js:889`) even at `maxdeviceview=1`; a known ID does not always do so.
External inspector reads apply their primary reply immediately. Only a domain limit of one needs
another subscription reset while a complete snapshot is displayed; the wire slot stays reserved
until that reset finishes, without holding back the primary reply or replaying it over later events.
At larger limits a single-ID result is already shorter than the limit and clears the subscription.
Delivered events then update collected rows
without periodic full scans. Only an actual server page is reconciled every 15 seconds,
while visible, foreground, connected and idle; a short first page receives global events already.
Delivered visible-device events apply immediately. Metadata-only updates and removals outside
page membership do not reload it; a relevant rename or addition can reconcile its boundaries.
This does not open another control connection. Counts and off-page state are snapshots between
reconciliations, and are not described as globally live.

Server-filtered page membership is authoritative: database collations can match `Café` for `cafe`.
The UI does not apply a second Kotlin substring check. For a server-filtered collected snapshot,
metadata preserves membership. Renamed members and outside names containing the filter after
case/diacritic folding are candidates for `nodes {id,skip:0,filter}` verification against the original
server filter. One foreground worker coalesces at most 16 candidates, waits for 500 ms of quiet,
and spaces reads by 750 ms behind user reads. Query changes discard queued work; later renames
invalidate an active result even if the new name is no longer a candidate. Overflow or read failure
keeps rows/actions current and offers explicit refresh, rather than starting another full scan.
Thus an overflowing burst may leave membership incomplete until refresh. Local fallback searches
and ID selections keep their explicit local matching rules. A stale query or transport generation
cannot send or apply the verification result.

Missing detail/notification/transfer targets are resolved by `nodes {id,skip:0,filter:""}` without
replacing the browser's page or query. Selected devices, chats and relay targets receive addressed
refreshes every 10 seconds while foreground and current when their updates are not covered by a
complete snapshot. On-page retained IDs are skipped when the domain limit exceeds one. At limit
one another addressed read can replace their subscription, so they still need polling.
Power observation uses addressed reads too; it never repeatedly scans the full browser collection.
Because 1.3.1 also returns empty nodes on DB failure, absence needs two consecutive empty addressed
replies at least one second apart, or an explicit removal event. One empty reply preserves the
target and chat; a transport error is not confirmed absence. Removal/addition invalidation tokens
exist only for outstanding reads and are released on every completion path.

Saved card state includes the browser filter, raw search, normalized query and requested offset,
including an offset still in flight. A notification lookup has a 15-second total deadline including
queue wait; timeout opens the missing-target card and consumes the destination. Retrying that card
reads only its ID while the browser snapshot is current.

Android input capability is retained only within a current limited-server snapshot; transport
loss invalidates it. Ordinary full snapshots keep their previous capability-refresh behavior.

Verification: `DeviceListPolicyTest`, `NodesPagerTest`, `DeviceListBenchmarkTest`, `LargeServerUiTest`,
`LargeServerLiveTest`; fixture instructions in `tools/livebench/LARGE_SERVERS.md`.

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

### Login tokens and server access keys (MeshCentral 1.3.1)

The following references are to the **1.3.1 npm package**. A login token is two values, not a bearer
token: **My Account → Login Tokens** calls `createLoginToken` and returns `tokenUser` (starting with
`~t:`) plus `tokenPass` (`meshuser.js:5398-5433`). Send the pair as the first two `x-meshauth` parts,
preserving the generated username's case. Do not use the account username with the token password.
`authenticate` resolves the token to its owner and checks its hash/expiry (`webserver.js:614-653`);
`loginOptions.tokenName` exempts it from second-factor verification (`webserver.js:1094-1095`,
`1118-1119`). `tokenrequired` still means a two-factor challenge, not a request to create a login token.

`showPasswordLogin: false` controls the web page's password form (`webserver.js:3768`), not this native
header path. Token creation can be disabled or restricted by `passwordrequirements.loginTokens`
(`meshuser.js:5402-5403`). Rejected/expired token credentials use the ordinary authentication failure.

The independent `loginKey` domain setting is checked **before** header authentication on `control.ashx`
(`webserver.js:7599-7612`). Without `?key=<value>`, even a valid token gets
`{"action":"close","cause":"noauth","msg":"nokey"}`. Configuration accepts alphanumeric keys of
1–128 characters (`meshcentral.js:1535-1536`). The native client stores this setting separately in
`SecureVault`, builds the control URL with one encoded query value, and offers server settings on
`nokey`. URL metadata remains query-free. Relay endpoints do not share this gate; they retain their
existing authenticated-cookie mechanisms. No browser session or OIDC integration is added here.

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

### Desktop controls, stream settings and PiP (MeshCentral 1.3.1)

The references in this subsection are to the **1.3.1 npm package**, plus the explicitly pinned
[MeshAgent legacy-1.2.6 decoder](https://github.com/Ylianst/MeshAgent/blob/legacy-1.2.6/meshcore/KVM/Windows/kvm.c).
The local bench uses that Linux-agent release; a server version alone does not identify its agents.

| Setting / action | Audited encoding and source |
|---|---|
| JPEG quality, scale, capture interval | `00 05 00 0A 01 quality scale:u16 timer:u16`, big-endian; `public/scripts/agent-desktop-0.0.2.js:190–195` |
| Remote scale | 1024 = full resolution, 512 = 50%, 256 = 25%; `views/default.handlebars:1495–1503`; requests the transmitted image scale, independently of local zoom |
| Frame timer | **Milliseconds**, not fps; web options 20/50/100/400/1000 ms, `views/default.handlebars:1508–1514`; Windows decoder `meshcore/KVM/Windows/kvm.c:538–555` accepts 20–5000 ms and scale 64–4096 |
| Initial / resized screen | Re-send compression settings, `public/scripts/agent-desktop-0.0.2.js:202–212`; UI applies them with `SendCompressionLevel` at `views/default.handlebars:10176` |
| Physical pointer / buttons | Existing ten-byte command 2; `public/scripts/agent-desktop-0.0.2.js:582–626`; no extra mouse or pen protocol |
| Wheel | Existing twelve-byte command 2, delta only; `public/scripts/agent-desktop-0.0.2.js:605–624`, agent `meshcore/KVM/Windows/kvm.c:512–535` |
| Physical keyboard / secure attention | Existing commands 1 and 10; `public/scripts/agent-desktop-0.0.2.js:521,542` |

The native menu offers 1–50 fps and sends `ceil(1000 / fps)` milliseconds. JPEG quality, fps and
remote scale are **requested**, non-secret local preferences isolated by profile and node. The server
desktop multiplexer takes
the maximum quality and scale and the minimum capture interval across viewers
(`meshdesktopmultiplex.js:597–616`). Another viewer can therefore raise effective settings.
The menu labels the requested scale and explains that all three settings are requests; incoming
screen dimensions remain the observed dimensions. No effective JPEG quality or full-frame rate is
reported by the protocol. Reconnecting and changing
screen size keep the chosen settings. Updating a setting also requests a refresh. The counter reports
incoming binary payload bytes/s and **approximate** decoded framebuffer updates/s, including tile/copy
updates: KVM does not expose a full-frame boundary. Idle rates return to zero.

Three fingers scroll remotely. The per-session two-finger choice can retain the original behavior,
scroll the remote window, or pan locally. The pinch switch also applies during pen/palm rejection;
it does not enable rejected finger input. Phone defaults remain local pan/zoom. Mouse motion bypasses
finger gesture recognition and uses absolute framebuffer coordinates even in trackpad mode.

No native horizontal-wheel field was found in this protocol. `AXIS_HSCROLL` is mapped to the
compatible **Shift + vertical wheel** convention, restoring only the temporary modifier the client
pressed. Applications that do not implement that convention cannot scroll horizontally through this
path. Hardware key pairs and repeats are captured before the IME; focus, permission and session loss
release held inputs. Android/DeX may reserve Alt+Tab or Meta before delivery to the app, so the key menu
retains the explicit shortcuts, including Ctrl+Alt+Del.
`Ctrl+Alt+Shift+Esc` releases physical-key capture and opens local session options; the capture chip
re-enables it. Tab/Enter can then operate local controls. Right Alt/Ctrl, numpad Enter/Divide use
extended key actions and right Shift uses VK 161 **only for Windows**, as in
`agent-desktop-0.0.2.js:385,411–430` and `views/default.handlebars:9785`. Other OSes receive generic
Shift/Ctrl/Alt (16/17/18) without extended actions. Mouse-source trackpads using `TOOL_TYPE_FINGER`
share the mouse path only for hover, wheel and held-button streams. Buttonless contacts remain
available to the touch gesture recognizer. Android 14+ one-pointer native touchpad gestures use
`AXIS_GESTURE_SCROLL_X/Y_DISTANCE` (pixel deltas) and `AXIS_GESTURE_PINCH_SCALE_FACTOR` (relative scale),
including historical samples. Native axes are consumed once at the window callback: Compose 1.7
can filter a MOVE with unchanged cursor coordinates or replay it after layout. DOWN is first hit-tested
by Compose so local panels covering the canvas cannot control the covered desktop. Pending finger taps
are cancelled when Android classifies a native gesture after DOWN; see [MotionEvent axes](https://developer.android.com/reference/android/view/MotionEvent#AXIS_GESTURE_PINCH_SCALE_FACTOR).
`AXIS_SCROLL` is a wheel fallback. A wheel detent maps to 360 units, matching the web
normalization (`agent-desktop-0.0.2.js:606`). Hover and the first click do not require window focus;
losing focus still releases owned buttons.

PiP is a local Android presentation mode, not a KVM command. Only the framebuffer is drawn, input is
suppressed at the relay, and owned held keys/buttons are released on entry. The stream continues while
the Activity is visible in PiP (`ON_PAUSE`); `ON_STOP` retains the normal background-pause policy.
The application workspace owns one lock flag, deadline and visible-window registry for the process.
Entering PiP or stopping the last visible window starts the deadline once; another Activity (including
ACTION_SEND) cannot restart it. ON_PAUSE alone does not start it, preserving visible split-screen
sessions on Android 8–10. A main-thread timer checks the deadline while PiP remains visible, and
ON_START/ON_RESUME check it again, including after screen off. Locked PiP shows a text placeholder.
The relay's separate app-lock gate pauses the stream, clears the published framebuffer, discards late
tiles and prevents unpause/input even across reconnect or another window's foreground callbacks.
PIN/biometric unlock is shared by all windows. New processes still start locked. Configuration
recreation remains enabled; the process-owned lock and session survive it.
Automatic PiP is persisted locally and defaults **off**. Only a connected, visible, unlocked desktop
can arm automatic PiP; an open sheet disarms automatic entry. See
[Android's PiP lifecycle guidance](https://developer.android.com/develop/ui/views/picture-in-picture).

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

## Device invitations and administration (MeshCentral 1.3.1)

Audited source: upstream MeshCentral **1.3.1**, `meshuser.js` and `views/default.handlebars`.
These are native control-channel operations, including for offline devices; agent connectivity is
not required. Fresh authenticated lists and known permissions are required by the mobile UI.

- `createInviteLink`: `meshuser.js:4400–4445`; web options/request at
  `views/default.handlebars:5794–5826`, response at `4070–4085`. Send `meshid`, `expire` in **hours**,
  `flags:0` (background and interactive installation), `agents:0` (all available agents), and a
  generated `responseid`. The server validates expire 0–99999 and flags 0–256, and checks the group
  is viewable; it does **not** require Manage Computers here. The mobile client intentionally uses
  a stricter enrollment policy: agent group `mtype:2`, effective **group** Manage Computers (4),
  no user site restriction 4096 (except full administrator), and no LAN-only feature flag 2.
  User-group grants and manage-all-groups administrators are resolved through the existing rights
  resolver, without borrowing direct device grants. UI entry conditions in the web reference are
  at `default.handlebars:5587–5589` and `15295–15297`.
- Only 1, 8, 24 and 168 hours are offered; there is no unlimited link. The success response has
  `action`, `meshid`, `expire`, `url`, `cookie`, `responseid` and `tag`; errors have `result`.
  Validate the correlated action/group/duration and cookie, then construct `agentinvite?c=...`
  with `serverinfo.name`, `serverinfo.port` and `serverinfo.https`, preserving the authenticated
  domain path. Exactly like `default.handlebars:4072–4082`, use the connection hostname only
  when the advertised name has no dot or the LAN-only feature flag is set. The response `url`
  does not override these audited rules. The dialog shows the resulting host and port without
  the cookie, including explicit default ports. Missing advertised fields fall back to the
  configured endpoint for older servers.
  Like the web UI, a configured domain `loginKey` is included for the explicitly shared invitation
  page. Invitations are sensitive; neither URL nor cookie is logged or persisted in saved state.
  Expiry is conservatively measured from request start, displayed as local date/time, and checked
  again when copying/sharing. Closing the dialog discards the link. Sharing is an Android
  `ACTION_SEND` text/plain intent within `ACTION_CHOOSER`; clipboard data is marked sensitive.
  On API 28+, close/disposal and expiration clear the clipboard only if its single text item is
  still this exact invitation; later unrelated clipboard content is preserved. Expired content
  is also checked on return to the foreground, where Android permits clipboard access.
  ZXing Core 3.5.3 (Apache-2.0, no runtime dependencies) produces QR codes locally with level M.
- `changedevice`: `meshuser.js:3512–3666`. Send `nodeid`, `name`, `desc`, `tags` (array) and
  `responseid`. Requires effective node Manage Computers (4). Empty array clears tags; nonempty
  tags are trimmed, deduplicated and sorted, each at most 63 characters. Name and description are
  bounded to 64 characters, tag input to 4096 (`default.handlebars:9462`). These are single-line
  fields and reject ISO controls plus U+202A–202E/U+2066–2069 before trimming. The form retains its
  opening snapshot and sends only changed fields (`default.handlebars:9494–9498`), while checking
  permissions against the current device. Unchanged fields modified by another administrator
  are never reverted. `result:"ok"` acknowledges the operation; errors such as `Access Denied`
  fail it. Read `nodes` once with a fresh correlation id and match only the sent fields before
  reporting success (no five-read polling loop). These reads use a distinct correlation-id prefix
  and update only the verified target; they never replace the full cached list or reset other
  devices' transient session capabilities. Late verification replies are also isolated. Normal
  `changenode` events update the list.
- Notes are a **separate field**, not `desc`: `setNotes` at `meshuser.js:3779–3798` requires
  Set Notes (128); send `id` and `notes`, an empty string removes the note. `getNotes` at
  `4343–4365` requires visibility and returns `{action:"getNotes", id, notes}` where `notes:null`
  means empty. Neither command echoes `responseid`; set has no success reply and emits no node
  change event. Reads are serialized/correlated by `id`, validate the response shape, and repeat
  after writes until the expected text is observed. After a read timeout/cancellation that node's
  untagged read lane refuses further requests until reconnect, so a late reply cannot falsely
  confirm a subsequent save. The dialog offers an explicit Reconnect action and disables further
  saves until recovery. The editor cannot save before the initial read succeeds. The client
  bounds note writes to 16000 characters and preserves the draft on failure. An oversized draft
  shows the length limit and disables Save without showing a permission/reconnection error.
- `changeDeviceMesh`: `meshuser.js:2844–2962`. Send one node in `nodeids`, destination `meshid`
  and `responseid`. Destination needs Manage Computers (4) (`2866`), source **node** and destination
  need Edit Mesh (1) (`2901–2905`), and the source/destination **group** types must match
  (`2895–2897`), independently of a stale or absent `node.mtype`. This differs from
  merely checking Manage Computers on both groups. The confirmation names the destination and
  warns that access follows it. `result:"ok"` is followed by a fresh nodes read. The server event
  at `2951` is `nodemeshchange` with `node`, `oldMeshId`, `newMeshId` and **no `meshid` at event
  level**. Move/group events coalesce through a 200 ms queue: at most one group/node snapshot
  runs and one trailing snapshot waits. Only `meshes` and `nodes` are requested, never repeated
  `serverinfo`/`authcookie`. Disconnect discards pending work. This recomputes names, rights and
  visibility (including a device moved out of accessible groups) without a read storm.

All mutation commands recheck fresh permissions immediately before sending. Dialog confirmation
is separate from editing, cancellation sends nothing, disconnected/stale state disables writes,
and failures/timeouts never produce a success message. Bounded server rejection reasons are shown
through the existing localized error renderer. No automatic mutation retries are made.

The opt-in `DeviceAdminLiveTest` accepts only `https://10.0.2.2:4443/` and a UI-authenticated local
administrator held in SecureVault. It checks the real invitation installation page, edits an offline
synthetic device, verifies notes and a move, then independently restores group, metadata and notes
in `finally`. It is disabled unless the runner receives `deviceAdminLive=true`.

## Agent TCP port forwarding (MeshCentral 1.3.1)

The authenticated control channel supplies `authcookie.cookie` (`meshuser.js:6689–6695`),
bound to the user, domain and client IP. Each accepted local TCP connection opens a distinct
`meshrelay.ashx?auth=…&nodeid=…&tcpport=…&p=14[&tcpaddr=…]` WebSocket under the same base path.
The server generates the pairing ID and agent-side `rauth` cookie and sends the tunnel command
itself (`meshrelay.js:945–989`). No separate control tunnel command, `getcookie`, reconnect or
alternative authorization path is attempted. Cookies/complete URLs never enter stored rules or logs.

`p=14` is the Web-TCP audit label (`meshrelay.js:54`). The server permits either Remote Control
(`0x8`) or Relay (`0x200000`) rights; full administrators also qualify (`meshrelay.js:951–954`).
The protocol-rights filter at `meshrelay.js:73–80` has no dedicated TCP denial bit. The domain's
SSH/SFTP/RDP/VNC UI feature flags are not TCP authorization checks. `tcpaddr` is passed to the
agent without an additional domain filter; omitted means `127.0.0.1` (`meshrelay.js:964`).
The Android UI fails closed while rights/device state are unknown and excludes Android agents
and server-local (`mtype=3`) devices. The server remains authoritative on every connection.

Pairing sends text `c` or recorded-session `cr` (`meshrelay.js:399–417,564–581`). This confirms
the two WebSocket peers, not a successful connection to the TCP target. The agent attempts
`tcpaddr:tcpport`, discards the pairing marker, and pipes binary WebSocket frames as raw
TCP bytes (`agents/meshcore.js:2511–2519,2575–2587`). The viewer sends no terminal usage
number and injects no WebSocket control text into the TCP stream. Recording status is not
interpreted as end-to-end encryption: the MeshCentral server and agent handle plaintext TCP.

Denied permissions or missing nodes may close without a useful reason (`meshrelay.js:948,953–954`).
A 20-second deadline covers pairing only. In 1.3.1 the agent's `net.createConnection` has no
target-error handler (`agents/meshcore.js:2510–2518`): an unreachable target can remain open
after `c` and occupy a connection slot indefinitely. The UI says “Local port open”, explains
this condition, and offers explicit stop. No target-data timeout is invented for legitimate
idle protocols such as SSH. Clients may apply their own timeout.

A refusal or timeout before pairing stops the rule only when it has no other connections.
Existing siblings retain the listener; a certificate/TLS-policy failure remains fatal to the rule.
No failed connection is retried automatically.
After pairing, a transport failure or buffer limit resets only that local TCP connection and
leaves its siblings and listeners open. Failed and cancelled streams use `SO_LINGER=0` (RST),
so a client which reads until EOF receives an error rather than a successful short response.
Normal remote closure (including MeshCentral's bare 1005 close) remains FIN; an explicit
WebSocket error close is a connection failure. Local client disconnects never stop siblings.
Control loss or rights revocation stops affected rules; a fresh sign-in never restarts them.

The alternative cookie-routing path is `meshuser.js:6917–6933` and `meshrelay.js:894–944`;
`getcookie` itself requires Remote Control, not merely Relay. `localrelay.ashx` is different:
the server connects to `node.host`, ignoring `tcpaddr`, and is registered only outside WAN-only
mode (`webserver.js:7657–7665`, `meshrelay.js:1203–1218,1337–1349`). Neither path is used here.

Each rule exclusively binds `127.0.0.1` and available `::1` on the same port, never a wildcard.
IPv4-only hosts (no configured/up IPv6 loopback, or an IPv4-only JVM) retain the IPv4 listener.
An occupied IPv6 port is not an availability fallback: both bindings must then be released.
Android/Linux uses SO_REUSEADDR for restart after TIME_WAIT, not SO_REUSEPORT. Windows JVM
tooling retains exclusive binding because its address-reuse semantics differ.
All required binds must succeed before the rule is published; a failure releases them all. The
port is immediately reusable after stop: the manager waits for any native accept operation to
return before reporting stopped, without waiting for a callback that needs the manager lock. An automatic
port may try up to 16 local port candidates to resolve IPv6 collisions. There are at most
eight active rules and 16 total connections across both families. Outgoing reads pause at
256 KiB of OkHttp queued data with a 5–100 ms exponential polling delay. Incoming callbacks
write directly to the local socket; individual frames over 1 MiB are rejected.

TCP relay Ping runs every 120 seconds, while the control channel retains its 30-second
heartbeat and the same TLS policy governs both. The raw relay write timeout is explicitly
120 seconds; the control client keeps its default. OkHttp otherwise times out WebSocket writes
after ten seconds under backpressure. The longer Ping interval tolerates short reader
backpressure, but cannot keep an idle connection alive behind a proxy with a 60-second idle
timeout. Such a failure is isolated to that connection. Clients can use their own protocol
keepalive; the app never injects application bytes or reconnects failed streams.

After local EOF, the app waits for the outgoing queue, renewing its 120-second stall deadline
whenever the queue shrinks. A progressing upload is not limited to 120 seconds in total.
Expiry or remote EOF with unsent bytes resets the client. An empty queue confirms transport
writes, not acknowledgement by the target application. Normal completion uses WebSocket
close(1000), not cancel(). After queue drain, it allows five seconds of quiet time from the last received frame
(and an initial five-second grace period). An in-progress blocked local write also defers
closure. Replies which continue arriving for more than five seconds are therefore drained.
There is still no upstream FIN frame: protocols which need remote FIN before replying are unsupported.

Rules automatically stop after five minutes with no local TCP connections. The timer starts
at enablement and restarts after the last connection ends. An open connection, even an idle
SSH session or an unreachable paired target, prevents this rule-level timeout. Timers are
cancelled on admission and stop, and never restore a rule after process death.

The user-started Android foreground service uses `connectedDevice` and declares
`FOREGROUND_SERVICE_CONNECTED_DEVICE` plus `CHANGE_NETWORK_STATE`, matching ongoing
communication with external devices over a network. It returns `START_NOT_STICKY`, has no
boot receiver and stops with the last route. Notifications are rebuilt only when active rule
names, ports or connection counts change; bytes and repeated starts do not cause reposts.
Updates are conflated and spaced at least one second apart, retaining the latest trailing count.
Repeated service starts do not reset this limit. Initial foreground publication remains immediate.
Settings lists all saved rules of the current profile even without a server/device list;
inactive rules can be deleted to reclaim slots. PIN/biometric locking protects the UI only:
existing tunnels continue for external clients, with notification stop still available.
The notification stops all routes; the device screen stops only the selected profile/device's
routes. See the [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

External launch links contain only the actual local endpoint. Microsoft RDP uses
`rdp://full%20address=s:127.0.0.1:<port>` as specified by the
[Microsoft Android-compatible URI contract](https://learn.microsoft.com/en-us/windows-server/remote/remote-desktop-services/remote-desktop-uri#legacy-rdp-uri-scheme).
VNC and SSH handlers use `vnc://` and `ssh://`; absent handlers produce a local hint.

## Manual terminal reconnect and computer file batches (MeshCentral 1.3.1)

A reconnect constructs a **new** Agent p1 or SSH p11 relay from the current control session after checking the same profile/account/device and its current rights. The terminal renderer is retained. An ordered local marker resets parser/modes without clearing scrollback, and restores the viewport. Old collectors/timers are cancelled; generation checks reject late output and WebView input. No command or submitted credential is stored for reconnect. SSH follows its normal authentication exchange again; input stays disabled until shell output proves readiness.

Before accepting fresh output, the old screen tail and boundary marker are scrolled into history. Grid resizing preserves a reader's viewport. After reconnect the local xterm parser consumes ED 3 (`ESC[3J`) only until the first accepted input, protecting history from shell startup. After input, explicit `clear` can erase history again; ED 0/1/2 retain their normal screen semantics. Native tests cover both sides of that boundary.

The archive protocol was checked against upstream **1.3.1**:

- `agents/meshcore.js:4309-4336`: p5 request `{action:"zip", path, files:[name], output}`. The Agent creates `path/output` using `zip-writer`, with `path` as the base path. It silently ignores an empty/missing source set and concurrent ZIP requests.
- `agents/meshcore.js:4320-4335`: binary UTF-8 JSON replies `dialogmessage/zipping`, optional `dialogmessage/zippingFile`, then `dialogmessage` with explicit JSON `msg:null` and `refresh` when the output stream closes. These messages carry no request ID. The controller allows one archive operation and requires start, explicit close, then refresh; missing `msg` is not completion.
- `agents/meshcore.js:4356-4359`: `{action:"cancel"}` cancels ZIP work. Cleanup can race the final stream-close callback; loss of the relay is not proof of cleanup.
- `views/default.handlebars:12649-12660`: the web UI sends the same `zip/path/files/output` fields and adds `.zip` to the output name.
- `apprelays.js:2092-2108`: SFTP `rm` starts asynchronous `unlink` and optionally `rmdir`, without an acknowledgement. This relay has no ZIP action. SFTP folders are enumerated recursively with `ls` and downloaded using the existing exact-size/stop/postflight exchange. Nonempty SFTP directory deletion remains server-dependent.

The client uses a UUID temporary archive name and checks it is absent before creation. A fresh listing must confirm that exact actionable regular file of at least 22 bytes. Downloads check size, reread the owned SAF document to compare SHA-256 with received bytes, then re-list the remote parent. ZIP validation checks EOCD/ZIP64, central/local header consistency and bounds, with at most 65,535 entries and 32 MiB of metadata. It does not decompress members or verify their CRC. A missing start signal after five seconds triggers cancel and a fresh-listing absence fence before recursive fallback. The UI warns that ZIP creation needs space and write access on the remote disk; the protocol provides no trustworthy disk-space preflight.

Cancellation waits up to five seconds for archive close/refresh before relay teardown, then checks and removes the exact owned temporary name when possible. A leftover produces an exact-path in-app notice and, if permitted, a private notification. Cleanup failure after a verified download is a success with warning, not a reason to download again. No remote checksum API or ZIP capability flag is invented.

Multi-delete separates files (`rec:false`) and directories (`rec:true`). Immediately before each group, a fresh listing must match each confirmed name, type and file size; a mismatch aborts the group. Fresh listings then verify absence, with at most ten metadata polls for asynchronous SFTP completion and no repeated `rm`. This bounds the confirmed operation but is not an atomic server-side compare-and-delete. Windows aliases including all-dot names, trailing dots/spaces, device names and alternate stream syntax remain visible without actions.

Batch reservations prevent overlap. SAF selection, pending work and asynchronous closing cleanup keep the foreground service active. Queues lease persistable URI permissions, preserve pre-existing grants and release only acquired bits on replacement/close. Queue metadata remains process-local. Single files use CreateDocument; groups use a tree. A refused file keeps the relay usable; a dead or unsynchronised relay pauses remaining rows before creating documents. Retry is explicit and can cover all unfinished items. Upload retry retains the original content URI and confirmed target; its 31-second cancellation fallback stops only relay readers, preserving the worker for reconnect.

`apprelays.js:2047,2051-2052` closes SFTP on readdir errors and maps every nondirectory entry to `t:3`, exposing size but discarding `longname`/mode. The client cannot reliably identify links/FIFOs. It attempts these entries as files, checks exact size, reports refusal/mismatch with the path, and pauses on a stalled open. A link target matching the advertised size is indistinguishable from a regular file. Recursive folders remain `name.partial` until every descendant succeeds; completed contents are retained on failure. Progress publication is capped at 10 Hz with exact final bytes; queue rows are lazy.

Native tests cover queue selection/retry/cancel, SAF ownership, recursive SFTP, ZIP reply ordering/cleanup, deletion confirmation and terminal history/viewport/credentials. Opt-in `ToolsPlusLiveTest` uses a dedicated emulator's prior UI login and only `https://10.0.2.2:4443/`; it checks a synthetic 30-file ZIP by independent SHA-256 and deletes two owned test files. `ToolsPlusLiveTerminalTest` disables and restores only that emulator's Wi-Fi/mobile data and checks the remote command counter after manual reconnect. These tests do not establish physical-device or general server-version acceptance.

`SftpLiveAcceptanceTest` is separately opt-in (`-e sftpLive true`). It uses real OpenSSH through an isolated MeshCentral 1.3.1 relay on `https://10.0.2.2:4467/`, the real Files UI and Android's SAF picker. Its local metadata bridge on port 19867 provides synthetic fixture metadata, filesystem SHA-256 manifests and bounded fixture mutations; credentials are seeded separately into the debug app's private cache via stdin and must be removed before teardown. `tools/livebench/sftp_fixture.py` creates 200 ordinary files (including eight empty files and a 24 MiB file), Unicode/spaced names, four links, permission failures and a FIFO. Source hashes are computed independently of the Android download path. Whole-folder failures retain `.partial`; explicit retry checks all regular files. Additional cases cover per-file recovery, denied-directory disconnect, FIFO deadline, cancellation and confirmed deletion after a file-to-directory replacement.

The real OpenSSH check confirms the upstream boundary above: `apprelays.js:2047` calls `obj.close()` on a failed `readdir`, while `:2051-2052` collapses all nondirectory types to `t:3`. An independent relay-only probe reproduced this before Android acceptance. A recoverable `download/cancel` now reports a file refusal without falsely saying the session ended; size mismatch and timeout retain their separate reasons. No automatic SSH credential replay or server modification is introduced.

## Compatibility summary

The client supports native Mesh Agent desktop, terminal and files, plus server-mediated SSH/SFTP.
Intel AMT KVM, Web-RDP, Web-VNC and WebRTC remain unimplemented.

A domain access key (`loginKey`) is supported as described in the 1.3.1 authentication section above.
A server that blocks tool clients with `notools` or requires browser SSO can still refuse the native
sign-in path. The app surfaces that refusal; it does not bypass these policies. Verify each protocol
detail against the upstream source for the server version you run; a general support matrix is not claimed.

## Native user-to-computer chat (MeshCentral 1.3.1)

Sources below refer to the upstream 1.3.1 package, not approximate APIs.

- `views/default.handlebars:8644–8653` opens the administrator messenger and sends
  `{"action":"meshmessenger","nodeid":"node//example"}` on `control.ashx`.
  `meshuser.js:2198–2214` requires `MESHRIGHT_CHATNOTIFY` (16384) and routes `openUrl`
  with `/messenger?id=meshmessenger/<nodeid>/<userid>` to the agent. In addition,
  `meshuser.js:277–293` requires Remote Control (8) or Remote View (256) for routing.
  LAN-only mode rejects this action (`meshuser.js:2194`).
- `agents/meshcore.js:2079–2084` opens the user's browser and returns success/failure.
  This client does not interpret successful control-channel send as proof that the
  user opened a browser. Only the paired-relay handshake confirms another participant.
  Official Android agent id 14 is hidden: its console link operation is a separate
  protocol, and support for this computer-agent `openUrl` path is not established.
- `webserver.js:4017–4067` validates the messenger ID shape (seven slash-delimited
  segments). `views/messenger.handlebars:756–775` opens
  `meshrelay.ashx?id=meshmessenger/node/<domain>/<node>/user/<domain>/<user>&auth=<cookie>`.
  The administrator uses **`authcookie.cookie`**, containing `userid`, `domainid` and
  IP (`meshuser.js:6689–6695`); `rcookie`/`rauth` belongs to the agent side of other
  tunnels and does not authenticate the administrator here. The common client refreshes
  this cookie during the signed-in control session. It is never logged or persisted.
- `webserver.js:9412–9427` accepts that encrypted query cookie for WebSocket authentication,
  validates domain and optional IP binding and applies a 60-minute age limit. Existing
  session cookies are also supported by the server. The native chat uses the same
  immutable TLS policy, pinned server key and non-redirecting OkHttp socket factory as
  the other relays; it never adds `x-meshauth` or remembered-device credentials to chat.
- `meshrelay.js:338–377` pairs by ID and requires at least one authenticated side.
  Node-to-user pairing has an upstream TODO for verifying the exact authenticated
  user (`:346–348`); the app therefore derives IDs only from its current authenticated
  user and currently authorized device. Chat is server-relayed, not end-to-end encrypted.
- Raw text `c` means two relay sockets paired; `cr` also indicates server recording
  (`views/messenger.handlebars:763–775`). The screen displays recording explicitly.
  Text is `{"action":"chat","msg":"raw Unicode text"}` (`:254–264`, `:432`).
  Quotes, newlines and HTML-looking content are JSON-escaped on the wire and rendered
  as plain selectable text. There are no actionable links or HTML interpretation.
- `{"ctrlChannel":"102938","type":"ping"}` receives a matching `pong` (`:421–428`).
  A valid `random` frame confirms the browser without starting WebRTC. Typing, WebRTC negotiation,
  image, push-status and unknown frames are ignored. File offers (`file`/`fileUploadStart`) receive `fileUploadCancel` with the same numeric
  id, provided it is finite and in [0, 1), matching `messenger.handlebars:453–489`. `action:close` is treated as a refusal. No WebRTC negotiation is sent, so
  browser text remains on the WebSocket rather than switching transports.
- No message acknowledgement or delivery ID exists. A successful send means OkHttp
  accepted the text, not that the remote user read it. Messages are never replayed.
  Sending before browser confirmation is disabled (`messenger.handlebars:391` is the web equivalent).
- There is no distinct goodbye frame. Socket close after pairing means the peer left
  **or** the connection ended; failure is reported separately without asserting why the
  person disappeared. `meshrelay.js:656` gives an unpaired relay a 15-second timeout
  outside Firebase mode. An unpaired first invitation ends in an explicit “user did not open”
  state. A paired transport loss first reconnects silently; an already-open browser itself
  retries after 500 ms (`messenger.handlebars:371–375`). A manual window request probes the
  relay for 2.5 seconds before inviting; `c`/`cr` suspends that pending invitation. Requests are
  limited to one per five seconds. An initial/manual invitation that has not yet been sent
  survives transport failure during connection/probing, and is sent at most once; automatic
  recovery after an established conversation never opens another browser window. Recovery
  has a 20-minute retained-pair deadline and at most 120 seconds waiting for fresh control credentials.
  `c`/`cr` only proves two sockets were paired: the server can pair two authenticated sockets
  (`meshrelay.js:366–412`). The app enters Verifying and requires browser `random`
  (`messenger.handlebars:772`) or a first text/file frame within 3 seconds. Neither ping nor pong
  proves a browser: the server can emit both itself (`meshrelay.js:325–330,433–434`), and the
  browser's pong has no challenge identifier. This deliberately uses the first-browser-frame
  confirmation path, preventing server keepalives from confirming a ghost pair.
  Missing proof or closure within 3 seconds of opening without `c` enters ServerRetained:
  MeshCentral rejects a third socket (`meshrelay.js:622–624`) while the old TCP pair may live for
  240 seconds plus keepalive probes (`meshrelay.js:335`). Retries run every 5 seconds, with the
  same 20-minute budget, no extra window and sending disabled; deadline exhaustion becomes Failed.
  Every unpaired socket also has a local 20-second deadline, including Firebase servers without
  a server timeout (`meshrelay.js:635–652`). Normal unpaired expiry becomes NotOpened/PeerLeft.
  Five consecutive transport/proxy failures stop ordinary recovery; browser confirmation resets
  that count. Duplicate-close recovery uses its separate 20-minute budget, allowing the Linux TCP
  retransmission window to expire. A manual request reuses only an unpaired socket younger than
  five seconds; an older socket is replaced before probing/inviting, so its old deadline cannot
  cancel the new request. No messages are replayed or queued for offline/push delivery; callbacks
  are attempt-fenced.

The client retains at most 200 messages and a draft in memory for the session, with
local send/receipt timestamps. Outbound text is a single line, limited to 4096 UTF-16 code
units; IME Send and Enter send it. Longer incoming text is displayed as a bounded prefix
with an explicit truncation marker, without splitting a surrogate pair. The streaming JSON
scanner bounds decoded text/metadata and nesting, including escaped strings and huge unknown
fields. Parsing runs on the WebSocket reader, not Main. OkHttp has already buffered the raw
message: this bounds additional parsing/history memory, not the transport's frame buffer.

Agent connectivity is required only to send an invitation. The browser relay remains usable
when the agent goes offline. A temporary control disconnect or network change preserves the
conversation, draft and unread count. A replacement relay waits for a fresh authcookie from
the restored control connection. The default-network tracker ignores a late onLost for the
previous network; a network handoff refreshes control authentication and its IP-bound cookie
only while a chat is active. Passive chat history does not tear down other tools. Every network
handoff marks the chat cookie stale, including late cookie replies on the old control connection;
the next explicit chat request refreshes control before a new relay can use the replacement cookie.
Fresh snapshots recheck grants/deletion, even when the agent is offline. Logout, identity/
server/key changes, rejected authentication, revoked rights or explicit closure still close
the relay and erase the conversation. Back only navigates; closing with history/draft requires
confirmation. Neither process restart nor a notification automatically creates a conversation.

Unread counts increase whenever the chat screen is not visible, including another foreground
tool. They appear in the session list, the main session action, and over the desktop. Opening
the resumed chat clears the count. Generic private notifications use the existing channel,
`app_name` and `setOnlyAlertOnce(true)`; updates are coalesced to at most one per second per
conversation with a trailing latest count. Reading/closing cannot resurrect a pending notice.
Chat text is never included or written to the event feed.
An immutable intent identifies the existing profile/session and a local delivery sequence
prevents replay on Activity rotation while accepting a new notification after restoration.

An explicit foreground chat action starts a non-exported, non-sticky `ChatService`. It declares
`remoteMessaging` and `FOREGROUND_SERVICE_REMOTE_MESSAGING` and passes the corresponding service
type on API 34+. A low-importance ongoing notification keeps the waiting, connected or recovering
conversation visible to Android; a StateFlow of active conversations stops the service when
none remain, without polling. Recovery does not reset the transcript scroll position. Outside Connected, the status and retry
action stay pinned outside the transcript, including a long conversation scrolled to its end.
With a landscape keyboard they share the composer row: status labels the draft field and retry
replaces the disabled send action, preserving room for the draft, Back and Close. It
never restarts a conversation after process death. This follows the Android
[remote messaging foreground-service contract](https://developer.android.com/develop/background-work/services/fgs/service-types#remote-messaging).
Background message notifications still require Android notification permission; process death,
force-stop or device network/power policies are not durable-delivery guarantees.

Not supported: user-to-user administration chat, official Android agent chat, files,
audio/video, WebRTC, images, typing indicators, exports and Firebase push chat. Server-side
logging/recording follows MeshCentral configuration independently of local memory-only history.

## Native processes and services (MeshCentral 1.3.1)

The existing authenticated, TLS-pinned `control.ashx` socket carries `{action:"msg", type, nodeid}`. Types `ps` and `services` read lists; `psinfo` adds numeric `pid`. Replies have `action:"msg"`, the same type, `nodeid`, and `value` as JSON text for lists (meshcore 1.3.1); `psinfo` returns an object. The parser also accepts already-decoded objects/arrays. `psinfo` also returns `pid`. The agent does not echo `tag`/`responseid`; routing acknowledgements do not contain results. `TaskToolsBroker` isolates pending reads by node/type (plus PID for details). Ordinary cancellation retains an abandoned slot for up to 15 seconds to consume its late reply, preventing another request of that type from being sent first. Once consumed, fresh reads and mutations resume without reconnecting; pending-slot errors are retried by the visible screen. A real request timeout or an expired abandoned slot releases the slot but makes subsequent replies display-only until the agent goes offline or the control connection changes. A possibly delayed old reply never verifies a new request.

`ps` is an object keyed by PID, each entry containing `cmd` and optional `user`/`path` (the live Linux agent also includes `pid`). `services` is an array or numeric-keyed object. Windows entries contain `name`, `displayName`, optional `status.state`/`status.pid` and `startType`. Live Linux entries contain `name`, `serviceType`, `escname`, `conf`, `description` and sometimes top-level `state`. Missing fields stay unknown; malformed lists are errors, never empty verification snapshots. A Linux `ps` header is ignored only when its key/pid are `PID` and user is `USER`, and at least one real process remains. Identical duplicate Linux service rows are collapsed; conflicting duplicates fail closed.

`pskill` sends `value:"<pid>|<name>"`; service mutations use `serviceStart`, `serviceStop`, `serviceRestart` and exact `serviceName`. There is no success acknowledgement. The client confirms selection, checks current permissions/connectivity again, reads a fresh preflight list, dispatches once, then polls within a 10-second (process) or 15-second (service) window. The deadline is checked between polls: an in-flight read finishes or reaches its own 15-second timeout, so total verification can exceed the window by one read. Process success requires a nonempty snapshot retaining a strict majority of pre-command identities and absence of the previous PID. Only Windows may also infer termination from a changed name at the same PID; on Linux/macOS/unknown platforms that change remains unverified. Service start/stop requires explicit state or the guarded systemd observation described below; restart requires a stopped-to-running observation or a changed nonzero service PID. Missing/late/malformed data never proves completion. No automatic retry of mutations. A confirmation dialog pauses periodic refresh. A confirmed action waits for an already-active read; cancellation before dispatch never says that a command was sent.

Server `meshuser.js` allows routing with REMOTECONTROL (8) or REMOTEVIEWONLY (256); `agents/meshcore.js` does not enforce mutation rights here. The client therefore requires known current rights: either bit permits reading; mutations require bit 8 with all of 256, 4096 and 65536 absent, or FULL (-1). As in `default.handlebars:9605`, agent 14 remains read-only even after a successful list probe. The server input-disable flag `features2 & 0x2000` forbids mutations even for FULL; absent `features2` is zero, matching the web client's JavaScript bitwise test. `MeshRights.allowsUnrestrictedInput` is shared with the existing phone-input check. Null rights fail closed. PIN locking removes the screen; lifecycle pause stops polling and cancels work. Error states hide the periodic-refresh claim; uncertain data is identified by its error, not mislabeled as missing mutation rights.

Opening a service's Details sends `service` with `serviceName`. The agent also falls through to a `services` reply. The broker reserves both slots and consumes both replies in either order. If either part times out, its slot is released but future replies of that type become display-only until reconnection, so an unconsumed fallthrough cannot verify a later action. Details must identify the requested service. Startup type is retained for the screen session when enumeration omits it and refreshed on opening Details again. Missing fields stay unknown.

Sources: MeshCentral 1.3.1 `meshuser.js:277-299,1021-1104`, `agents/meshcore.js:1498-1625`, and `views/default.handlebars` task-tools functions. Sanitized live Linux and synthetic Windows fixtures are in `app/src/test/resources/task-tools/`.

The distributed Linux agent's embedded `service-manager` builds a table with `systemctl --type=service --state=running`. Its JSON error path returns an empty table, while enumeration still returns unit files. Thus absence of `RUNNING` is not unconditional proof of stopping. The client infers a transition only for a previously running systemd unit still present in a majority-preserving list, with a majority of the other running units surviving as witnesses. A completely empty running table remains unknown. The corresponding upstream module is [service-manager.js](https://github.com/Ylianst/MeshAgent/blob/master/modules/service-manager.js); review evidence also records the exact embedded module extracted from the tested binary.
