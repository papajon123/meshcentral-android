# Local MeshCentral livebench

An isolated Windows/WSL/Android test fixture for the public mobile client. It uses
MeshCentral **1.3.1**, the official Android agent **1.0.23**, and the upstream
Linux x64 agent from **legacy-1.2.6**. It never administers a production server.

## Opt-in OpenSSH acceptance

`sftp_fixture.py create --root <owned-root>/jail/fixture --uid <temporary-uid> --gid <temporary-gid>`
creates the hostile Unix tree used by `SftpLiveAcceptanceTest`; it requires a new,
owned root and refuses symlink ancestors. `repair --root <same-root>` changes only
the known hazards after validating its marker and file contents. It does not
install/start SSH, create users or delete the test environment.

The Android test is skipped unless `-e sftpLive true` is supplied. It requires an
isolated SSH-enabled MeshCentral 1.3.1 at `https://10.0.2.2:4467/`, its own agent
and SSH node, and a loopback-only metadata bridge at `http://10.0.2.2:19867/`.
`GET /fixture` supplies `serverUrl`, `sshNodeId`, `directory` (`/fixture`) and
`manifest` (relative path to `{size, sha256}`). Bounded `POST /op/reset` and
`/op/repair` return `manifests` for `source` and `cancel-test`; `/op/swap`,
`/op/inspect` and `/op/allow-directory` affect only the synthetic deletion and
permission fixtures. Never point this bridge at arbitrary paths or production.

Seed `cache/sftp-live-auth.json` into the debug app's private directory through
`adb -s <owned-serial> shell run-as ...` stdin with mode 0600. Its keys are
`serverUsername`, `serverPassword`, `serverPin` (SHA-256 SPKI), `sshUsername` and
`sshPassword`. Generate temporary credentials outside the repository; do not put
them in command arguments, test output or the HTTP bridge. After pulling test
evidence, delete this file and verify absence before stopping the owned emulator;
remove the temporary server, SSH process/user/keys and filesystem tree as well.
The test uses the real Files screen, SAF picker and relay; the HTTP bridge only
controls owned fixtures and provides independently computed source hashes.

## Requirements

- Windows, PowerShell 7, Node.js/npm, Python 3, and WSL `Ubuntu-24.04` with systemd.
- JDK 17 (`JAVA_HOME`) and Android SDK platform/build-tools 35 (`ANDROID_HOME`).
- Existing AVDs `PublicCompatibility35` and `PublicReadiness`. The default
  `ANDROID_AVD_HOME` is the owner's `orca/workspaces/other/Meshcentral-Android/state/public-avd-home`
  under the user profile; override it with `-AvdHome C:\bench\avds` if necessary.
- Ports 4443, 5568, and 5570 must be free. The script refuses to adopt an
  unowned listener. Emulator ports are fixed intentionally.
- At least **6 GiB free RAM before each emulator starts**. Build first, then
  start the emulators. Other workloads may require more headroom. A low-memory
  failure leaves the already-started owned components available; wait and retry.
- Internet access to `registry.npmjs.org`, GitHub releases, and the Gradle/Android
  dependency repositories during the initial setup/build.

## Commands

From the repository root:

```powershell
pwsh -NoProfile -File tools/livebench/livebench.ps1 -Setup
$env:JAVA_HOME = 'C:\tools\jdk-17'
pwsh -NoProfile -File tools/livebench/build.ps1
pwsh -NoProfile -File tools/livebench/livebench.ps1 -Start
pwsh -NoProfile -File tools/livebench/livebench.ps1 -Status
pwsh -NoProfile -File tools/livebench/livebench.ps1 -Stop
```

`-Start` starts the server and the WSL agent, boots both owned emulators with
`-read-only -no-snapshot`, installs/pairs the Android agent, and installs the
already-built debug client. `-Start -ServerOnly` starts just the server and WSL
agent. The client APK is not built implicitly. `-Status` authenticates against
the local control channel and prints group names and device online states.
Startup waits for the required agents to appear online; a timeout fails the
command instead of claiming readiness. An already-active WSL agent with identical
configuration is preserved without breaking its sessions.

`-Setup` is repeatable and preserves existing credentials and group IDs. Stop
the server before using it. It uses its own `package.json`, explicit public npm
registry, and private npm cache; the server inherits the same npm registry/cache
for its automatic dependency installation.

`-Reset` stops the owned components, **archives** the fixture database,
certificates, credentials, and enrollment files beneath the private data root,
and creates a new fixture. It also archives the WSL agent identity. Old secrets
remain in the reset archive until the owner deliberately removes that archive.
Run `-Start` afterward to enroll agents into the new groups. No global npm,
firewall, hosts-file, trust-store, or WSL distribution settings are changed.

## Private state and accounts

All Windows runtime state is under:

```text
%LOCALAPPDATA%\meshcentral-livebench\
  server\                 isolated npm installation and package-lock.json
  meshcentral-data\       NeDB, certificates, server configuration, agent cache
  credentials.json        generated account passwords and TOTP secret
  fixture.json            generated device-group identifiers
  android-enrollment.txt  private mc:// enrollment link
  linux-agent.msh         private WSL enrollment configuration
  downloads\             pinned agent APK and optional toolchain downloads
  logs\                  local process/build logs (do not publish)
  baseline\              sanitized observations and screenshots
```

The root ACL grants access to its owner and SYSTEM. Treat all runtime files as
private even when their names do not contain “secret”. The fixture generates an
administrator, `totp-user` with TOTP, and `limited-user` with remote-control,
terminal, and file rights only in `bench-linux`. `totp-user` has those rights in
both `bench-linux` and `bench-android`. New public registrations and server
self-update are disabled. Open `credentials.json` locally when manual login is
needed; never paste its contents into logs, issues, or this repository.

The server uses a stopped-database fixture based on the actual 1.3.1
`pass.js`/`meshuser.js` schema. Credentials are hashed with upstream code; it does
not guess a remote account-creation API. Existing unrelated databases are not
adopted. Linux installation is confined to `/opt/meshcentral-livebench` and the
owned `meshcentral-livebench.service`. A separate owned Windows `wsl.exe` keeps
the distro alive; systemd services alone do not guarantee WSL stays running.
`-Stop` stops that service and the owned lease, never shuts down WSL globally.

## Networking and trust

- Android server address: **`https://10.0.2.2:4443`**, the Android emulator's
  host-loopback alias. The generated certificate and Android enrollment link
  use the same name. The debug client must enable **Trust self-signed certificates**.
- Windows browser: `https://localhost:4443/`. Accept this fixture's certificate
  locally for the test. No system trust-store installation is performed.
- `adb reverse tcp:4443 tcp:4443` is also installed per owned emulator, but the
  tested Android configuration uses `10.0.2.2`.
- WSL uses its current Windows NAT gateway. MeshAgent authenticates using its
  pinned server public-key hash from the `.msh` file; it does not rely on that
  gateway address matching the browser certificate name. `-Start` regenerates
  this address after WSL networking changes.
- Allowed browser origins are explicitly limited to `localhost`, `127.0.0.1`,
  and `10.0.2.2`. The HTTPS listener is reachable from WSL; use only a trusted
  local test machine/network. No external address or production credentials are
  required.

Do not change the certificate name to bare `localhost`: 1.3.1 forces LAN mode
when the certificate common name has no dot, even when `WANonly` is configured.
The mobile link is constructed exactly as in upstream `webserver.js`:
`mc://host:port,agentPublicKeyHashBase64,meshId`. Enrollment material is never
printed. Linux binaries are checked against the package's pinned SHA384;
the Android APK is checked against its release SHA256.

## Repeatable checks

```powershell
python -m unittest discover -s tools/livebench -p test_helpers.py -v
node tools/livebench/bench.cjs verify
$env:ANDROID_SERIAL = 'emulator-5570'
python tools/livebench/baseline.py
```

`verify` checks all three accounts, rejection of a wrong password, rejection of
missing TOTP, and the limited user's group boundary. It writes sanitized results
to `auth-checks.json` in the private root. `baseline.py` resets **only** the debug
client's app data on the owned `emulator-5570`, tests wrong-password handling,
password login/device list, and a TOTP challenge/code generated by `totp.py`.
It writes `baseline/auth-ui.json` and screenshots and returns nonzero for failed
client scenarios. A client failure is a baseline finding, not a reason to
silently modify the client.

`ui.py` requires an explicit owned `ANDROID_SERIAL`; there is no default serial.
It offers `dump`, `tap`, `tapdesc`, `key`, `shot`, `pair-agent`, and `consent`. UI dumps hide
input values. Screenshots refuse filled input/enrollment forms. Use the Python
API for credentials, never put passwords or OTPs on a command line. `totp.py`
can be imported; its CLI deliberately does not print codes.

For the remote-tool checks:

1. In the client, open the online Android device and inspect its capability tabs.
2. Open Desktop. On `emulator-5568`, use `ui.py` to choose **Entire screen** and
   **Start** in Android's capture-consent dialog (`python tools/livebench/ui.py consent`). Repeat consent after a fresh
   emulator boot. A connected label alone does not prove a visible picture.
3. In the local web UI, open the same online device, Desktop, Connect. Verify an
   actual image and save a screenshot. Android Files should list its media/root
   categories in the web UI; compare that with the native client's Files tab.
4. Open the Linux terminal and run `echo LIVEBENCH_TERMINAL_OK`. Its fixture file
   is `/opt/meshcentral-livebench/fixture/hello.txt`. Browse only that test path,
   and verify a download's contents rather than just a success label.

## Known limitations

- Read-only AVD sessions intentionally discard installs, enrollment, consent,
  and client state on shutdown. `-Start` reinstalls/re-pairs them; this can leave
  an older offline Android device record. Select the **online** entry.
- The host may have no Linux desktop. Terminal/files are the supported WSL
  checks; a Linux graphical session is not provisioned.
- MeshCentral's default downloader may reject GitHub addresses supplied by the
  local DNS/proxy. Setup preloads the verified Linux x64 binary into
  `meshcentral-data/agentbuilds/legacy-1.2.6`. Warnings about other architectures
  do not mean the two fixture agents failed.
- This is a test server with a self-signed certificate, not a hardened or
  publicly hosted deployment. Do not publish its private state or APK outputs.
- Native Android desktop/file behavior and Linux transfer behavior must be
  judged from the recorded baseline. Server connectivity is not end-to-end
  acceptance of every feature.

Upstream sources: [MeshCentral 1.3.1](https://www.npmjs.com/package/meshcentral/v/1.3.1),
[Android agent 1.0.23](https://github.com/Ylianst/MeshCentralAndroidAgent/releases/tag/1.0.23),
[legacy Linux agents](https://github.com/Ylianst/MeshAgent/releases/tag/legacy-1.2.6).
