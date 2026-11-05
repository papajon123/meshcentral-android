# MeshPilot isolated review demo

A **local-only** MeshCentral 1.3.1 fixture for reviewing the client against one
synthetic Linux target. It is a development fixture, not a production deployment.
The app does not deploy MeshCentral servers.

## What is here

- `compose.yml` - loopback-published server plus a target with no host ports.
- `Dockerfile` - pinned multi-stage server and target images.
- `server-entry.cjs` - offline NeDB seed, ownership marker, MeshCentral launch,
  and agent configuration publication.
- `target-entry.sh` - internal-only SSH, Xvfb/openbox/xterm, and the sandbox agent.
- `smoke.cjs` - sanitized control-channel checks.
- `README.md` - this runbook.

The only published endpoint is `127.0.0.1:4469` on the host. The Android emulator
uses `https://10.0.2.2:4469/`. The certificate is self-signed for `10.0.2.2`;
trust it only inside the emulator. There is no public URL.

## Accounts and data

- `admin` - site administrator, full rights on the only group.
- `reviewer` - remote-control rights `8` on the single group `MeshPilot sandbox`, without server administration.
- The group contains only the synthetic target container; there are no real
  computers.

Passwords come only from the three required environment variables below. They are
hashed with upstream 1.3.1 `pass.js` while the empty database volume is seeded.
The NeDB file stores only upstream `salt`/`hash` values. Passwords are not written
to the repository, image, or logs. Existing data is reused only when the ownership
marker and owned group match; any foreign or marker-less database is refused.

Server TLS private material is generated at runtime in the `meshcentral-data`
named volume. The target host key is generated at runtime in `ssh-host-keys`. The
`.msh` file is delivered through the separate `agent-config` named volume and is
never printed or exposed as a URL. The agent stores its identity/config only in
the `sandbox-home` runtime volume.

## Pinned inputs

- `node:22-bookworm-slim` index digest
  `sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da`
- `debian:bookworm-slim` index digest
  `sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3`
- MeshCentral `1.3.1` npm tarball, SHA-512 integrity verified at build; its optional SSH module `ssh2@1.17.0` is installed in the image.
- MeshAgent `legacy-1.2.6` `meshagent_x86-64`, size and SHA-384 verified at build.

Images are tagged only `meshpilot-review-server:local` and
`meshpilot-review-target:local`. The build context is this `demo` directory, not
the repository root.

## Run

Requires Docker Desktop with the already-approved `desktop-linux` context and
PowerShell 7. Run from the repository root. Enter secrets through prompts; do not
put them in history or a file.

```powershell
function Read-DemoSecret([string]$Prompt) {
    (Read-Host $Prompt -AsSecureString | ConvertFrom-SecureString -AsPlainText)
}
$env:MESH_DEMO_ADMIN_PASSWORD = Read-DemoSecret 'Local demo admin password'
$env:MESH_DEMO_REVIEW_PASSWORD = Read-DemoSecret 'Local demo reviewer password'
$env:MESH_DEMO_SSH_PASSWORD = Read-DemoSecret 'Local sandbox SSH password'
```

Keep these variables set for all Compose commands; every command interpolates the
required variables. They exist only in this PowerShell process. Do not use
placeholder values for a real launch.

```powershell
docker --context desktop-linux compose -p meshpilot-review-demo `
  -f distribution/google-play/demo/compose.yml config --quiet
docker --context desktop-linux compose -p meshpilot-review-demo `
  -f distribution/google-play/demo/compose.yml build
docker --context desktop-linux compose -p meshpilot-review-demo `
  -f distribution/google-play/demo/compose.yml up -d
```

`config --quiet` intentionally prints nothing. Wait for the server health check
and the target agent, then verify the local endpoint:

```powershell
docker --context desktop-linux compose -p meshpilot-review-demo `
  -f distribution/google-play/demo/compose.yml ps
Invoke-WebRequest 'https://127.0.0.1:4469/' -SkipCertificateCheck -UseBasicParsing |
  Select-Object StatusCode
```

Run the sanitized smoke test inside the server container:

```powershell
docker --context desktop-linux compose -p meshpilot-review-demo `
  -f distribution/google-play/demo/compose.yml exec -T server `
  node /opt/meshpilot/smoke.cjs
```

It emits one JSON object with booleans/counts/group names only. `ok=true` means:
admin and reviewer authenticate, the wrong reviewer password is rejected, exactly
one agent is online, reviewer is not a site admin, and reviewer sees exactly one
group named `MeshPilot sandbox`.

Stop only this Compose project, without deleting volumes:

```powershell
docker --context desktop-linux compose -p meshpilot-review-demo `
  -f distribution/google-play/demo/compose.yml down
Remove-Item Env:MESH_DEMO_ADMIN_PASSWORD, Env:MESH_DEMO_REVIEW_PASSWORD, Env:MESH_DEMO_SSH_PASSWORD
```

No command in this runbook removes named volumes. Runtime volumes retain the demo
database, TLS keys, agent configuration, host keys, and sandbox identity.

## Security boundaries and limitations

- The server publishes only `127.0.0.1:4469:8443`. The target has no host ports.
- The target network is `internal: true`, with no host mounts, Docker socket,
  privileged mode, or host networking. This is strong fixture separation, not an
  absolute network-isolation guarantee.
- Target SSH listens only on port 22 inside the internal link. Root SSH is
  disabled, only `sandbox` may log in, and the image has no `sudo`.
- The MeshCentral domain enables the confirmed 1.3.1 `ssh` feature flag. This is
  separate from the target's internal `sshd`.
- Desktop is synthetic: Xvfb at `:99`, 1280x720, openbox and xterm, with no TCP X11.
  Desktop/consent testing needs an interactive user and is not claimed as verified.
- `smoke.cjs` does not execute terminal/files relay because the exact 1.3.1 relay
  framing was not audited here; it reports `relayTested=false` and
  `terminalFilesRelayVerified=false`. It also reports `desktopConsentVerified=false`.
  Do not treat the basic smoke result as full remote-control acceptance.
- Server self-update, default-agent downloads, agent updates, WebRTC and new account registration are off. Server files and backups stay under the owned `/data` volume.
  The server is not hardened for public exposure.
- Hosting the demo publicly and creating a real reviewer account are separate
  future steps. They are deliberately not provided here.

## Troubleshooting

If the target log reports that the agent configuration volume is not ready, the
server has not yet generated its certificate and `.msh`. Wait for the server
health check, then restart only the target service with the same Compose project.
If the ownership marker is rejected, the volume is not owned by this fixture; do
not point this Compose file at it. This runbook provides no volume-deletion command.

## Local validation, 2026-11-04

Both images built successfully on Docker Desktop. JavaScript and shell syntax checks passed.
The running fixture passed all control-channel checks in `smoke.cjs`: both accounts authenticated,
the wrong password was rejected, the reviewer had no site-admin rights, exactly one group and
one online synthetic agent were visible. Containers were stopped after verification; owned
volumes were retained. This does not verify remote desktop, consent, terminal/files or SSH/SFTP
relay behavior, and it is not a public reviewer endpoint.
