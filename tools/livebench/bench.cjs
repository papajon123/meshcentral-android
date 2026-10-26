'use strict';
// MeshCentral 1.3.1 local fixture. Never print credentials or enrollment material.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const root = path.join(process.env.LOCALAPPDATA, 'meshcentral-livebench');
const data = path.join(root, 'meshcentral-data');
const pkg = path.join(root, 'server', 'node_modules', 'meshcentral');
const read = file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8').replace(/^\uFEFF/, ''));
const write = (file, value) => fs.writeFileSync(path.join(root, file), JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
function b64(bytes) { return bytes.toString('base64').replace(/\+/g, '@').replace(/\//g, '$'); }
function totp(secret, now = Date.now()) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = ''; for (const c of secret) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g).map(b => parseInt(b, 2)));
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(Math.floor(now / 30000)));
  const hash = crypto.createHmac('sha1', key).update(counter).digest();
  return String((hash.readUInt32BE(hash[19] & 15) & 0x7fffffff) % 1000000).padStart(6, '0');
}
async function seed() {
  fs.mkdirSync(data, { recursive: true });
  if (fs.existsSync(path.join(data, 'meshcentral.db'))) {
    if (!fs.existsSync(path.join(root, 'fixture.json'))) throw Error('Existing database without fixture; refusing to adopt it.');
    console.log('Fixture already exists.'); return;
  }
  // Use the exact upstream password hashing implementation, not an invented API.
  const pass = require(path.join(pkg, 'pass.js'));
  const credentials = {};
  const users = [];
  for (const name of ['admin', 'totp-user', 'limited-user']) {
    const password = crypto.randomBytes(24).toString('base64url') + 'aA7!';
    const hashed = await new Promise((resolve, reject) => pass.hash(password, (err, salt, hash) => err ? reject(err) : resolve({salt, hash})));
    credentials[name] = { password };
    users.push({ _id: 'user//' + name, type: 'user', name, domain: '', creation: Math.floor(Date.now()/1000), links: {}, ...hashed, siteadmin: name === 'admin' ? 4294967295 : 0 });
  }
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const secret = [...crypto.randomBytes(32)].map(n => alphabet[n & 31]).join('');
  credentials['totp-user'].otpsecret = secret;
  users[1].otpsecret = secret;
  const groups = ['bench-linux', 'bench-android'].map(name => ({ type: 'mesh', _id: 'mesh//' + b64(crypto.randomBytes(48)), name, mtype: 2, desc: 'Synthetic local fixture', domain: '', links: {}, creation: Date.now(), creatorid: 'user//admin', creatorname: 'admin' }));
  for (const group of groups) {
    for (const user of users) {
      if (user.name === 'limited-user' && group.name !== 'bench-linux') continue;
      const rights = user.name === 'admin' ? 4294967295 : 8 + 16 + 32;
      group.links[user._id] = { name: user.name, rights };
      user.links[group._id] = { name: group.name, rights };
    }
  }
  write('credentials.json', credentials);
  write('fixture.json', { version: '1.3.1', groups: Object.fromEntries(groups.map(g => [g.name, g._id])) });
  fs.writeFileSync(path.join(data, 'meshcentral.db'), [...users, ...groups].map(JSON.stringify).join('\n') + '\n', { mode: 0o600, flag: 'wx' });
  write('meshcentral-data/config.json', {
    settings: { cert: '10.0.2.2', port: 4443, exactPorts: true, redirPort: 0, mpsPort: 0, WANonly: true, selfUpdate: false, agentUpdates: false, agentCoreDump: false, webrtc: false },
    domains: { '': { title: 'MeshCentral Livebench', newAccounts: false, minify: false, allowedOrigin: ['localhost', '127.0.0.1', '10.0.2.2'] } }
  });
  console.log('Created 3 users and 2 groups; secrets stored only in the private data directory.');
}
function control(username = 'admin', invalid = false, omitOtp = false) {
  const WebSocket = require(require.resolve('ws', { paths: [pkg] }));
  const account = read('credentials.json')[username];
  const auth = [username, invalid ? 'intentionally-invalid-password' : account.password];
  if (account.otpsecret && !omitOtp) auth.push(totp(account.otpsecret));
  return new WebSocket('wss://localhost:4443/control.ashx', { rejectUnauthorized: false, headers: { 'x-meshauth': auth.map(s => Buffer.from(s).toString('base64')).join(',') } });
}
async function probe(username = 'admin', invalid = false, omitOtp = false) {
  return new Promise((resolve, reject) => {
    const ws = control(username, invalid, omitOtp);
    const result = { user: username, authenticated: false, actions: [], groups: [], devices: [] };
    const timer = setTimeout(() => { result.timedOut = true; ws.terminate(); }, 15000);
    ws.on('error', () => { clearTimeout(timer); reject(Error('Local control connection failed')); });
    ws.on('message', bytes => {
      const m = JSON.parse(bytes.toString());
      if (!result.actions.includes(m.action)) result.actions.push(m.action);
      if (m.action === 'userinfo') { result.authenticated = true; ws.send(JSON.stringify({ action: 'meshes' })); ws.send(JSON.stringify({ action: 'nodes' })); }
      if (m.action === 'meshes') result.groups = m.meshes.map(g => g.name);
      if (m.action === 'nodes') {
        result.devices = Object.values(m.nodes).flat().map(n => ({ name: n.name, online: !!(n.conn & 1), agentId: n.agent?.id }));
        ws.close();
      }
      if (m.action === 'close') { result.cause = m.cause; ws.terminate(); }
    });
    ws.on('close', () => { clearTimeout(timer); resolve(result); });
  });
}
function enrollment() {
  const fixture = read('fixture.json');
  const cert = fs.readFileSync(path.join(data, 'agentserver-cert-public.crt'), 'utf8');
  const ops = require(path.join(pkg, 'certoperations.js')).CertificateOperations();
  const hash = ops.getPublicKeyHash(cert);
  const group = name => fixture.groups[name].split('/')[2];
  fs.writeFileSync(path.join(root, 'android-enrollment.txt'), `mc://10.0.2.2:4443,${b64(Buffer.from(hash, 'hex'))},${group('bench-android')}`, { mode: 0o600 });
  const gateway = process.env.BENCH_WSL_GATEWAY;
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(gateway || '')) throw Error('Missing WSL gateway');
  const meshHex = Buffer.from(group('bench-linux').replace(/@/g, '+').replace(/\$/g, '/'), 'base64').toString('hex');
  fs.writeFileSync(path.join(root, 'linux-agent.msh'), `MeshName=bench-linux\nMeshType=2\nMeshID=0x${meshHex}\nServerID=${hash.toUpperCase()}\nMeshServer=wss://${gateway}:4443/agent.ashx\n`, { mode: 0o600 });
  console.log('Prepared local agent enrollment files (contents withheld).');
}
async function main() {
  switch (process.argv[2]) {
    case 'seed': await seed(); break;
    case 'enrollment': enrollment(); break;
    case 'status': console.log(JSON.stringify(await probe(), null, 2)); break;
    case 'ready': {
      const wanted = process.argv[3] === 'linux-only' ? [6] : [6, 14];
      let result;
      for (let attempt = 0; attempt < 30; attempt++) {
        result = await probe();
        if (wanted.every(id => result.devices.some(d => d.agentId === id && d.online))) {
          console.log(JSON.stringify(result, null, 2)); return;
        }
        await new Promise(resolve => setTimeout(resolve, 2000));
      }
      console.log(JSON.stringify(result, null, 2));
      throw Error('Required local agents did not become online');
    }
    case 'verify': {
      const checks = [];
      for (const name of ['admin', 'totp-user', 'limited-user']) checks.push(await probe(name));
      checks.push(await probe('admin', true));
      checks.push(await probe('totp-user', false, true));
      write('auth-checks.json', checks);
      console.log(JSON.stringify(checks, null, 2));
      if (checks.some(r => r.timedOut) || !checks.slice(0, 3).every(r => r.authenticated) || checks.slice(3).some(r => r.authenticated) || checks[2].groups.length !== 1 || checks[2].groups[0] !== 'bench-linux') throw Error('Authentication or authorization check failed');
      break;
    }
    default: throw Error('Unknown bench command');
  }
}
if (require.main === module) main().catch(() => { console.error('Bench operation failed; no credential-bearing exception was printed.'); process.exitCode = 1; });
module.exports = { root, data, pkg, read, write, control, probe, totp };
