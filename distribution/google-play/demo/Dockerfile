# syntax=docker/dockerfile:1.7

# Pinned from the official Docker Hub tag metadata for node:22-bookworm-slim.
FROM node:22-bookworm-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da AS meshcentral-build

ARG MESHCENTRAL_VERSION=1.3.1
ARG MESHCENTRAL_INTEGRITY=sha512-adf9wyq+78RK6HTRNlF0OO9sPs6dzDaYlScaHRqs2w8xefm/QmS/zdrwuV2UuWWiOqh1RWPxgGNVwF/qvjL0Xg==

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    curl -fsSL --retry 3 --proto '=https' --tlsv1.2 \
      -o /tmp/meshcentral.tgz \
      "https://registry.npmjs.org/meshcentral/-/meshcentral-${MESHCENTRAL_VERSION}.tgz"; \
    MC_INTEGRITY="$MESHCENTRAL_INTEGRITY" node -e "const fs=require('fs'),c=require('crypto');const d='sha512-'+c.createHash('sha512').update(fs.readFileSync('/tmp/meshcentral.tgz')).digest('base64');if(d!==process.env.MC_INTEGRITY)throw new Error('MeshCentral tarball integrity mismatch')"

WORKDIR /opt/meshcentral
RUN npm install --omit=dev --ignore-scripts --no-audit --no-fund /tmp/meshcentral.tgz \
    && npm install --omit=dev --ignore-scripts --no-audit --no-fund ssh2@1.17.0 \
    && node -e "const p=require('/opt/meshcentral/node_modules/meshcentral/package.json');if(p.version!=='1.3.1')throw new Error('Unexpected MeshCentral version')" \
    && npm cache clean --force \
    && rm -f /tmp/meshcentral.tgz

# Pinned from the same official tag metadata. TLS keys are generated only into
# the runtime named volume mounted at /data.
FROM node:22-bookworm-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da AS server

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates gosu tini \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/meshcentral
COPY --from=meshcentral-build /opt/meshcentral/node_modules /opt/meshcentral/node_modules
COPY server-entry.cjs smoke.cjs /opt/meshpilot/

RUN mkdir -p /data /agent-config \
    && chown -R node:node /data /agent-config \
    && printf '%s\n' \
       '#!/bin/sh' \
       'set -eu' \
       'chown -R node:node /data /agent-config' \
       'exec gosu node node /opt/meshpilot/server-entry.cjs' \
       > /usr/local/bin/meshpilot-server-start \
    && chmod 0755 /usr/local/bin/meshpilot-server-start

EXPOSE 8443
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/meshpilot-server-start"]

# The target is amd64-only because the upstream legacy Linux agent is x86-64.
FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3 AS agent-download

ARG AGENT_SHA384=1bf04d9ced5c4be006d1ed714da04048d26e8f3ffdd02f39b69a73b6d73da98682dda7401ef33f04c621549aff6c3a28
ARG AGENT_SIZE=3757520

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl coreutils \
    && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    curl -fsSL --retry 3 --proto '=https' --tlsv1.2 \
      -o /tmp/meshagent \
      'https://github.com/Ylianst/MeshAgent/releases/download/legacy-1.2.6/meshagent_x86-64'; \
    test "$(stat -c%s /tmp/meshagent)" = "$AGENT_SIZE"; \
    echo "$AGENT_SHA384  /tmp/meshagent" | sha384sum -c -; \
    install -d -m 0755 /out; \
    install -m 0755 /tmp/meshagent /out/meshagent; \
    rm -f /tmp/meshagent

FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3 AS target

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates \
       fontconfig \
       fonts-dejavu-core \
       libcurl4 \
       libgl1 \
       libssl3 \
       libstdc++6 \
       libx11-6 \
       libxcursor1 \
       libxext6 \
       libxfixes3 \
       libxi6 \
       libxinerama1 \
       libxrandr2 \
       libxrender1 \
       libxtst6 \
       openbox \
       openssh-server \
       procps \
       tini \
       util-linux \
       x11-utils \
       xauth \
       xfonts-base \
       xterm \
       xvfb \
       zlib1g \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    useradd --create-home --uid 1000 --shell /bin/bash sandbox; \
    passwd -l root; \
    install -d -m 0700 -o sandbox -g sandbox \
      /home/sandbox/agent /home/sandbox/fixture /home/sandbox/.config/openbox; \
    printf '%s\n' 'MeshPilot synthetic fixture.' > /home/sandbox/fixture/hello.txt; \
    chown sandbox:sandbox /home/sandbox/fixture/hello.txt; \
    chmod 0644 /home/sandbox/fixture/hello.txt; \
    rm -f /etc/ssh/ssh_host_*; \
    install -d -m 0755 /run/sshd; \
    install -d -m 0700 /run/meshpilot-ssh; \
    printf '%s\n' \
      'Port 22' \
      'HostKey /run/meshpilot-ssh/ssh_host_ed25519_key' \
      'PidFile /run/sshd.pid' \
      'PermitRootLogin no' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication no' \
      'KbdInteractiveAuthentication no' \
      'PermitEmptyPasswords no' \
      'UsePAM yes' \
      'AllowUsers sandbox' \
       'Subsystem sftp internal-sftp' \
      'X11Forwarding no' \
      'AllowTcpForwarding no' \
      'PermitTunnel no' \
      'GatewayPorts no' \
      'PermitUserEnvironment no' \
      'UseDNS no' \
      'MaxAuthTries 3' \
      'LoginGraceTime 30' \
      'ClientAliveInterval 30' \
      'ClientAliveCountMax 2' \
      'PrintMotd no' \
      'LogLevel INFO' \
      > /etc/ssh/sshd_config; \
    chmod 0644 /etc/ssh/sshd_config

COPY --from=agent-download /out/meshagent /opt/meshpilot/meshagent
COPY target-entry.sh /usr/local/bin/meshpilot-target-entry
RUN chmod 0755 /opt/meshpilot/meshagent /usr/local/bin/meshpilot-target-entry

ENV DISPLAY=:99
EXPOSE 22
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/meshpilot-target-entry"]
