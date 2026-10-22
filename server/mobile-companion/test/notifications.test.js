'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const h = require('./helpers');

const DAY_MS = 24 * 60 * 60 * 1000;

function sha256(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function diagnostics(memoryUsedPercent, diskUsedPercent) {
  const memoryAvailable = Math.round(1000000 * (100 - memoryUsedPercent) / 100);
  const diskAvailable = Math.round(1000000 * (100 - diskUsedPercent) / 100);
  return [
    'HOST\thost-1',
    'OSDESC\tUbuntu 22.04.4 LTS',
    'UPTIME\t3600',
    'CPU\t4\t0.5',
    'MEM\t1000000\t' + memoryAvailable,
    'DISK\t/dev/sda1\t/\t1000000\t' + diskAvailable
  ].join('\n');
}

function setupBasic(options) {
  const harness = h.makeHarness(options);
  harness.webserver.addNode('node/d1/n1', {
    agentId: 6,
    osdesc: 'Ubuntu 22.04 LTS',
    rightsByUser: { 'user/d1/alice': 0x20000, 'user/d1/bob': 0x20000 }
  });
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.bob = h.makeClient('user/d1/bob', 'd1');
  harness.webserver.addUser(harness.alice.user);
  harness.webserver.addUser(harness.bob.user);
  harness.agent = harness.webserver.addAgent('node/d1/n1');
  return harness;
}

async function register(harness, client) {
  const response = await harness.registerNotifications(client || harness.alice);
  assert.equal(response.ok, true, JSON.stringify(response.error));
  return response.data;
}

async function completeDiagnostics(harness, client, nodeid, payload) {
  const started = await harness.startJob(client || harness.alice, nodeid || 'node/d1/n1', 'diagnostics');
  assert.equal(started.ok, true, JSON.stringify(started.error));
  const agent = harness.webserver.wsagents[nodeid || 'node/d1/n1'];
  await h.completeJob(harness.plugin, agent, started.data.job.id, payload);
  return started.data.job.id;
}

async function http(harness, options) {
  const opts = Object.assign({
    token: options && options.token,
    domain: 'd1',
    url: '/d1/mobilecompanion/events'
  }, options || {});
  return harness.httpEvents(opts);
}

test('registration returns a token once and persists only a SHA-256 hash with metadata', async function () {
  const harness = h.makeHarness();
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.webserver.addUser(harness.alice.user);

  const registered = await register(harness);
  assert.match(registered.token, /^[A-Za-z0-9_-]{43}$/);
  assert.match(registered.tokenId, /^mcn_[0-9a-f]{16}$/);
  assert.equal(registered.expiresAt, harness.clock.now + (30 * DAY_MS));
  assert.equal(registered.endpoint, '/d1/mobilecompanion/events');
  assert.equal(registered.endpoint.startsWith('/'), true);
  assert.equal(registered.endpoint.includes('://'), false);
  assert.equal(registered.endpoint.includes(registered.token), false);

  const state = harness.db.snapshot('mobilecompanion_state');
  assert.equal(state.tokens.length, 1);
  const stored = state.tokens[0];
  assert.deepEqual(Object.keys(stored).sort(), ['domain', 'expiresAt', 'hash', 'id', 'owner']);
  assert.equal(stored.hash, sha256(registered.token));
  assert.equal(stored.id, registered.tokenId);
  assert.equal(stored.owner, 'user/d1/alice');
  assert.equal(stored.domain, 'd1');
  assert.equal(JSON.stringify(state).includes(registered.token), false);

  const second = await register(harness);
  assert.notEqual(second.token, registered.token);
  assert.equal(harness.db.snapshot('mobilecompanion_state').tokens.length, 2);
});

test('HTTP bearer events use the shared events handler with fresh device ACL', async function () {
  const harness = setupBasic();
  await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(50, 60));
  const registered = await register(harness);

  const sentBefore = harness.agent.sent.length;
  const response = await harness.httpEvents({
    token: registered.token,
    domain: 'd1',
    url: registered.endpoint
  });
  assert.equal(response.statusCode, 200);
  assert.equal(response.headers['cache-control'], 'no-store');
  assert.equal(Object.prototype.hasOwnProperty.call(response.headers, 'access-control-allow-origin'), false);
  assert.equal(response.json.items.some(function (item) { return item.kind === 'job-completed'; }), true);
  assert.equal(harness.agent.sent.length, sentBefore, 'HTTP must never dispatch runcommands');

  harness.webserver.nodes['node/d1/n1'].hiddenFor.add('user/d1/alice');
  const revoked = await harness.httpEvents({ token: registered.token, domain: 'd1' });
  assert.equal(revoked.statusCode, 200);
  assert.equal(revoked.json.items.some(function (item) { return item.kind === 'job-completed'; }), false);

  harness.webserver.nodes['node/d1/n1'].hiddenFor.delete('user/d1/alice');
  const restored = await harness.httpEvents({ token: registered.token, domain: 'd1' });
  assert.equal(restored.json.items.some(function (item) { return item.kind === 'job-completed'; }), true);
});

test('foreign, revoked, expired, disabled, deleted and wrong-domain tokens get the same 401', async function () {
  const harness = setupBasic();

  const first = await register(harness);
  const foreignRevoke = await harness.revokeNotifications(harness.bob, first.tokenId);
  assert.equal(foreignRevoke.ok, false);
  assert.equal(foreignRevoke.error.code, 'NOTIFICATION_NOT_FOUND');
  assert.equal((await http(harness, { token: first.token })).statusCode, 200);

  const revoked = await harness.revokeNotifications(harness.alice, first.tokenId);
  assert.equal(revoked.ok, true);
  const revokedResponse = await http(harness, { token: first.token });
  assert.equal(revokedResponse.statusCode, 401);

  const expired = await register(harness);
  harness.clock.now += (31 * DAY_MS);
  const expiredResponse = await http(harness, { token: expired.token });
  assert.equal(expiredResponse.statusCode, 401);

  const disabled = await register(harness);
  harness.alice.user.siteadmin = 32;
  const disabledResponse = await http(harness, { token: disabled.token });
  assert.equal(disabledResponse.statusCode, 401);
  delete harness.alice.user.siteadmin;

  const deleted = await register(harness);
  delete harness.webserver.users['user/d1/alice'];
  const deletedResponse = await http(harness, { token: deleted.token });
  assert.equal(deletedResponse.statusCode, 401);
  harness.webserver.addUser(harness.alice.user);

  const wrongDomainUser = await register(harness);
  harness.alice.user.domain = 'd2';
  const wrongUserDomain = await http(harness, { token: wrongDomainUser.token });
  assert.equal(wrongUserDomain.statusCode, 401);
  harness.alice.user.domain = 'd1';

  const wrongRequestDomain = await http(harness, { token: wrongDomainUser.token, domain: 'd2' });
  assert.equal(wrongRequestDomain.statusCode, 401);

  const unknown = await http(harness, { token: 'A'.repeat(43) });
  assert.equal(unknown.statusCode, 401);

  for (const response of [revokedResponse, expiredResponse, disabledResponse, deletedResponse, wrongUserDomain, wrongRequestDomain, unknown]) {
    assert.equal(response.body, '{"error":"unauthorized"}');
    assert.equal(response.headers['cache-control'], 'no-store');
    assert.equal(Object.prototype.hasOwnProperty.call(response.headers, 'access-control-allow-origin'), false);
  }
});

test('agent contexts cannot register notification tokens', async function () {
  const harness = h.makeHarness();
  let agentSends = 0;
  const agentContext = {
    dbNodeKey: 'node/d1/n1',
    authenticated: 2,
    agentInfo: {},
    send: function () { agentSends++; }
  };
  await harness.plugin.serveraction({
    action: 'plugin',
    plugin: 'mobilecompanion',
    op: 'registerNotifications',
    requestId: 'agent-register',
    userid: 'user/d1/alice'
  }, agentContext, harness.webserver);
  await h.settle();
  assert.equal(agentSends, 0);
  assert.equal(harness.db.setCount, 0);
  assert.equal(harness.db.snapshot('mobilecompanion_state'), null);
});

test('cookies, query tokens, unknown query fields and plaintext requests are rejected', async function () {
  const harness = setupBasic();
  const registered = await register(harness);

  const cookieOnly = await harness.httpEvents({
    domain: 'd1',
    headers: { cookie: 'xid=session-cookie', authorization: undefined }
  });
  assert.equal(cookieOnly.statusCode, 401);

  const queryToken = await harness.httpEvents({
    domain: 'd1',
    url: '/d1/mobilecompanion/events?access_token=' + registered.token
  });
  assert.equal(queryToken.statusCode, 400);

  const ownerQuery = await harness.httpEvents({ domain: 'd1', url: '/d1/mobilecompanion/events?owner=user/d1/bob' });
  assert.equal(ownerQuery.statusCode, 400);

  for (const query of ['since=-1', 'limit=0', 'limit=101', 'since=1.5', 'since=1&since=2', 'limit=abc', 'extra=1']) {
    const response = await harness.httpEvents({
      token: registered.token,
      domain: 'd1',
      url: '/d1/mobilecompanion/events?' + query
    });
    assert.equal(response.statusCode, 400, query);
  }

  const queryAndHeader = await harness.httpEvents({
    token: registered.token,
    domain: 'd1',
    url: '/d1/mobilecompanion/events?access_token=' + registered.token
  });
  assert.equal(queryAndHeader.statusCode, 400);

  const insecure = await harness.httpEvents({ token: registered.token, domain: 'd1', secure: false });
  assert.equal(insecure.statusCode, 401);
  assert.equal(insecure.body, '{"error":"unauthorized"}');

  const rawForwardedHeader = await harness.httpEvents({
    token: registered.token,
    domain: 'd1',
    secure: false,
    headers: { 'x-forwarded-proto': 'https' }
  });
  assert.equal(rawForwardedHeader.statusCode, 401, 'raw forwarded headers are not trusted by default');

  harness.webserver.args = { tlsoffload: true };
  const trustedOffload = await harness.httpEvents({
    token: registered.token,
    domain: 'd1',
    secure: false,
    headers: { 'x-forwarded-proto': 'https' }
  });
  assert.equal(trustedOffload.statusCode, 200);
  delete harness.webserver.args;

  harness.webserver.tlsServer = {};
  const localTls = await harness.httpEvents({ token: registered.token, domain: 'd1', secure: false });
  assert.equal(localTls.statusCode, 200, 'a local tlsServer means the main app is TLS');
  delete harness.webserver.tlsServer;

  const post = await harness.httpEvents({ token: registered.token, domain: 'd1', method: 'POST' });
  assert.equal(post.statusCode, 405);
  assert.equal(post.headers.allow, 'GET');

  const bearerAndCookie = await harness.httpEvents({
    token: registered.token,
    domain: 'd1',
    headers: { cookie: 'xid=ignored' }
  });
  assert.equal(bearerAndCookie.statusCode, 200);
  assert.equal(bearerAndCookie.headers['cache-control'], 'no-store');
  assert.equal(Object.prototype.hasOwnProperty.call(bearerAndCookie.headers, 'access-control-allow-origin'), false);
});

test('per-token rate is 20/min and the rate map is bounded and cleaned', async function () {
  const harness = h.makeHarness();
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.webserver.addUser(harness.alice.user);
  const registered = await register(harness);

  for (let i = 0; i < 20; i++) {
    const response = await http(harness, { token: registered.token });
    assert.equal(response.statusCode, 200, 'request ' + i);
  }
  const limited = await http(harness, { token: registered.token });
  assert.equal(limited.statusCode, 429);
  assert.equal(limited.headers['retry-after'], '60');
  assert.equal(limited.headers['cache-control'], 'no-store');

  harness.clock.now += 60 * 1000;
  assert.equal((await http(harness, { token: registered.token })).statusCode, 200);

  for (let i = 0; i < 600; i++) harness.plugin._httpRateAllowed('rate-id-' + i);
  assert.ok(harness.plugin._httpRateSize() <= 512);
  harness.clock.now += 2 * 60 * 1000;
  harness.plugin._httpRateAllowed('fresh-id');
  assert.equal(harness.plugin._httpRateSize(), 1);
});

test('global inflight is limited to 20 and no HTTP call dispatches a command', async function () {
  const harness = h.makeHarness();
  const tokens = [];
  for (let userIndex = 0; userIndex < 5; userIndex++) {
    const userid = 'user/d1/u' + userIndex;
    const client = h.makeClient(userid, 'd1');
    harness.webserver.addUser(client.user);
    for (let tokenIndex = 0; tokenIndex < 5; tokenIndex++) {
      tokens.push((await register(harness, client)).token);
    }
  }
  harness.webserver.addNode('node/d1/shared', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  await harness.main.dispatchEvent({
    etype: 'node', action: 'nodeconnect', nodeid: 'node/d1/shared', conn: 1, domain: 'd1'
  });

  harness.webserver.holdNodes = true;
  const pending = [];
  for (let i = 0; i < 20; i++) {
    pending.push(harness.httpEvents({ token: tokens[i], domain: 'd1' }));
  }
  for (let i = 0; i < 100 && harness.webserver.heldNodes.length < 20; i++) await h.settle();
  assert.equal(harness.webserver.heldNodes.length, 20);

  const busy = await harness.httpEvents({ token: tokens[20], domain: 'd1' });
  assert.equal(busy.statusCode, 429);
  assert.equal(busy.body, '{"error":"busy"}');

  harness.webserver.releaseHeldNodes();
  const finished = await Promise.all(pending);
  assert.equal(finished.every(function (response) { return response.statusCode === 200; }), true);
  assert.equal(harness.plugin._httpInflight(), 0);
});

test('restart keeps a token valid until its persisted TTL', async function () {
  const first = h.makeHarness();
  first.alice = h.makeClient('user/d1/alice', 'd1');
  first.webserver.addUser(first.alice.user);
  const registered = await register(first);
  const snapshot = first.db.snapshot('mobilecompanion_state');

  const restarted = h.makeHarness({ state: snapshot, now: first.clock.now });
  restarted.alice = h.makeClient('user/d1/alice', 'd1');
  restarted.webserver.addUser(restarted.alice.user);
  const accepted = await http(restarted, { token: registered.token });
  assert.equal(accepted.statusCode, 200);
  assert.equal(restarted.db.snapshot('mobilecompanion_state').tokens.length, 1);

  restarted.clock.now += (30 * DAY_MS) - 1;
  assert.equal((await http(restarted, { token: registered.token })).statusCode, 200);
  restarted.clock.now += 2;
  assert.equal((await http(restarted, { token: registered.token })).statusCode, 401);
});

test('revocation is owner-only and the five-token per-user quota is enforced', async function () {
  const harness = h.makeHarness();
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.bob = h.makeClient('user/d1/bob', 'd1');
  harness.webserver.addUser(harness.alice.user);
  harness.webserver.addUser(harness.bob.user);

  const tokens = [];
  for (let i = 0; i < 5; i++) tokens.push(await register(harness));
  const sixth = await harness.registerNotifications(harness.alice);
  assert.equal(sixth.ok, false);
  assert.equal(sixth.error.code, 'TOKEN_QUOTA_EXCEEDED');
  assert.equal(harness.db.snapshot('mobilecompanion_state').tokens.length, 5);

  const foreign = await harness.revokeNotifications(harness.bob, tokens[0].tokenId);
  assert.equal(foreign.ok, false);
  assert.equal(foreign.error.code, 'NOTIFICATION_NOT_FOUND');
  assert.equal((await http(harness, { token: tokens[0].token })).statusCode, 200);

  const revoked = await harness.revokeNotifications(harness.alice, tokens[0].tokenId);
  assert.equal(revoked.ok, true);
  assert.equal((await http(harness, { token: tokens[0].token })).statusCode, 401);
  const again = await harness.revokeNotifications(harness.alice, tokens[0].tokenId);
  assert.equal(again.ok, false);
  assert.equal(again.error.code, 'NOTIFICATION_NOT_FOUND');

  const replacement = await register(harness);
  assert.notEqual(replacement.tokenId, tokens[0].tokenId);
  const invalid = await harness.revokeNotifications(harness.alice, '');
  assert.equal(invalid.ok, false);
  assert.equal(invalid.error.code, 'INVALID_ARGUMENT');
});

test('deleteJob checks owner, current rights and terminal status, then removes linked events', async function () {
  const harness = setupBasic();

  const completed = await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(50, 60));
  const foreign = await harness.deleteJob(harness.bob, completed);
  assert.equal(foreign.ok, false);
  assert.equal(foreign.error.code, 'JOB_NOT_FOUND');

  const running = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(running.data.job.status, 'running');
  const active = await harness.deleteJob(harness.alice, running.data.job.id);
  assert.equal(active.ok, false);
  assert.equal(active.error.code, 'JOB_ACTIVE');
  await h.completeJob(harness.plugin, harness.agent, running.data.job.id, diagnostics(50, 60));

  const deleted = await harness.deleteJob(harness.alice, running.data.job.id);
  assert.equal(deleted.ok, true);
  assert.equal(deleted.data.deleted, true);
  assert.equal(deleted.data.jobId, running.data.job.id);
  const state = harness.db.snapshot('mobilecompanion_state');
  assert.equal(state.jobs.some(function (job) { return job.id === running.data.job.id; }), false);
  assert.equal(state.events.some(function (event) { return event.jobId === running.data.job.id; }), false);
  const gone = await harness.getJob(harness.alice, running.data.job.id);
  assert.equal(gone.error.code, 'JOB_NOT_FOUND');

  const rightsChecked = await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(50, 60));
  harness.webserver.nodes['node/d1/n1'].hiddenFor.add('user/d1/alice');
  const noRights = await harness.deleteJob(harness.alice, rightsChecked);
  assert.equal(noRights.ok, false);
  assert.equal(noRights.error.code, 'JOB_NOT_FOUND');
  assert.equal(harness.db.snapshot('mobilecompanion_state').jobs.some(function (job) {
    return job.id === rightsChecked;
  }), true);
});

test('device-alert is emitted only for successful diagnostics above server thresholds and deduped for one hour', async function () {
  const harness = setupBasic();
  async function alerts() {
    const response = await harness.events(harness.alice, { since: 0, limit: 100 });
    assert.equal(response.ok, true);
    return response.data.items.filter(function (item) { return item.kind === 'device-alert'; });
  }

  await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(95, 89));
  let items = await alerts();
  assert.equal(items.length, 1);
  assert.equal(items[0].metric, 'memory');
  assert.equal(items[0].direction, 'high');
  assert.deepEqual(Object.keys(items[0]).sort(), ['direction', 'expiresAt', 'id', 'kind', 'metric', 'nodeid', 'sequence', 'time']);
  assert.equal(JSON.stringify(items[0]).includes('usedPercent'), false);

  await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(96, 89));
  assert.equal((await alerts()).length, 1, 'memory alert must be deduped for one hour');

  await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(50, 90));
  items = await alerts();
  assert.equal(items.filter(function (item) { return item.metric === 'disk'; }).length, 1);
  assert.equal(items.filter(function (item) { return item.metric === 'memory'; }).length, 1);

  await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(94, 89));
  items = await alerts();
  assert.equal(items.length, 2, 'below-threshold diagnostics must not alert');

  harness.clock.now += (60 * 60 * 1000) + 1;
  await completeDiagnostics(harness, harness.alice, 'node/d1/n1', diagnostics(99, 50));
  items = await alerts();
  assert.equal(items.filter(function (item) { return item.metric === 'memory'; }).length, 2);
});

test('capabilities documents the new operations and honest server thresholds without push', async function () {
  const harness = setupBasic();
  const response = await h.request(harness.plugin, harness.alice, harness.webserver, { op: 'capabilities' });
  assert.equal(response.ok, true);
  const data = response.data;
  for (const op of ['registerNotifications', 'revokeNotifications', 'deleteJob', 'events']) {
    assert.equal(data.operations.includes(op), true, op);
  }
  assert.equal(data.events.includes('device-alert'), true);
  assert.equal(data.alerts.diskUsedPercent, 90);
  assert.equal(data.alerts.memoryUsedPercent, 95);
  assert.equal(data.alerts.dedupeSeconds, 3600);
  assert.equal(data.alerts.push, false);
  assert.equal(data.notifications.scope, 'events-read-only');
  assert.equal(data.notifications.method, 'GET');
  assert.equal(data.notifications.auth, 'Authorization: Bearer');
  assert.equal(data.notifications.push, false);
  assert.equal(data.notifications.tokenTtlDays, 30);
  assert.equal(data.notifications.maxTokensPerUser, 5);
  assert.equal(data.notifications.maxTokensGlobal, 200);
  assert.equal(data.notifications.ratePerMinute, 20);
  assert.equal(data.notifications.maxInflight, 20);
  assert.equal(data.notifications.serverThresholds.diskUsedPercent, 90);
  assert.equal(data.notifications.serverThresholds.memoryUsedPercent, 95);
  assert.equal(data.notifications.serverThresholds.dedupeSeconds, 3600);
  assert.equal(data.notifications.serverThresholds.source, 'diagnostics');
});

test('the real hook registers same-origin domain paths and rejects cross-domain tokens', async function () {
  const harness = h.makeHarness();
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.root = h.makeClient('user//root', '');
  harness.webserver.addUser(harness.alice.user);
  harness.webserver.addUser(harness.root.user);

  harness.installHttpRoutes();
  const paths = harness.webserver.app.routes.map(function (route) { return route.path; });
  assert.deepEqual(paths.sort(), ['/d1/mobilecompanion/events', '/d2/mobilecompanion/events', '/mobilecompanion/events']);

  const d1 = await register(harness, harness.alice);
  assert.equal(d1.endpoint, '/d1/mobilecompanion/events');
  const root = await register(harness, harness.root);
  assert.equal(root.endpoint, '/mobilecompanion/events');

  const viaRoute = await harness.httpEvents({
    viaRoute: true,
    token: root.token,
    domain: '',
    url: root.endpoint
  });
  assert.equal(viaRoute.statusCode, 200);

  const crossPath = await harness.httpEvents({ token: d1.token, domain: 'd1', url: root.endpoint });
  assert.equal(crossPath.statusCode, 401);
  const crossDomain = await harness.httpEvents({ token: d1.token, domain: 'd2', url: d1.endpoint });
  assert.equal(crossDomain.statusCode, 401);
});
