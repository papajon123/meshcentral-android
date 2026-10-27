# Task tools fixtures

Linux replies were captured on 2026-10-27 from the isolated loopback MeshCentral 1.3.1 server and its Linux x64 legacy-1.2.6 agent on Ubuntu 24.04.2. `ps` contained 35 entries, `services` 344. Only eight representative rows are retained. Names, users, commands, paths and PIDs are synthetic; field structure and service state values are retained. Linux services have `serviceType: systemd`, with optional top-level `state: RUNNING`; absence is unknown, never stopped.

Windows examples are synthetic, based on MeshCentral 1.3.1 `agents/meshcore.js:1498–1618` and `views/default.handlebars` (`showDeskToolsProcesses`, `showDeskToolsServices`, `showServiceDetailsDialog`). No Windows agent was enrolled on the isolated test server during this work. The Windows fixtures are protocol examples, not a live Windows acceptance claim.

Additional live observations: Linux enumeration included a `PID`/`USER` header and returned each of 172 services twice with identical relevant fields (344 raw entries). Regression tests cover both forms, reject header-only snapshots and reject contradictory duplicate service states. A read-only `service` probe returned the requested name and startup type and then the extra `services` message; no service was changed.

After review: both Windows examples now use JSON text in `value`, matching `meshcore.js` list sends. Decoded-object compatibility is tested separately. These examples remain synthetic and do not establish Windows runtime acceptance.
