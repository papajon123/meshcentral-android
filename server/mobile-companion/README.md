# Mobile Companion (optional MeshCentral server plugin)

Optional server-side jobs, events, notification tokens, and a read-only HTTP
events endpoint for the MeshCentral Android companion. This package is
installed by the server administrator after review and backup. It does not
patch MeshCentral core, does not accept arbitrary code or shell strings, and
does not expose job creation or mutation over HTTP.

- Plugin id / directory / short name: `mobilecompanion`
- Plugin format: `exports.mobilecompanion = function (pluginHandler) { ... }`
- Audited against MeshCentral 1.3.0 sources (`pluginHandler.js`, `meshuser.js`,
  `meshagent.js`, `webserver.js`, `db.js`, `meshcentral.js`,
  `agents/meshcore.js`)
- Node.js: built-ins only, no npm dependencies
- Version: 1.1.0

## Reviewed installation

1. Copy this directory to the MeshCentral data directory:

   ```text
   <meshcentral-data>/plugins/mobilecompanion/
   ```

   The directory name must equal `shortName`. The loader requires
   `<meshcentral-data>/plugins/mobilecompanion/mobilecompanion.js` and then
   reads `config.json`.

2. Enable the local-development plugin list in the MeshCentral
   `config.json`. `pluginHandler.js` reads `settings.plugins.list` directly and
   loads these names without a database plugin record:

   ```json
   {
     "settings": {
       "plugins": {
         "enabled": true,
         "list": ["mobilecompanion"]
       }
     }
   }
   ```

3. Restart MeshCentral. The plugin implements
   `hook_setupHttpHandlers`. MeshCentral 1.3.0 calls that hook once at the start
   of `setupHTTPHandlers()`, before the core per-domain routes are registered,
   so the plugin registers `domain.url + "mobilecompanion/events"` for every
   configured domain. There is no admin panel. Check the server log for
   `Error loading plugin: mobilecompanion`; absence of that line is the
   installation signal.

4. Run the package tests from this directory:

   ```powershell
   node --test --test-isolation=none test/all.test.js
   ```

   `--test-isolation=none` is required on restricted Windows sandboxes where
   the Node test runner may not spawn child processes. On an unrestricted
   machine the same tests can be run by name with `node --test`.

Start on an isolated stand. Before a production installation, back up the data, preserve certificates and agent identities, and verify authenticated control, capability discovery and token revocation. Roll back the configuration if verification fails.

## Protocol v1 websocket operations

All websocket messages are MeshCentral plugin messages. The HTTP endpoint is
described separately below and is read-only.

Request:

```json
{
  "action": "plugin",
  "plugin": "mobilecompanion",
  "op": "startJob",
  "requestId": "8b6b0d0e-3f2a-4c57-a1b2-9d0e0f1a2b3c",
  "userid": "user/<domain>/<name>",
  "nodeid": "node/<domain>/<nodeid>",
  "templateId": "diagnostics",
  "parameters": {},
  "operationId": "0f1e2d3c-4b5a-4c6d-8e9f-001122334455",
  "createdAt": 1780000000000,
  "confirmed": false
}
```

`userid` is stamped by MeshCentral core in `meshuser.js` (`case 'plugin'`).
The plugin does not trust any domain/user value from the request payload.

Response:

```json
{
  "action": "plugin",
  "plugin": "mobilecompanion",
  "op": "startJob",
  "requestId": "8b6b0d0e-3f2a-4c57-a1b2-9d0e0f1a2b3c",
  "ok": true,
  "data": { "job": { } }
}
```

Error:

```json
{
  "action": "plugin",
  "plugin": "mobilecompanion",
  "op": "startJob",
  "requestId": "8b6b0d0e-3f2a-4c57-a1b2-9d0e0f1a2b3c",
  "ok": false,
  "error": { "code": "FORBIDDEN", "message": "access denied" }
}
```

- `requestId` must be a string of 1..128 printable characters. Invalid values
  get `INVALID_REQUEST_ID`; the response echoes the first 128 characters or an
  empty string if it was not a string.
- `op` must be one of `capabilities`, `startJob`, `getJob`, `listJobs`,
  `cancelJob`, `deleteJob`, `events`, `registerNotifications`,
  `revokeNotifications`. Anything else gets `UNKNOWN_OP`.
- A response is sent with `client.send(object)`. There is no other delivery
  API. Closed websockets are ignored; the job continues on the server.

### Websocket authentication and authorization

Only an authenticated **user** websocket is accepted:

- `client.user` and `client.domain` must exist, and `client.ws` must exist.
- `command.userid === client.user._id` is mandatory.
- The domain id comes only from `client.domain.id` (the default domain id may
  be the empty string).
- `client.user.domain`, when present, must equal `client.domain.id`.
- Agent plugin contexts have no `client.user`/`client.domain`; they are
  rejected. The fake-agent-context test covers this, including
  `registerNotifications`.
- Every device read goes through
  `webserver.GetNodeWithRights(domain, user, nodeid, callback)`. `visible` must
  be exactly `true`.
- Jobs require `MESHRIGHT_REMOTECOMMAND (0x20000)`. This matches the shell/PowerShell
  check in MeshCentral 1.3.0 `meshuser.js` for `runcommands` types 2/3.
  Agent-console rights (24, for type 4) do not authorize these jobs.
- Job lookup checks owner, domain, and current device rights. A guessed job id
  from another user/domain or a device that is no longer visible returns
  `JOB_NOT_FOUND` (not `FORBIDDEN`, to avoid existence leaks).
- Access is re-checked immediately before dispatch, including jobs queued while
  the agent was offline. Revoked rights fail the job instead of sending it.

### `capabilities`

No fields. Returns protocol metadata, the operation allowlist, template list,
platform list, event kinds, notification-token limits, the HTTP endpoint
pattern, rate limits, and the honest server-side alert thresholds.

The notification section is:

```json
{
  "scope": "events-read-only",
  "method": "GET",
  "path": "mobilecompanion/events",
  "endpoint": "domain.url + \"mobilecompanion/events\"",
  "endpointExamples": ["/mobilecompanion/events", "/d1/mobilecompanion/events"],
  "auth": "Authorization: Bearer",
  "tokenTtlDays": 30,
  "maxTokensPerUser": 5,
  "maxTokensGlobal": 200,
  "ratePerMinute": 20,
  "maxInflight": 20,
  "sinceMax": 9007199254740991,
  "limitMax": 100,
  "push": false,
  "serverThresholds": {
    "source": "diagnostics",
    "diskUsedPercent": 90,
    "memoryUsedPercent": 95,
    "dedupeSeconds": 3600
  }
}
```

The same server thresholds are also reported at the top level:

```json
{
  "alerts": {
    "source": "diagnostics",
    "diskUsedPercent": 90,
    "memoryUsedPercent": 95,
    "dedupeSeconds": 3600,
    "push": false
  }
}
```

`push` is `false` and stays `false`: this plugin does not provide instant push.
Android may add client-side filters later; the thresholds above are the server
thresholds that actually create `device-alert` events.

### `startJob`

Request fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `nodeid` | string | `node/<current-domain>/<node>`; domain must match the session domain |
| `templateId` | string | allowlisted template (see below) |
| `parameters` | object | `{}` for read-only templates; `{ "name": "..." }` for service mutations |
| `operationId` | UUID | idempotency key scoped to `(user, domain)` |
| `createdAt` | integer | UTC milliseconds; new operations may be at most 5 minutes old, plus 60 seconds clock skew |
| `confirmed` | boolean | must be `true` for mutations |

Semantics:

- The job is persisted **before** any dispatch. The response returns the
  persisted public job.
- A repeated `(owner, domain, operationId)` returns the same job and does not
  execute again. Reusing the id with a different node/template/parameters gives
  `OPERATION_CONFLICT`.
- A new operation whose `createdAt` is too old or too far in the future gives
  `STALE_OPERATION`; this also prevents a repeat after retention from starting
  the command again.
- Only one `queued`/`running` job per node is allowed (`NODE_BUSY`). This avoids
  the agent's single `cmdchild` silently dropping a second command.
- Mutations require an online, fully authenticated agent at creation time and
  are never accumulated while offline (`AGENT_OFFLINE`).
- Read-only jobs may wait for an agent for 5 minutes (`queued`); they expire
  with `AGENT_WAIT_TIMEOUT` if it does not appear.
- Job capacity is global for this plugin instance: 40 jobs. Full capacity
  returns `QUOTA_EXCEEDED`; active jobs are never evicted.

### `getJob`, `listJobs`, `cancelJob`

- `getJob`: `{ "jobId": "mc_..." }` → `{ "job": { ... } }`.
  Owner/domain/current rights checked.
- `listJobs`: optional `{ "nodeid": "node/<domain>/<node>", "limit": 20 }`.
  `limit` is 1..40, default 20. Returns `{ "jobs": [ ... ] }`, newest
  `createdAt` first. Inaccessible devices are skipped. With an explicit
  `nodeid`, insufficient rights return `FORBIDDEN`.
- `cancelJob`: `{ "jobId": "mc_..." }`.
  - `queued`: cancelled for real, no dispatch happens, status becomes
    `cancelled`.
  - `running`: `CANCEL_UNSUPPORTED`. MeshCentral `runcommands` has no cancel
    primitive; the plugin never claims the remote process was killed.
  - terminal: idempotent no-op returning the stored job.

### `deleteJob`

Request `{ "jobId": "mc_..." }`.

- Only the owner in the same domain with current device rights can delete.
  Foreign/unknown/invisible jobs return `JOB_NOT_FOUND` to avoid existence
  leaks.
- Only terminal jobs (`completed`, `failed`, `expired`, `cancelled`,
  `unknown`) can be deleted. `queued`/`running` return `JOB_ACTIVE`; the job is
  left untouched.
- Deleting removes the job and the linked job events (`job-completed`,
  `job-failed`) from the same bounded state document. Unrelated device events
  are not removed.
- A deleted `(owner, domain, operationId)` no longer deduplicates a later
  `startJob`; deletion is an explicit owner action.
- Response: `{ "deleted": true, "jobId": "mc_..." }`.

### `events` (websocket)

Request `{ "since": 0, "limit": 50 }`.

- `since` is the last consumed global sequence (integer ≥ 0, default 0).
- `limit` is 1..100, default 50.
- Response:

  ```json
  {
    "items": [
      {
        "id": "mce_12",
        "sequence": 12,
        "kind": "job-completed",
        "nodeid": "node/<domain>/<node>",
        "jobId": "mc_...",
        "time": 1780000000000,
        "expiresAt": 1780604800000
      }
    ],
    "cursor": 18,
    "resetCursor": false
  }
  ```

- `cursor` is the highest global sequence the server evaluated for this caller.
  If the caller's `since` is ahead of the server (state reset), the response is
  `{ items: [], cursor: <current seq>, resetCursor: true }`.
- The caller receives only its own job events and device events for devices it
  can still see. Rights are checked fresh on every `events` request.
- No raw `msg`, `msgArgs`, cookies, session ids, command lines, or environment
  values are stored or returned.
- History is bounded to 7 days and 500 events. Duplicate device state changes
  and duplicate help requests within 60 seconds are suppressed.
- `device-alert` items additionally carry only `metric` (`disk` or `memory`)
  and `direction` (`high`); no raw percentage or command output is included.

### `registerNotifications`

Request: no fields. Only an authenticated user websocket is accepted; the same
`client.user`/`client.domain`/`command.userid` checks apply. A fake agent
context cannot register a token.

Response (the token is returned **exactly once**):

```json
{
  "token": "<43-character base64url>",
  "tokenId": "mcn_<16 hex>",
  "expiresAt": 1782592000000,
  "endpoint": "/d1/mobilecompanion/events"
}
```

- The token is `crypto.randomBytes(32)` encoded as base64url.
- TTL is 30 days.
- Maximum 5 live tokens per `(owner, domain)` and 200 globally. Expired tokens
  are pruned before the quota check. Full quota returns
  `TOKEN_QUOTA_EXCEEDED`; tokens are never silently evicted.
- The endpoint is an absolute path on the current origin:
  `domain.url + "mobilecompanion/events"`. The default domain (`domain.url="/"`)
  gets `/mobilecompanion/events`; domain `d1` (`domain.url="/d1/"`) gets
  `/d1/mobilecompanion/events`. A DNS-based domain also uses
  `/mobilecompanion/events`; the token is still bound to the domain id.
- The raw token is not written to the database, state, logs, or URL. The app
  must store it in Android Keystore-backed storage.

### `revokeNotifications`

Request `{ "tokenId": "mcn_..." }`.

- Only a token with the same `owner` and `domain` can be revoked.
- Unknown, expired, or foreign token ids return `NOTIFICATION_NOT_FOUND`
  (no existence leak).
- Response: `{ "revoked": true, "tokenId": "mcn_..." }`.
- The operation is available while the user websocket is online. This is the
  `LogoutAndroid` hook: the app calls `revokeNotifications` before logout for
  every token id it stored. There is no automatic revocation on websocket
  close, because the app may want background access to survive a UI logout.
- The raw bearer token is not accepted here; only its returned `tokenId`.

## Read-only HTTP events endpoint

### Path and routing

The real `hook_setupHttpHandlers(webserver, parent)` implementation registers,
for each configured domain, exactly:

```text
<domain.url>mobilecompanion/events
```

`meshcentral.js` sets `domain.url` to `/` for the default domain or
`/<domainid>/` for a path-based domain, and `/` for DNS domains. Therefore:

| Case | Endpoint path |
| --- | --- |
| default domain | `/mobilecompanion/events` |
| domain `d1` with `domain.url="/d1/"` | `/d1/mobilecompanion/events` |
| DNS domain | `/mobilecompanion/events` (domain resolved from Host/`req.xdomain`) |

The hook runs before the core per-domain routes. DNS domains that share `/` get
one registered route; the token domain is still checked against
`req.xdomain.id`. A token from domain `d1` is rejected on the default or `d2`
path even if the caller supplies a different `req.xdomain`.

### Request

```http
GET /d1/mobilecompanion/events?since=0&limit=50 HTTP/1.1
Host: meshcentral.example
Authorization: Bearer <43-character token>
Accept: application/json
```

- Authentication is **only** `Authorization: Bearer <token>`. The scheme match
  is case-insensitive; the token part must be exactly 43 base64url characters.
- Cookies are never read. Cookie-only requests get `401`.
- Query tokens are not accepted. Any query key other than `since`/`limit`,
  duplicate keys, `access_token`, `owner`, `domain`, etc. get `400`.
- `since`: strict decimal, `0..9007199254740991`, default `0`.
- `limit`: strict decimal, `1..100`, default `50`.
- No CORS headers are ever emitted (`Access-Control-Allow-Origin` is absent).
  This is a same-origin endpoint.
- The endpoint is GET-only and has no mutation operations. There is no way to
  call `startJob`, `runcommands`, `deleteJob`, or `revokeNotifications` over
  HTTP.

### Response

The body is the same shape as the websocket `events` response:

```json
{
  "items": [
    {
      "id": "mce_12",
      "sequence": 12,
      "kind": "job-completed",
      "nodeid": "node/<domain>/<node>",
      "jobId": "mc_...",
      "time": 1780000000000,
      "expiresAt": 1780604800000
    }
  ],
  "cursor": 18,
  "resetCursor": false
}
```

`device-alert` items have only `metric` and `direction` added. The common
`handleEvents` path is reused, so owner/domain filtering and fresh
`GetNodeWithRights` checks are identical to websocket `events`.

Headers on every response:

```text
Cache-Control: no-store
Pragma: no-cache
X-Content-Type-Options: nosniff
Content-Type: application/json; charset=utf-8
```

No `Access-Control-Allow-*` header is set.

### Errors

All authentication failures intentionally look identical:

```json
{"error":"unauthorized"}
```

with status `401`, `Cache-Control: no-store`, and no CORS header. This covers a
missing/malformed token, unknown token, revoked token, expired token, wrong
domain/path, deleted user, locked account, and an insecure request.

| Status | Body | Condition |
| --- | --- | --- |
| `400` | `{"error":"bad request"}` | invalid/unknown/duplicate query parameters |
| `401` | `{"error":"unauthorized"}` | TLS, bearer, token, user, domain, or path check failed |
| `405` | `{"error":"method not allowed"}` | non-GET; `Allow: GET` is set |
| `429` | `{"error":"rate limited"}` | more than 20 requests/minute for this token |
| `429` | `{"error":"busy"}` | 20 HTTP event requests already in flight |
| `500` | `{"error":"internal error"}` | persistence/ACL/internal failure or response over 128 KiB |

### TLS and reverse proxy

The endpoint requires TLS. A request is accepted when:

- the Node socket reports `encrypted === true`, Express `req.secure === true`,
  or the main app has a non-null local `webserver.tlsServer` (MeshCentral binds
  the main app only to its local HTTPS server when TLS is not offloaded); or
- MeshCentral is configured with `trustedproxy`/`tlsoffload` so Express sets
  `req.secure` for a trusted peer and `X-Forwarded-Proto: https`; or
- the boolean `tlsoffload: true` mode is configured (1.3.0 does not call
  `app.set('trust proxy')` for that form) and `X-Forwarded-Proto: https` is
  present.

Raw `X-Forwarded-Proto` is ignored in every other configuration. A deployment
using `tlsoffload: true` must firewall the MeshCentral HTTP port so clients
cannot connect to it directly and forge that header. Never expose plaintext
HTTP; never trust forwarded headers without the matching MeshCentral
`trustedproxy`/`tlsoffload` setting.

### Rate and concurrency

- After token/user/domain/path checks, each token gets a fixed 60-second window
  of 20 requests. The 21st gets `429 rate limited` and `Retry-After: 60`.
- At most 20 HTTP event requests are in flight globally. The next gets
  `429 busy` and `Retry-After: 1`.
- Rate entries older than 2 minutes (from the current window start) are
  removed and the map is capped at 512 entries (oldest insertion evicted).
  Counters are in memory for this process and reset on restart. The persisted
  token TTL is not affected.
- The response is capped at 128 KiB; a larger serialized response becomes a
  generic `500` rather than being sent unbounded.

## Notification token persistence schema

Tokens live in the same single state document as jobs/events and go through the
same serialized atomic store. The exact token object is:

```json
{
  "id": "mcn_0123456789abcdef",
  "hash": "<64 lowercase hex characters: SHA-256 of the raw token>",
  "owner": "user/d1/alice",
  "domain": "d1",
  "expiresAt": 1782592000000
}
```

- `tokens` is an array in `_id = "mobilecompanion_state"`,
  `type = "mobilecompanion_state"`.
- Only `id`, `hash`, `owner`, `domain`, and `expiresAt` are persisted. A
  plaintext `token` field is never written; on load the state validator
  rebuilds the allowlisted object, so a stray plaintext field from a broken
  document is not written back.
- Lookup compares SHA-256 hashes with `crypto.timingSafeEqual` over all stored
  tokens.
- Expired tokens are pruned on load and on every transaction. The global cap is
  200 and the per-user cap is 5; registration does not evict a live token.
- No token appears in logs or URLs. The registration response is the only
  place the raw token is returned.

## Public job object

```json
{
  "id": "mc_...",
  "nodeid": "node/<domain>/<node>",
  "templateId": "diagnostics",
  "parameters": {},
  "createdAt": 1780000000000,
  "startedAt": 1780000000100,
  "finishedAt": 1780000000900,
  "status": "completed",
  "exitCode": 0,
  "result": { },
  "truncated": false,
  "output": "...",
  "errorCode": "OPTIONAL_CODE"
}
```

- `status` ∈ `queued`, `running`, `completed`, `failed`, `expired`,
  `cancelled`, `unknown`.
- `startedAt` and `finishedAt` are `null` until known.
- `exitCode` is present only when the server-side completion marker supplied
  one.
- `result` is present only for parsed structured results.
- `output` is present only for failed/unknown jobs (bounded to 64 KiB). It is
  not emitted for successful parsed results.
- `truncated` is `true` when an array/string/output was capped.
- Internal `owner`, `domain`, `_nonce`, `_waitUntil`, `_platform`, and
  `operationId` are never returned. `operationId` is used only for dedupe.

## Template schemas

All three read-only templates return a JSON object with `os` equal to
`windows` or `linux`. Missing data is `null` or an empty array, never a fake
zero. Arrays are capped, and `truncated: true` means at least one cap applied.

### `diagnostics` (read-only)

```json
{
  "os": "linux",
  "hostname": "host-1",
  "osDescription": "Ubuntu 22.04.4 LTS",
  "uptimeSeconds": 3600,
  "cpu": {
    "logicalCount": 4,
    "load1": 0.5,
    "loadPercent": null
  },
  "memory": {
    "totalBytes": 1000000,
    "availableBytes": 400000,
    "usedPercent": 60
  },
  "disks": [
    {
      "device": "/dev/sda1",
      "mount": "/",
      "totalBytes": 1000000,
      "availableBytes": 400000,
      "usedPercent": 60
    }
  ],
  "network": [
    {
      "name": "eth0",
      "mac": null,
      "addresses": ["192.168.1.10/24", "fe80::1/64"]
    }
  ],
  "truncated": false
}
```

- Windows fills `cpu.loadPercent`; Linux fills `cpu.load1`. The other is
  `null`.
- Disks are capped at 100; network interfaces at 100; addresses per interface
  at 32.
- Linux disk entries come from `df -P -k`; Windows disk entries come from
  `Win32_LogicalDisk` (`DriveType=3`).
- Linux network entries come from `ip -o addr show`; Windows from
  `Win32_NetworkAdapterConfiguration` (`IPEnabled=True`).

### `services` (read-only)

```json
{
  "os": "windows",
  "items": [
    {
      "name": "Spooler",
      "displayName": "Print Spooler",
      "state": "Running",
      "startMode": "Automatic"
    }
  ],
  "truncated": false
}
```

- Items are capped at 200.
- Windows: `Get-Service` → `Name`, `DisplayName`, `Status`, `StartType`.
- Linux: `systemctl list-units` + `systemctl show` →
  `Id`, `Description`, `ActiveState`/`SubState`, `UnitFileState`. `state` is
  `ActiveState/SubState`; `startMode` is `UnitFileState`.
- `servicePath`, command arguments, environment, and executable paths are
  never collected or returned.

### `processes` (read-only)

```json
{
  "os": "linux",
  "items": [
    {
      "pid": 1234,
      "name": "sshd",
      "cpu": 1.5,
      "memory": 10240
    }
  ],
  "truncated": false
}
```

- Items are capped at 200.
- `cpu` is percent of one logical CPU and may exceed 100 on multi-core
  processes. Windows uses `Win32_PerfFormattedData_PerfProc_Process`
  `PercentProcessorTime`; Linux uses `ps pcpu`.
- `memory` is bytes (Windows `WorkingSet`; Linux `rss` KiB multiplied by 1024).
- Command lines, environment, users, and open files are never collected.

### Service mutations

Templates: `service-start`, `service-stop`, `service-restart`.

Parameters are strict: exactly one key, `name`. There are no wildcards and no
shell interpolation.

- Windows name: `^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$`
- Linux name: `^[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}$`

Preconditions: `confirmed: true`, online agent, current `visible` + rights
`0x20000`. The result on success:

```json
{
  "os": "linux",
  "operation": "service-start",
  "name": "sshd",
  "changed": true,
  "state": "active"
}
```

`changed` is `false` when the service was already in the requested state.
Windows uses `Get-Service` / `Start-Service` / `Stop-Service` /
`Restart-Service`; Linux uses `systemctl`. The final state is checked after the
operation; failure becomes a failed job, not a completed one.

## Command execution contract

- Windows stdout/stderr use UTF-8 explicitly (plugin 1.1.2). The default OEM
  encoding can make replies containing localized service/process names
  unreadable by the agent/server JSON transport. Regression tests validate the
  raw output as UTF-8 and round-trip Cyrillic/CJK names and command errors.
- Windows templates terminate the outer `try/catch` statement with a blank
  line. MeshCentral invokes `powershell -Command -` and keeps stdin open;
  without that terminator PowerShell buffers the statement and the appended
  `exit`, so the job times out without a response. Fixed in plugin 1.1.1.
- `test/windows-stdin.test.js` exercises all six generated Windows templates
  and a command failure through real Windows PowerShell with stdin kept open.
  Cmdlets are replaced with synthetic functions, including service mutations;
  no real services are changed. Run `npm test` on Windows to execute these
  checks; other platforms explicitly skip them. This test covers shell input,
  completion markers and payload parsing, not a connected Windows Mesh Agent.
- The plugin sends `{ action: "runcommands", type, cmds, runAsUser: 0,
  reply: true, responseid: <server nonce>, sessionid:
  "mobilecompanion/<jobid>" }` directly to `webserver.wsagents[nodeid]`.
- Shell type follows the audited MeshCentral 1.3.0 values: `2` = Windows
  PowerShell, `3` = Linux `/bin/sh`.
- `reply: true` is mandatory. A core ACK is not a completion signal.
- The generated script ends with a unique server nonce and exit code:
  `__MC_DONE_<nonce>__<exitcode>`. Parsing requires the exact nonce at the end
  of the combined stdout+stderr. A missing marker becomes `unknown`
  (`MARKER_MISSING`), never `completed`.
- A non-zero marker exit code becomes `failed` (`COMMAND_FAILED`).
- A successful marker with an invalid/unparseable payload becomes `failed`
  (`INVALID_RESULT`).
- Execution timeout (5 minutes) becomes `unknown` (`TIMEOUT`). No automatic
  retry is ever performed.
- stdout and stderr are merged by MeshCentral; the plugin does not invent a
  separate stderr channel.
- The synthetic `mobilecompanion/<jobid>` session id is not a live user
  session. `webserver.routeAgentCommand` requires a four-part
  `user/<domain>/<user>/<session>` id, so this reply is dropped by core and is
  correlated only in `hook_processAgentData` by `responseid` plus
  `agent.dbNodeKey`.
- The hook is called after core's routing attempt. A compromised agent that
  deliberately forges a real user session id could still reach core routing;
  the plugin cannot prevent that without a core patch. This plugin never sends
  a real session id and ignores wrong agent/wrong `responseid` replies.

## Alerts

`device-alert` is generated only when a **successful `diagnostics` job** returns
a valid parsed result and one of these server thresholds is met:

| Metric | Condition | Event fields |
| --- | --- | --- |
| memory | `result.memory.usedPercent >= 95` | `metric: "memory"`, `direction: "high"` |
| disk | any `result.disks[].usedPercent >= 90` | `metric: "disk"`, `direction: "high"` |

- At most one event per metric and node per hour (dedupe key
  `alert:<nodeid>:<metric>`). A disk alert is emitted once even if several
  disks are above the threshold.
- The event contains only `metric` and `direction` in the allowlist. Raw
  percentages, disk/memory arrays, command output, and agent text are never
  stored in the alert event.
- The alert has `owner: null`, so current visibility of the device is checked
  on every read, like other device events.
- Failed, invalid, missing-marker, non-diagnostics, or expired jobs never
  create alerts.
- There is no push. Android may add its own filters/thresholds; the server
  thresholds above are authoritative for server events and are reported in
  `capabilities`.

## Persistence, limits, retention

- One JSON database document, only this plugin's record:
  `_id = "mobilecompanion_state"`, `type = "mobilecompanion_state"`.
- Fields: `seq`, `jobs`, `events`, `nodeStates`, `tokens`.
- All reads/writes go through small DB adapters and a serialized Promise queue.
  Every mutation is applied to a draft and only committed after
  `db.Set` succeeds; failed saves leave memory unchanged.
- Hard limits: 40 jobs total, 500 events total, 64 KiB stored output per job,
  200 notification tokens globally (5 per user), 4 MiB aggregate document,
  7-day history.
- Structured `result` JSON is also capped at 64 KiB; arrays are shortened and
  `truncated` is set when needed.
- Active (`queued`/`running`) jobs are never evicted. Full job capacity returns
  `QUOTA_EXCEEDED`; it does not silently delete active work.
- Event history is a bounded ring: expired events are removed, and the oldest
  event is displaced when the 500-event cap is reached. It is not a durable
  audit log.
- On process restart:
  - `running` becomes `unknown` with `errorCode: "SERVER_RESTART"`; the command
    is not sent again.
  - `queued` jobs with a valid wait deadline stay queued for the agent and are
    re-timed; queued jobs without a deadline become `unknown`.
  - Commands are never automatically re-executed.
  - Tokens remain valid until their persisted `expiresAt`; HTTP auth resolves
    them from the reloaded state.
  - Expired tokens are pruned during load.

## Events

Stored event kinds:

| Kind | Source | Visibility |
| --- | --- | --- |
| `job-completed` | plugin final with exit code 0 and valid result | job owner + current device rights `0x20000` |
| `job-failed` | known job failure (non-zero marker, invalid result, revoked rights, unavailable user) | job owner + current device rights `0x20000` |
| `device-online` | real `nodeconnect` event with agent connectivity bit 1 set | any user with current `visible` device access |
| `device-offline` | real `nodeconnect` event without agent bit 1 | any user with current `visible` device access |
| `device-requesthelp` | real agent log event `etype:"node"`, `action:"agentlog"`, `msgid:98` | any user with current `visible` device access |
| `device-alert` | successful `diagnostics` result above server threshold | any user with current `visible` device access |

- `unknown`, `expired`, and `cancelled` jobs do not create job events; they are
  visible through `getJob`/`listJobs`. A dispatch exception also becomes
  `unknown` (the command may have reached the agent), so it does not emit a
  false `job-failed`.
- `device-online`/`device-offline` deliberately use the **agent** bit, because
  jobs require a connected MeshAgent. CIRA/AMT-only connectivity does not count
  as online for this plugin.
- `msgid 98` is the confirmed help-request marker in 1.3.0
  (`meshagent.js` calls `NotifyUserOfDeviceHelpRequest` for it). If upstream
  changes that event id, the plugin stops producing `device-requesthelp`
  instead of inventing a type. No raw message text is stored.
- Events are written with `main.AddEventDispatch(['*'], target)`; 1.3.0
  `CreateNodeDispatchTargets`/`CreateMeshDispatchTargets` always add `*`, which
  covers `nodeconnect` and `agentlog`. No plugin event hook exists in core.

## Known limitations and contracts to review

1. **No MeshCentral core patch.** Running `runcommands` cannot be cancelled or
   deduplicated by core. The plugin deduplicates by `operationId` and status,
   never claims a running process was killed, and treats missing final markers
   as `unknown`.
2. **Single-server assumption.** `webserver.wsagents` is the local agent map.
   There is no peer-server dispatch. Multi-server MeshCentral deployments are
   not supported by this version.
3. **Single-process serialization.** The Promise queue serializes this plugin
   instance, not multiple servers or multiple plugin processes. The aggregate
   record is global for the server.
4. **`hook_processAgentData` runs after core routing.** The synthetic session
   id makes normal replies non-routable, but a malicious agent can forge a
   different session id. See the command execution contract above.
5. **Linux OS detection is description-based.** Windows uses the audited agent
   id ranges (1..4 and 42..43). Non-Windows is accepted as Linux only when
   `osdesc` identifies a Linux distribution; macOS/BSD return `UNSUPPORTED_OS`.
   Linux requires `systemctl`, `ps` (procps), `df`, `ip`, `awk`, `sed`, `head`,
   and `cut`; a missing utility makes the job fail rather than returning a
   false empty success.
6. **Windows requirements.** PowerShell 3+ and the CIM classes
   `Win32_OperatingSystem`, `Win32_Processor`, `Win32_LogicalDisk`,
   `Win32_NetworkAdapterConfiguration`, and
   `Win32_PerfFormattedData_PerfProc_Process`, plus `Get-Service`. If CIM is
   unavailable the template fails; it does not fabricate empty data.
7. **Read-only agent wait.** Only read-only diagnostics/services/processes wait
   for up to 5 minutes. Mutations are never queued while offline.
8. **Event history is not an audit log.** It is a 7-day/500-item ring and can
   be displaced by device events.
9. **`listJobs` extra fields.** The task did not specify `limit`/`nodeid`; they
   are optional additions documented above. The default list is 20, maximum 40.
10. **`cancelJob` on terminal jobs** is an idempotent no-op returning the job.
    Running jobs return `CANCEL_UNSUPPORTED`.
11. **Cursors.** `cursor` is the highest global sequence evaluated, not the
    last returned item. A client that stores it will not revisit events that
    were invisible at query time. If rights are later granted, older events are
    not replayed; only new events are delivered.
12. **HTTP tokens are bearer secrets.** Anyone with the token can read the
    owner's events until expiry/revocation. The app must keep it in Android
    Keystore-backed storage, never put it in a URL, and never log it. The token
    is shown only in the registration response.
13. **No automatic token revocation on logout.** The app must call
    `revokeNotifications` over the authenticated websocket before logout. If
    the token is lost, it remains valid until the 30-day TTL or until the app
    can enumerate/revoke the stored `tokenId`. Deleting the account removes the
    user and HTTP rejects the token, but the hash may remain until TTL/prune.
14. **Explicit delete only.** `deleteJob` removes the job and linked
    `job-completed`/`job-failed` events. It does not remove `device-alert`
    events, which are node-level events without a `jobId`.
15. **Rate/inflight are per process and in memory.** A restart resets the
    20/min window and the 20-request inflight counter. Token TTL is persisted.
16. **Reverse proxy TLS is a deployment contract.** The endpoint requires
    `req.secure`/socket TLS or the boolean `tlsoffload` mode with
    `X-Forwarded-Proto: https`. The operator must firewall direct plaintext
    access. Raw forwarded headers are otherwise ignored.
17. **HTTP is events-only.** There is no HTTP job creation, command execution,
    token revocation, or mutation API. `deleteJob` and
    `revokeNotifications` are websocket-only.
18. **No push.** `device-alert` is stored in the bounded event ring; there is
    no instant push or push provider integration.
19. **`deleteJob` and dedupe.** Deleting a terminal job removes its
    `operationId` record, so a later `startJob` with the same operation id is
    treated as new. This is intentional for an explicit owner delete, but
    clients should use a fresh operation id for re-runs.
20. **No production install.** Installation, reverse proxy, TLS, and server
    maintenance stay with the lead after review.

## Package layout

```text
mobilecompanion.js       plugin entry point, auth, jobs, events, hooks,
                         notification tokens, read-only HTTP events
config.json              MeshCentral plugin metadata
lib/state.js             DB adapter, serialized state queue, retention, tokens
lib/templates.js         fixed scripts, allowlist, marker parsing
lib/schema.js            bounded JSON schemas and Linux parsers
lib/errors.js            internal error type
test/*.test.js           node:test + stubs (no npm dependencies)
```

## Test coverage

The suite has 41 tests and keeps all 28 original jobs/events/templates tests.
It covers the mandatory notification cases:

- raw token is never in the persisted state; only its SHA-256 hash and
  allowlisted metadata are stored;
- fake agent context cannot `registerNotifications`;
- foreign, revoked, expired, deleted-user, locked-account, wrong-domain, and
  unknown tokens are denied with the same generic 401 body;
- cookie-only and query-token requests are rejected; unknown/duplicate query
  fields and out-of-range `since`/`limit` are 400;
- HTTP never dispatches `runcommands` and reuses the shared `handleEvents` with
  fresh device ACL checks;
- same-origin domain paths are registered through the real
  `hook_setupHttpHandlers`, and cross-domain tokens are rejected;
- per-token 20/min rate, bounded/cleaned rate entries, and global inflight 20;
- token remains valid across restart until the persisted TTL;
- `deleteJob` checks owner, current rights, active status, and removes linked
  job events;
- `registerNotifications` 5/user and 200/global quota; owner-only
  `revokeNotifications`;
- `device-alert` threshold, allowlist fields, and one-hour per-node/metric
  dedupe;
- capabilities report the server thresholds, operations, and `push: false`;
- all original jobs/events/templates tests still pass.

## Delivery failure boundaries

Every persisted new job receives a deadline before dispatch. If saving the running state fails, the still-queued job is marked failed without sending a command. Database failures while writing terminal state are retried every 30 seconds (one timer per job); remote commands are never retried. Mutations remain non-resumable across server restart. Diagnostic health alerts require REMOTECOMMAND as well as current visibility.
