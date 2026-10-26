#!/usr/bin/env bash
set -euo pipefail
source_root="$1"
target=/opt/meshcentral-livebench
if [[ -e "$target" && ! -f "$target/owned-by-livebench" ]]; then
    printf '%s\n' 'Refusing to adopt an existing WSL directory.' >&2
    exit 1
fi
install -d -m 700 "$target"
unit=/etc/systemd/system/meshcentral-livebench.service
if [[ -e "$unit" ]] && ! grep -q '^# Owned by meshcentral-livebench$' "$unit"; then
    printf '%s\n' 'Refusing to replace an unrelated systemd service.' >&2
    exit 1
fi
unit_contents=$(cat <<'UNIT'
# Owned by meshcentral-livebench
[Unit]
Description=Isolated MeshCentral livebench Linux agent
After=network.target
[Service]
Type=simple
WorkingDirectory=/opt/meshcentral-livebench
ExecStart=/opt/meshcentral-livebench/meshagent run
Restart=always
RestartSec=5
UMask=0077
StandardOutput=null
StandardError=null
[Install]
WantedBy=multi-user.target
UNIT
)
binary="$source_root/meshcentral-data/agentbuilds/legacy-1.2.6/meshagent_x86-64"
if [[ -f "$target/owned-by-livebench" && -f "$unit" ]] &&
    cmp -s "$binary" "$target/meshagent" &&
    cmp -s "$source_root/linux-agent.msh" "$target/meshagent.msh" &&
    [[ "$(cat "$unit")" == "$unit_contents" ]] &&
    systemctl is-active --quiet meshcentral-livebench.service; then
    printf '%s\n' 'Bench WSL agent already active; configuration unchanged.'
    exit 0
fi
touch "$target/owned-by-livebench"
systemctl stop meshcentral-livebench.service 2>/dev/null || true
install -m 700 "$binary" "$target/meshagent"
install -m 600 "$source_root/linux-agent.msh" "$target/meshagent.msh"
mkdir -p "$target/fixture"
if [[ ! -f "$target/fixture/hello.txt" ]]; then
    printf '%s\n' 'Synthetic livebench file.' > "$target/fixture/hello.txt"
fi
printf '%s\n' "$unit_contents" > "$unit"
systemctl daemon-reload
systemctl start meshcentral-livebench.service
systemctl is-active meshcentral-livebench.service
