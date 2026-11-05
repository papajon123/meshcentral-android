#!/usr/bin/env bash
set -euo pipefail
umask 077

log() { printf '%s\n' "$*" >&2; }
fail() { log "$*"; exit 1; }

[[ "$(id -u)" -eq 0 ]] || fail 'target entrypoint must start as root'
[[ -n "${MESH_DEMO_SSH_PASSWORD:-}" ]] || fail 'MESH_DEMO_SSH_PASSWORD is required'
[[ "$MESH_DEMO_SSH_PASSWORD" != *$'\n'* ]] || fail 'MESH_DEMO_SSH_PASSWORD must be a single line'
command -v sudo >/dev/null 2>&1 && fail 'sudo must not be installed in the target image'

ssh_password=$MESH_DEMO_SSH_PASSWORD
unset MESH_DEMO_SSH_PASSWORD

install -d -m 0700 /run/meshpilot-ssh /run/sshd
if [[ ! -s /run/meshpilot-ssh/ssh_host_ed25519_key ]]; then
  ssh-keygen -q -t ed25519 -N '' -f /run/meshpilot-ssh/ssh_host_ed25519_key
fi
chmod 0700 /run/meshpilot-ssh
chmod 0600 /run/meshpilot-ssh/ssh_host_ed25519_key

install -d -o sandbox -g sandbox -m 0700 \
  /home/sandbox /home/sandbox/agent /home/sandbox/fixture /home/sandbox/.config/openbox
chown -R sandbox:sandbox /home/sandbox
printf '%s\n' 'MeshPilot synthetic fixture.' > /home/sandbox/fixture/hello.txt
chown sandbox:sandbox /home/sandbox/fixture/hello.txt
chmod 0644 /home/sandbox/fixture/hello.txt

printf 'sandbox:%s\n' "$ssh_password" | chpasswd
unset ssh_password

install -m 0700 -o sandbox -g sandbox /opt/meshpilot/meshagent /home/sandbox/agent/meshagent

install -d -m 1777 /tmp/.X11-unix
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99

runuser -u sandbox -- env HOME=/home/sandbox DISPLAY=:99 XAUTHORITY=/home/sandbox/.Xauthority \
  Xvfb :99 -screen 0 1280x720x24 -nolisten tcp -ac >/dev/null 2>&1 &
xvfb_pid=$!

for _ in $(seq 1 50); do
  [[ -S /tmp/.X11-unix/X99 ]] && break
  kill -0 "$xvfb_pid" 2>/dev/null || fail 'Xvfb exited before creating display :99'
  sleep 0.2
done
[[ -S /tmp/.X11-unix/X99 ]] || fail 'display :99 did not become ready'

runuser -u sandbox -- env HOME=/home/sandbox DISPLAY=:99 XAUTHORITY=/home/sandbox/.Xauthority \
  openbox >/dev/null 2>&1 &
openbox_pid=$!

runuser -u sandbox -- env HOME=/home/sandbox DISPLAY=:99 XAUTHORITY=/home/sandbox/.Xauthority \
  SHELL=/bin/bash xterm -geometry 110x30+16+16 -title 'MeshPilot demo' \
  -e /bin/bash -lc 'cat "$HOME/fixture/hello.txt"; exec /bin/bash' >/dev/null 2>&1 &
xterm_pid=$!

for _ in $(seq 1 120); do
  [[ -s /run/meshpilot-config/meshagent.msh ]] && break
  sleep 1
done
[[ -s /run/meshpilot-config/meshagent.msh ]] || fail 'agent configuration volume is not ready'

install -m 0600 -o sandbox -g sandbox \
  /run/meshpilot-config/meshagent.msh /home/sandbox/agent/meshagent.msh

(
  cd /home/sandbox/agent
  exec runuser -u sandbox -- env HOME=/home/sandbox DISPLAY=:99 \
    /home/sandbox/agent/meshagent run
) >/dev/null 2>&1 &
agent_pid=$!

/usr/sbin/sshd -t -f /etc/ssh/sshd_config
/usr/sbin/sshd -D -e -f /etc/ssh/sshd_config &
sshd_pid=$!

cleanup() {
  trap - EXIT TERM INT
  for pid in "$sshd_pid" "$agent_pid" "$xterm_pid" "$openbox_pid" "$xvfb_pid"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 0' TERM INT

wait "$sshd_pid"
