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

Failure frames: `{action:'close', cause, msg}` followed by the socket close.

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

## Compatibility

The client targets the native Mesh Agent desktop relay (`usage 2`). Intel AMT KVM, Web-RDP, Web-VNC, WebRTC,
terminal sessions, and file transfer use different paths and are not implemented.

A server that requires a domain URL access key (`?key=`), blocks tool clients with `notools`, or requires
browser SSO may refuse the native sign-in path. The app surfaces the server's message; it does not bypass
these policies. Verify protocol details against MeshCentral 1.2.5 or the upstream source for the version you
run; support for other versions is not claimed.
