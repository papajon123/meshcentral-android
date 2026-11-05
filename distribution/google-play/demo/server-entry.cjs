'use strict';

// Offline MeshCentral 1.3.1 fixture bootstrap. Passwords are used only to
// create upstream pass.js hashes in the initial NeDB file and are never logged.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const DATA_DIR = process.env.MESHPILOT_DATA_DIR || '/data';
const AGENT_CONFIG_DIR = process.env.MESHPILOT_AGENT_CONFIG_DIR || '/agent-config';
const MESHCENTRAL_HOME = process.env.MESHPILOT_MESHCENTRAL_HOME
  || '/opt/meshcentral/node_modules/meshcentral';
const DB_PATH = path.join(DATA_DIR, 'meshcentral.db');
const CONFIG_PATH = path.join(DATA_DIR, 'config.json');
const MARKER_PATH = path.join(DATA_DIR, '.meshpilot-demo-owner.json');
const AGENT_MSH_PATH = path.join(AGENT_CONFIG_DIR, 'meshagent.msh');

const PRODUCT = 'meshpilot-review-demo';
const GROUP_NAME = 'MeshPilot sandbox';
const MESHCENTRAL_VERSION = '1.3.1';
const ADMIN_RIGHTS = 4294967295;
const REVIEWER_RIGHTS = 8;

function fail(message) {
  process.stderr.write(message + '\n');
  process.exit(1);
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function b64(bytes) {
  return bytes.toString('base64').replace(/\+/g, '@').replace(/\//g, '$').replace(/=+$/g, '');
}

function hashPassword(pass, password) {
  return new Promise((resolve, reject) => {
    pass.hash(password, (err, salt, hash) => {
      if (err || typeof salt !== 'string' || typeof hash !== 'string') {
        reject(new Error('Upstream password hashing failed.'));
        return;
      }
      resolve({ salt, hash });
    });
  });
}

function configDocument() {
  return {
    settings: {
      cert: '10.0.2.2',
      port: 8443,
      exactPorts: true,
      redirPort: 0,
      mpsPort: 0,
      WANonly: true,
      filesPath: path.join(DATA_DIR, 'files'),
      autoBackup: { backupPath: path.join(DATA_DIR, 'backups') },
      agentDownloads: { enabled: false },
      selfUpdate: false,
      agentUpdates: false,
      agentCoreDump: false,
      webrtc: false
    },
    domains: {
      '': {
        title: 'MeshPilot demo',
        newAccounts: false,
        minify: false,
        // Confirmed MeshCentral 1.3.1 domain-level SSH feature flag.
        ssh: true,
        allowedOrigin: ['localhost', '127.0.0.1', '10.0.2.2']
      }
    }
  };
}

async function seed(pass) {
  const adminPassword = process.env.MESH_DEMO_ADMIN_PASSWORD || '';
  const reviewerPassword = process.env.MESH_DEMO_REVIEW_PASSWORD || '';
  if (!adminPassword || !reviewerPassword) {
    throw new Error('Required demo password environment variables are missing.');
  }
  if (adminPassword === reviewerPassword) {
    throw new Error('Admin and reviewer demo passwords must differ.');
  }

  const adminHash = await hashPassword(pass, adminPassword);
  const reviewerHash = await hashPassword(pass, reviewerPassword);
  const meshId = 'mesh//' + b64(crypto.randomBytes(48));
  const nowSeconds = Math.floor(Date.now() / 1000);
  const nowMillis = Date.now();

  const group = {
    type: 'mesh',
    _id: meshId,
    name: GROUP_NAME,
    mtype: 2,
    desc: 'Synthetic isolated demo target',
    domain: '',
    links: {},
    creation: nowMillis,
    creatorid: 'user//admin',
    creatorname: 'admin'
  };
  const admin = {
    _id: 'user//admin',
    type: 'user',
    name: 'admin',
    domain: '',
    creation: nowSeconds,
    links: {},
    salt: adminHash.salt,
    hash: adminHash.hash,
    siteadmin: ADMIN_RIGHTS
  };
  const reviewer = {
    _id: 'user//reviewer',
    type: 'user',
    name: 'reviewer',
    domain: '',
    creation: nowSeconds,
    links: {},
    salt: reviewerHash.salt,
    hash: reviewerHash.hash,
    siteadmin: 0
  };

  group.links[admin._id] = { name: admin.name, rights: ADMIN_RIGHTS };
  group.links[reviewer._id] = { name: reviewer.name, rights: REVIEWER_RIGHTS };
  admin.links[meshId] = { name: GROUP_NAME, rights: ADMIN_RIGHTS };
  reviewer.links[meshId] = { name: GROUP_NAME, rights: REVIEWER_RIGHTS };

  const state = {
    format: 1,
    product: PRODUCT,
    meshcentral: MESHCENTRAL_VERSION,
    groupName: GROUP_NAME,
    meshId
  };

  const records = [admin, reviewer, group].map(record => JSON.stringify(record)).join('\n') + '\n';
  try {
    fs.writeFileSync(DB_PATH, records, { mode: 0o600, flag: 'wx' });
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(configDocument(), null, 2) + '\n', {
      mode: 0o644,
      flag: 'wx'
    });
    fs.writeFileSync(MARKER_PATH, JSON.stringify(state, null, 2) + '\n', {
      mode: 0o600,
      flag: 'wx'
    });
  } catch {
    throw new Error('Could not create initial demo state; refusing to adopt partial state.');
  }

  delete process.env.MESH_DEMO_ADMIN_PASSWORD;
  delete process.env.MESH_DEMO_REVIEW_PASSWORD;
  return state;
}

function loadExistingState() {
  const markerExists = fs.existsSync(MARKER_PATH);
  if (!markerExists) {
    const entries = fs.readdirSync(DATA_DIR).filter(name => name !== '.' && name !== '..');
    if (entries.length !== 0) {
      throw new Error('Existing data has no demo ownership marker; refusing to adopt it.');
    }
    return null;
  }

  let state;
  try {
    state = readJson(MARKER_PATH);
  } catch {
    throw new Error('Demo ownership marker is unreadable; refusing to adopt the database.');
  }
  if (state.format !== 1 || state.product !== PRODUCT
      || state.meshcentral !== MESHCENTRAL_VERSION || state.groupName !== GROUP_NAME
      || typeof state.meshId !== 'string' || !state.meshId.startsWith('mesh//')) {
    throw new Error('Demo ownership marker does not match this fixture; refusing to adopt the database.');
  }
  if (!fs.existsSync(DB_PATH) || !fs.existsSync(CONFIG_PATH)) {
    throw new Error('Demo ownership marker exists but the database or config is missing.');
  }

  let records;
  try {
    records = fs.readFileSync(DB_PATH, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map(line => JSON.parse(line));
  } catch {
    throw new Error('Existing demo database is unreadable; refusing to modify it.');
  }
  const group = records.find(record => record && record._id === state.meshId && record.type === 'mesh');
  if (!group || group.name !== GROUP_NAME) {
    throw new Error('Existing database does not contain the owned demo group; refusing to adopt it.');
  }
  return state;
}

function meshHex(state) {
  const encoded = state.meshId.slice('mesh//'.length).replace(/@/g, '+').replace(/\$/g, '/');
  const hex = Buffer.from(encoded, 'base64').toString('hex');
  if (!/^[0-9a-f]{96}$/.test(hex)) {
    throw new Error('Owned demo mesh identifier is malformed.');
  }
  return hex;
}

async function waitForCertificate(child, exitState) {
  const certPath = path.join(DATA_DIR, 'agentserver-cert-public.crt');
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    if (exitState.exited) {
      throw new Error('MeshCentral exited before publishing the agent certificate.');
    }
    if (fs.existsSync(certPath)) {
      const cert = fs.readFileSync(certPath, 'utf8');
      if (cert.trim()) return cert;
    }
    await sleep(250);
  }
  throw new Error('MeshCentral did not generate the agent certificate in time.');
}

function publishAgentConfig(state, cert) {
  const ops = require(path.join(MESHCENTRAL_HOME, 'certoperations.js')).CertificateOperations();
  const serverId = String(ops.getPublicKeyHash(cert) || '').toUpperCase();
  if (!/^[0-9A-F]{96}$/.test(serverId)) {
    throw new Error('Could not derive the MeshCentral agent server identity.');
  }

  const msh = [
    `MeshName=${state.groupName}`,
    'MeshType=2',
    `MeshID=0x${meshHex(state)}`,
    `ServerID=${serverId}`,
    'MeshServer=wss://server:8443/agent.ashx',
    ''
  ].join('\n');

  fs.mkdirSync(AGENT_CONFIG_DIR, { recursive: true, mode: 0o700 });
  const temporary = path.join(AGENT_CONFIG_DIR, `.meshagent.msh.${process.pid}.tmp`);
  fs.writeFileSync(temporary, msh, { mode: 0o600, flag: 'wx' });
  fs.chmodSync(temporary, 0o600);
  fs.renameSync(temporary, AGENT_MSH_PATH);
}

async function main() {
  process.umask(0o077);
  fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  fs.mkdirSync(AGENT_CONFIG_DIR, { recursive: true, mode: 0o700 });

  const pass = require(path.join(MESHCENTRAL_HOME, 'pass.js'));
  const state = loadExistingState() || await seed(pass);

  const childEnv = { ...process.env };
  delete childEnv.MESH_DEMO_ADMIN_PASSWORD;
  delete childEnv.MESH_DEMO_REVIEW_PASSWORD;

  const child = spawn(
    process.execPath,
    [path.join(MESHCENTRAL_HOME, 'meshcentral.js'), '--datapath=' + DATA_DIR, '--launch'],
    {
      cwd: '/opt/meshcentral',
      env: childEnv,
      // MeshCentral --launch exits when stdin closes. Docker's inherited stdin is
      // normally already at EOF; keep the child pipe open for this parent's lifetime.
      stdio: ['pipe', 'inherit', 'inherit']
    }
  );

  const exitState = { exited: false, code: 1 };
  child.on('exit', (code, signal) => {
    exitState.exited = true;
    exitState.code = code === null ? 1 : code;
    if (signal) exitState.code = 1;
  });

  try {
    const cert = await waitForCertificate(child, exitState);
    publishAgentConfig(state, cert);
    process.stderr.write('MeshPilot demo agent configuration published.\n');
  } catch (error) {
    child.kill('SIGTERM');
    throw error;
  }

  for (const signal of ['SIGTERM', 'SIGINT']) {
    process.on(signal, () => {
      if (!exitState.exited) child.kill(signal);
    });
  }

  await new Promise(resolve => {
    if (exitState.exited) {
      resolve();
      return;
    }
    child.once('exit', resolve);
  });
  process.exit(exitState.code);
}

main().catch(error => {
  // Only fixed, non-secret diagnostics are printed.
  process.stderr.write(String(error && error.message ? error.message : 'Demo server setup failed.') + '\n');
  process.exit(1);
});
