'use strict';

// Sanitized control-channel smoke test. Run inside the server container:
//   node /opt/meshpilot/smoke.cjs
// It prints exactly one JSON object and no credentials, cookies, node IDs,
// enrollment data, or protocol frames.

const path = require('path');
const { createRequire } = require('module');
const MESHCENTRAL_HOME = process.env.MESHPILOT_MESHCENTRAL_HOME
  || '/opt/meshcentral/node_modules/meshcentral';
const meshcentralRequire = createRequire(path.join(MESHCENTRAL_HOME, 'package.json'));
const WebSocket = meshcentralRequire('ws');

const CONTROL_URL = 'wss://127.0.0.1:8443/control.ashx';
const GROUP_NAME = 'MeshPilot sandbox';
const ADMIN_PASSWORD = process.env.MESH_DEMO_ADMIN_PASSWORD || '';
const REVIEWER_PASSWORD = process.env.MESH_DEMO_REVIEW_PASSWORD || '';

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function authHeader(username, password) {
  return [
    Buffer.from(username, 'utf8').toString('base64'),
    Buffer.from(password, 'utf8').toString('base64')
  ].join(',');
}

function probe(username, password) {
  return new Promise(resolve => {
    const result = {
      authenticated: false,
      siteAdmin: false,
      groups: [],
      onlineAgents: 0,
      timedOut: false
    };
    let settled = false;
    let meshesSeen = false;
    let nodesSeen = false;
    let ws;

    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        if (ws && ws.readyState === WebSocket.OPEN) ws.close();
        else if (ws) ws.terminate();
      } catch {
        // No diagnostic, to avoid leaking handshake material.
      }
      resolve(result);
    };

    const maybeFinish = () => {
      if (meshesSeen && nodesSeen) finish();
    };

    const timer = setTimeout(() => {
      result.timedOut = true;
      try { if (ws) ws.terminate(); } catch { /* ignore */ }
      finish();
    }, 12000);

    try {
      ws = new WebSocket(CONTROL_URL, {
        rejectUnauthorized: false,
        headers: { 'x-meshauth': authHeader(username, password) }
      });
    } catch {
      finish();
      return;
    }

    ws.on('message', raw => {
      let message;
      try {
        message = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (!message || typeof message.action !== 'string') return;

      if (message.action === 'close') {
        finish();
        return;
      }
      if (message.action === 'userinfo') {
        result.authenticated = true;
        result.siteAdmin = Boolean(message.userinfo && message.userinfo.siteadmin);
        try {
          ws.send(JSON.stringify({ action: 'meshes' }));
          ws.send(JSON.stringify({ action: 'nodes' }));
        } catch {
          finish();
        }
        return;
      }
      if (message.action === 'meshes') {
        result.groups = Array.isArray(message.meshes)
          ? message.meshes.map(mesh => String(mesh.name)).filter(Boolean).sort()
          : [];
        meshesSeen = true;
        maybeFinish();
        return;
      }
      if (message.action === 'nodes') {
        const nodes = message.nodes && typeof message.nodes === 'object'
          ? Object.values(message.nodes).flat().filter(Boolean)
          : [];
        result.onlineAgents = nodes.filter(node => Number(node.conn) & 1).length;
        nodesSeen = true;
        maybeFinish();
      }
    });
    ws.on('error', () => finish());
    ws.on('close', () => finish());
  });
}

async function main() {
  const result = {
    ok: false,
    adminAuthenticated: false,
    reviewerAuthenticated: false,
    reviewerSiteAdmin: false,
    wrongPasswordRejected: false,
    onlineAgents: 0,
    reviewerGroups: [],
    reviewerGroupCount: 0,
    relayTested: false,
    terminalFilesRelayVerified: false,
    desktopConsentVerified: false
  };

  if (!ADMIN_PASSWORD || !REVIEWER_PASSWORD) {
    process.stdout.write(JSON.stringify(result) + '\n');
    process.exit(1);
  }

  let admin = null;
  for (let attempt = 0; attempt < 30; attempt++) {
    admin = await probe('admin', ADMIN_PASSWORD);
    if (!admin.authenticated || admin.onlineAgents === 1) break;
    await sleep(2000);
  }
  const reviewer = await probe('reviewer', REVIEWER_PASSWORD);
  const wrong = await probe('reviewer', 'intentionally-invalid-demo-password');

  result.adminAuthenticated = Boolean(admin && admin.authenticated);
  result.reviewerAuthenticated = Boolean(reviewer.authenticated);
  result.reviewerSiteAdmin = Boolean(reviewer.siteAdmin);
  result.wrongPasswordRejected = !wrong.authenticated;
  result.onlineAgents = admin ? admin.onlineAgents : 0;
  result.reviewerGroups = reviewer.groups;
  result.reviewerGroupCount = reviewer.groups.length;
  result.ok = Boolean(
    result.adminAuthenticated
    && result.reviewerAuthenticated
    && !result.reviewerSiteAdmin
    && result.wrongPasswordRejected
    && result.onlineAgents === 1
    && result.reviewerGroupCount === 1
    && result.reviewerGroups[0] === GROUP_NAME
  );

  process.stdout.write(JSON.stringify(result) + '\n');
  process.exit(result.ok ? 0 : 1);
}

main().catch(() => {
  const failed = {
    ok: false,
    adminAuthenticated: false,
    reviewerAuthenticated: false,
    reviewerSiteAdmin: false,
    wrongPasswordRejected: false,
    onlineAgents: 0,
    reviewerGroups: [],
    reviewerGroupCount: 0,
    relayTested: false,
    terminalFilesRelayVerified: false,
    desktopConsentVerified: false
  };
  process.stdout.write(JSON.stringify(failed) + '\n');
  process.exit(1);
});
