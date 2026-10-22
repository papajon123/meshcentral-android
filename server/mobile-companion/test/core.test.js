'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const h = require('./helpers');

const LINUX_DIAGNOSTICS = [
  'HOST\thost-1',
  'OSDESC\tUbuntu 22.04.4 LTS',
  'UPTIME\t3600',
  'CPU\t4\t0.5',
  'MEM\t1000000\t400000',
  'DISK\t/dev/sda1\t/\t1000000\t400000',
  'NET\teth0\t192.168.1.10/24',
  'NET\teth0\tfe80::1/64'
].join('\n');

const WINDOWS_DIAGNOSTICS = {
  os: 'windows',
  hostname: 'win-1',
  osDescription: 'Windows 11 Pro',
  uptimeSeconds: 100,
  cpu: { logicalCount: 8, load1: null, loadPercent: 12.5 },
  memory: { totalBytes: 16000000000, availableBytes: 8000000000, usedPercent: 50 },
  disks: [{ device: 'C:', mount: 'C:', totalBytes: 1000, availableBytes: 400, usedPercent: 60 }],
  network: [{ name: 'Ethernet', mac: 'AA:BB:CC:DD:EE:FF', addresses: ['192.168.1.20'] }],
  truncated: false
};

function setupBasic(options) {
  const harness = h.makeHarness(options);
  harness.webserver.addNode('node/d1/n1', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  harness.webserver.addNode('node/d1/win', { agentId: 2, osdesc: 'Windows 11 Pro' });
  harness.webserver.addNode('node/d1/q1', { agentId: 6, osdesc: 'Debian GNU/Linux' });
  harness.webserver.addNode('node/d1/r1', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.bob = h.makeClient('user/d1/bob', 'd1');
  harness.webserver.addUser(harness.alice.user);
  harness.webserver.addUser(harness.bob.user);
  return harness;
}

test('agent-console rights do not authorize shell jobs; remote-command rights do', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  harness.webserver.nodes['node/d1/n1'].rights = 24;
  const denied = await harness.startJob(harness.alice, 'node/d1/n1', 'service-start', { parameters: { name: 'sshd' }, confirmed: true });
  assert.equal(denied.ok, false);
  assert.equal(denied.error.code, 'FORBIDDEN');
  assert.equal(agent.sent.length, 0);
  harness.webserver.nodes['node/d1/n1'].rights = 0x20000;
  const accepted = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(accepted.ok, true);
  assert.equal(agent.sent.length, 1);
});

test('a failed running-state write does not send commands or permanently block the node', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const write = harness.db.Set.bind(harness.db);
  let failTransition = true;
  harness.db.Set = function (doc, callback) {
    if (failTransition && doc.jobs.some(function (job) { return job.status === 'running'; })) {
      failTransition = false;
      setImmediate(function () { callback(new Error('one transient write failure')); });
    } else write(doc, callback);
  };
  const first = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(first.ok, true);
  assert.equal(first.data.job.status, 'failed');
  assert.equal(first.data.job.errorCode, 'DISPATCH_FAILED');
  assert.equal(agent.sent.length, 0);
  assert.equal(harness.timers.timers.size, 0);
  const next = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(next.ok, true);
  assert.equal(agent.sent.length, 1);
});

test('database recovery retries terminal persistence only, never remote execution', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const write = harness.db.Set.bind(harness.db);
  let unavailable = true;
  harness.db.Set = function (doc, callback) {
    if (unavailable && doc.jobs.some(function (job) { return job.status !== 'queued'; })) {
      setImmediate(function () { callback(new Error('storage unavailable')); });
    } else write(doc, callback);
  };
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(started.data.job.status, 'queued');
  await harness.timers.advance(30001);
  assert.equal(agent.sent.length, 0);
  assert.equal(harness.timers.timers.size, 1);
  unavailable = false;
  await harness.timers.advance(30001);
  const read = await harness.getJob(harness.alice, started.data.job.id);
  assert.equal(read.data.job.status, 'failed');
  assert.equal(agent.sent.length, 0);
  assert.equal(harness.timers.timers.size, 0);
});

test('fake agent context and unauthenticated contexts are rejected', async function () {
  const harness = h.makeHarness();
  let agentSends = 0;
  const agentContext = {
    dbNodeKey: 'node/d1/n1',
    authenticated: 2,
    agentInfo: {},
    send: function () { agentSends++; }
  };
  await harness.plugin.serveraction({
    action: 'plugin', plugin: 'mobilecompanion', op: 'capabilities',
    requestId: 'r1', userid: 'user/d1/alice'
  }, agentContext, harness.webserver);
  assert.equal(agentSends, 0);
  assert.equal(harness.db.setCount, 0);

  const noWs = h.makeClient('user/d1/alice', 'd1');
  noWs.ws = null;
  noWs.send = function (message) { noWs.responses.push(message); };
  const noWsResponse = await h.request(harness.plugin, noWs, harness.webserver, { op: 'capabilities' });
  assert.equal(noWsResponse.ok, false);
  assert.equal(noWsResponse.error.code, 'UNAUTHENTICATED');

  const client = h.makeClient('user/d1/alice', 'd1');
  const mismatch = await h.request(harness.plugin, client, harness.webserver, {
    op: 'capabilities', userid: 'user/d1/bob'
  });
  assert.equal(mismatch.ok, false);
  assert.equal(mismatch.error.code, 'UNAUTHENTICATED');
});

test('foreign user, foreign domain and denied rights are rejected', async function () {
  const harness = setupBasic();
  harness.webserver.addNode('node/d2/n2', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS', domain: 'd2' });
  const agent = harness.webserver.addAgent('node/d1/n1');

  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(started.ok, true);
  const jobId = started.data.job.id;
  assert.equal(agent.sent.length, 1);

  const foreignDomain = await harness.startJob(harness.alice, 'node/d2/n2', 'diagnostics');
  assert.equal(foreignDomain.ok, false);
  assert.equal(foreignDomain.error.code, 'INVALID_ARGUMENT');

  const foreignJob = await harness.getJob(harness.bob, jobId);
  assert.equal(foreignJob.ok, false);
  assert.equal(foreignJob.error.code, 'JOB_NOT_FOUND');

  harness.webserver.nodes['node/d1/win'].rightsByUser = { 'user/d1/bob': 0 };
  const denied = await harness.startJob(harness.bob, 'node/d1/win', 'services');
  assert.equal(denied.ok, false);
  assert.equal(denied.error.code, 'FORBIDDEN');

  harness.webserver.nodes['node/d1/n1'].hiddenFor.add('user/d1/alice');
  const hidden = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  assert.equal(hidden.ok, false);
  assert.equal(hidden.error.code, 'FORBIDDEN');
  assert.equal(agent.sent.length, 1);
});

test('injection-shaped parameters and unknown templates are rejected before dispatch', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/win');
  const badNames = [
    "Spooler'; Stop-Computer; '",
    'Spooler$(Get-Process)',
    'Spooler`whoami`',
    'Spooler\nStop-Service Spooler',
    'Spooler*',
    'Spooler|cmd'
  ];
  for (const name of badNames) {
    const response = await harness.startJob(harness.alice, 'node/d1/win', 'service-start', {
      parameters: { name: name }, confirmed: true
    });
    assert.equal(response.ok, false, 'name should be rejected: ' + JSON.stringify(name));
    assert.equal(response.error.code, 'INVALID_ARGUMENT');
  }

  const extra = await harness.startJob(harness.alice, 'node/d1/win', 'service-start', {
    parameters: { name: 'Spooler', extra: 'x' }, confirmed: true
  });
  assert.equal(extra.ok, false);
  assert.equal(extra.error.code, 'INVALID_ARGUMENT');

  const template = await harness.startJob(harness.alice, 'node/d1/win', 'diagnostics; whoami');
  assert.equal(template.ok, false);
  assert.equal(template.error.code, 'INVALID_ARGUMENT');

  const operation = await harness.startJob(harness.alice, 'node/d1/win', 'diagnostics', {
    operationId: 'not-a-uuid'
  });
  assert.equal(operation.ok, false);
  assert.equal(operation.error.code, 'INVALID_ARGUMENT');

  const unconfirmed = await harness.startJob(harness.alice, 'node/d1/win', 'service-start', {
    parameters: { name: 'Spooler' }, confirmed: false
  });
  assert.equal(unconfirmed.ok, false);
  assert.equal(unconfirmed.error.code, 'CONFIRMATION_REQUIRED');
  assert.equal(agent.sent.length, 0);

  const valid = await harness.startJob(harness.alice, 'node/d1/win', 'service-start', {
    parameters: { name: 'Spooler' }, confirmed: true
  });
  assert.equal(valid.ok, true);
  assert.equal(agent.sent.length, 1);
  assert.match(agent.sent[0].cmds, /\$name = 'Spooler'/);
  assert.match(agent.sent[0].cmds, /Get-Service -Name \$name/);
  assert.doesNotMatch(agent.sent[0].cmds, /Stop-Computer/);
});

test('operationId is idempotent and the job is persisted before dispatch', async function () {
  const harness = setupBasic();
  const operationId = h.uuid();
  const agent = harness.webserver.addAgent('node/d1/n1', {
    onSend: function () {
      const state = harness.db.snapshot('mobilecompanion_state');
      assert.equal(state.jobs.length, 1);
      assert.equal(state.jobs[0].status, 'running');
    }
  });

  const first = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics', { operationId: operationId });
  assert.equal(first.ok, true);
  const jobId = first.data.job.id;
  assert.equal(agent.sent.length, 1);

  const second = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics', { operationId: operationId });
  assert.equal(second.ok, true);
  assert.equal(second.data.job.id, jobId);
  assert.equal(agent.sent.length, 1);

  await h.completeJob(harness.plugin, agent, jobId, LINUX_DIAGNOSTICS);
  const completed = await harness.getJob(harness.alice, jobId);
  assert.equal(completed.data.job.status, 'completed');

  const third = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics', { operationId: operationId });
  assert.equal(third.ok, true);
  assert.equal(third.data.job.id, jobId);
  assert.equal(third.data.job.status, 'completed');
  assert.equal(agent.sent.length, 1);

  const conflict = await harness.startJob(harness.alice, 'node/d1/n1', 'processes', { operationId: operationId });
  assert.equal(conflict.ok, false);
  assert.equal(conflict.error.code, 'OPERATION_CONFLICT');

  const stale = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics', {
    createdAt: harness.clock.now - (7 * 60 * 1000)
  });
  assert.equal(stale.ok, false);
  assert.equal(stale.error.code, 'STALE_OPERATION');
  assert.equal(agent.sent.length, 1);
});

test('ACK-only output does not complete a job', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  const command = h.findRunningCommand(agent, jobId);
  await h.rawAgentResult(harness.plugin, agent, command.responseid, 'OK');

  const response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.data.job.status, 'unknown');
  assert.equal(response.data.job.errorCode, 'MARKER_MISSING');
  assert.equal(response.data.job.finishedAt !== null, true);
  const state = harness.db.snapshot('mobilecompanion_state');
  assert.equal(state.events.filter(function (event) { return event.kind === 'job-completed'; }).length, 0);
});

test('valid marker from the matching agent completes with a bounded result', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  await h.completeJob(harness.plugin, agent, jobId, LINUX_DIAGNOSTICS);

  const response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.ok, true);
  const job = response.data.job;
  assert.equal(job.status, 'completed');
  assert.equal(job.exitCode, 0);
  assert.equal(job.result.os, 'linux');
  assert.equal(job.result.hostname, 'host-1');
  assert.equal(job.result.memory.usedPercent, 60);
  assert.equal(job.result.disks[0].usedPercent, 60);
  assert.equal(job.result.network.length, 1);
  assert.equal(job.result.network[0].addresses.length, 2);
  assert.equal(job.truncated, false);
  assert.equal(Object.prototype.hasOwnProperty.call(job, 'owner'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(job, 'domain'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(job, '_nonce'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(job, 'operationId'), false);

  const command = agent.sent[0];
  assert.equal(command.reply, true);
  assert.equal(command.type, 3);
  assert.equal(command.sessionid, 'mobilecompanion/' + jobId);
  assert.equal(command.sessionid.split('/').length, 2);

  const events = await harness.events(harness.alice, { since: 0 });
  assert.equal(events.ok, true);
  assert.equal(events.data.items.some(function (item) {
    return item.kind === 'job-completed' && item.jobId === jobId;
  }), true);
});

test('a non-zero marker becomes failed with a job-failed event', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  await h.completeJob(harness.plugin, agent, jobId, 'service failed', 1);
  const response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.data.job.status, 'failed');
  assert.equal(response.data.job.exitCode, 1);
  assert.equal(response.data.job.errorCode, 'COMMAND_FAILED');
  const events = await harness.events(harness.alice, { since: 0 });
  assert.equal(events.data.items.some(function (item) {
    return item.kind === 'job-failed' && item.jobId === jobId;
  }), true);
});

test('wrong agent and wrong responseid are ignored', async function () {
  const harness = setupBasic();
  harness.webserver.addNode('node/d1/other', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  const agent = harness.webserver.addAgent('node/d1/n1');
  const otherAgent = harness.webserver.addAgent('node/d1/other');
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  const command = h.findRunningCommand(agent, jobId);

  await h.rawAgentResult(harness.plugin, otherAgent, command.responseid,
    LINUX_DIAGNOSTICS + '\n' + h.markerFor(command.responseid, 0));
  let response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.data.job.status, 'running');

  await h.rawAgentResult(harness.plugin, agent, 'wrong-' + command.responseid,
    LINUX_DIAGNOSTICS + '\n' + h.markerFor(command.responseid, 0));
  response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.data.job.status, 'running');

  await h.completeJob(harness.plugin, agent, jobId, LINUX_DIAGNOSTICS);
  response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.data.job.status, 'completed');
  assert.equal(otherAgent.sent.length, 0);
});

test('result remains available after the originating web socket closes', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  await h.completeJob(harness.plugin, agent, jobId, LINUX_DIAGNOSTICS);

  harness.alice.ws.closed = true;
  harness.alice.throwOnSend = true;
  const aliceAgain = h.makeClient('user/d1/alice', 'd1');
  const response = await harness.getJob(aliceAgain, jobId);
  assert.equal(response.ok, true);
  assert.equal(response.data.job.status, 'completed');
  assert.equal(response.data.job.result.hostname, 'host-1');
});

test('server restart turns running into unknown without re-dispatch', async function () {
  const first = setupBasic();
  const agent = first.webserver.addAgent('node/d1/n1');
  const started = await first.startJob(first.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  assert.equal(agent.sent.length, 1);

  const restarted = h.makeHarness({ state: first.db.snapshot('mobilecompanion_state'), now: first.clock.now });
  restarted.webserver.addNode('node/d1/n1', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  const restartedAgent = restarted.webserver.addAgent('node/d1/n1');
  restarted.alice = h.makeClient('user/d1/alice', 'd1');
  restarted.webserver.addUser(restarted.alice.user);

  const response = await restarted.getJob(restarted.alice, jobId);
  assert.equal(response.ok, true);
  assert.equal(response.data.job.status, 'unknown');
  assert.equal(response.data.job.errorCode, 'SERVER_RESTART');
  assert.equal(restartedAgent.sent.length, 0);
  assert.equal(agent.sent.length, 1);
});

test('queued cancel is real, running cancel is unsupported', async function () {
  const harness = setupBasic();

  const queued = await harness.startJob(harness.alice, 'node/d1/q1', 'diagnostics');
  assert.equal(queued.ok, true);
  assert.equal(queued.data.job.status, 'queued');
  const queuedId = queued.data.job.id;

  const cancelled = await harness.cancelJob(harness.alice, queuedId);
  assert.equal(cancelled.ok, true);
  assert.equal(cancelled.data.job.status, 'cancelled');

  const lateAgent = harness.webserver.addAgent('node/d1/q1');
  await harness.plugin.hook_agentCoreIsStable(lateAgent, harness.webserver);
  await h.settle();
  assert.equal(lateAgent.sent.length, 0);

  const runningAgent = harness.webserver.addAgent('node/d1/r1');
  const running = await harness.startJob(harness.alice, 'node/d1/r1', 'diagnostics');
  const runningId = running.data.job.id;
  assert.equal(running.data.job.status, 'running');
  const unsupported = await harness.cancelJob(harness.alice, runningId);
  assert.equal(unsupported.ok, false);
  assert.equal(unsupported.error.code, 'CANCEL_UNSUPPORTED');
  const stillRunning = await harness.getJob(harness.alice, runningId);
  assert.equal(stillRunning.data.job.status, 'running');

  await h.completeJob(harness.plugin, runningAgent, runningId, LINUX_DIAGNOSTICS);
  const finished = await harness.getJob(harness.alice, runningId);
  assert.equal(finished.data.job.status, 'completed');
});

test('read-only jobs wait for an agent and expire after five minutes', async function () {
  const harness = setupBasic();
  harness.webserver.addNode('node/d1/q2', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  const queued = await harness.startJob(harness.alice, 'node/d1/q2', 'diagnostics');
  assert.equal(queued.ok, true);
  assert.equal(queued.data.job.status, 'queued');

  const lateAgent = harness.webserver.addAgent('node/d1/q2');
  await harness.plugin.hook_agentCoreIsStable(lateAgent, harness.webserver);
  await h.settle();
  assert.equal(lateAgent.sent.length, 1);
  const running = await harness.getJob(harness.alice, queued.data.job.id);
  assert.equal(running.data.job.status, 'running');

  const second = await harness.startJob(harness.alice, 'node/d1/q1', 'diagnostics');
  assert.equal(second.data.job.status, 'queued');
  await harness.timers.advance(5 * 60 * 1000 + 1);
  const expired = await harness.getJob(harness.alice, second.data.job.id);
  assert.equal(expired.data.job.status, 'expired');
  assert.equal(expired.data.job.errorCode, 'AGENT_WAIT_TIMEOUT');
  harness.webserver.addAgent('node/d1/q1');
  await harness.plugin.hook_agentCoreIsStable(harness.webserver.wsagents['node/d1/q1'], harness.webserver);
  await h.settle();
  assert.equal(harness.webserver.wsagents['node/d1/q1'].sent.length, 0);
});

test('requestId and operation allowlist are enforced', async function () {
  const harness = setupBasic();
  const client = harness.alice;
  const longId = 'x'.repeat(129);
  const invalidRequest = await h.request(harness.plugin, client, harness.webserver, {
    op: 'capabilities', requestId: longId
  });
  assert.equal(invalidRequest.ok, false);
  assert.equal(invalidRequest.error.code, 'INVALID_REQUEST_ID');

  const control = await h.request(harness.plugin, client, harness.webserver, {
    op: 'capabilities', requestId: 'bad\u0000id'
  });
  assert.equal(control.error.code, 'INVALID_REQUEST_ID');

  const unknown = await h.request(harness.plugin, client, harness.webserver, { op: 'exec' });
  assert.equal(unknown.ok, false);
  assert.equal(unknown.error.code, 'UNKNOWN_OP');
});

test('quota rejects new jobs without removing an active job; retention removes only old history', async function () {
  const now = 1700000000000;
  const active = h.makeStoredJob({
    id: 'active-job', owner: 'user/d1/alice', domain: 'd1', nodeid: 'node/d1/active',
    status: 'queued', createdAt: now - 1000, waitUntil: now + 60000
  });
  const recent = [];
  for (let i = 0; i < 40; i++) {
    recent.push(h.makeStoredJob({
      id: 'recent-' + i, owner: 'user/d1/alice', domain: 'd1', nodeid: 'node/d1/recent-' + i,
      status: 'completed', createdAt: now - 3600000, finishedAt: now - 3600000
    }));
  }
  const quotaHarness = h.makeHarness({ now: now, state: h.makeState(recent.concat([active])) });
  quotaHarness.webserver.addNode('node/d1/new', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  quotaHarness.webserver.addNode('node/d1/active', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  const quotaClient = h.makeClient('user/d1/alice', 'd1');
  quotaHarness.webserver.addUser(quotaClient.user);
  const quotaResponse = await quotaHarness.startJob(quotaClient, 'node/d1/new', 'diagnostics');
  assert.equal(quotaResponse.ok, false);
  assert.equal(quotaResponse.error.code, 'QUOTA_EXCEEDED');
  const quotaState = quotaHarness.db.snapshot('mobilecompanion_state');
  assert.equal(quotaState.jobs.some(function (job) { return job.id === 'active-job' && job.status === 'queued'; }), true);

  const old = [];
  for (let i = 0; i < 39; i++) {
    old.push(h.makeStoredJob({
      id: 'old-' + i, owner: 'user/d1/alice', domain: 'd1', nodeid: 'node/d1/old-' + i,
      status: 'completed', createdAt: now - 9 * 86400000, finishedAt: now - 8 * 86400000
    }));
  }
  const retentionHarness = h.makeHarness({ now: now, state: h.makeState(old.concat([active])) });
  retentionHarness.webserver.addNode('node/d1/new', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  retentionHarness.webserver.addNode('node/d1/active', { agentId: 6, osdesc: 'Ubuntu 22.04 LTS' });
  const retentionClient = h.makeClient('user/d1/alice', 'd1');
  retentionHarness.webserver.addUser(retentionClient.user);
  const retentionResponse = await retentionHarness.startJob(retentionClient, 'node/d1/new', 'diagnostics');
  assert.equal(retentionResponse.ok, true);
  const retentionState = retentionHarness.db.snapshot('mobilecompanion_state');
  assert.equal(retentionState.jobs.some(function (job) { return job.id === 'active-job'; }), true);
  assert.equal(retentionState.jobs.some(function (job) { return job.id === 'old-0'; }), false);
  assert.equal(retentionState.jobs.length, 2);
});

test('unknown operating systems are rejected and Windows uses PowerShell', async function () {
  const harness = setupBasic();
  harness.webserver.addNode('node/d1/mac', { agentId: 9, osdesc: 'macOS 14.5' });
  const unknown = await harness.startJob(harness.alice, 'node/d1/mac', 'diagnostics');
  assert.equal(unknown.ok, false);
  assert.equal(unknown.error.code, 'UNSUPPORTED_OS');

  const windowsAgent = harness.webserver.addAgent('node/d1/win');
  const windows = await harness.startJob(harness.alice, 'node/d1/win', 'diagnostics');
  assert.equal(windows.ok, true);
  assert.equal(windowsAgent.sent[0].type, 2);
  assert.match(windowsAgent.sent[0].cmds, /ConvertTo-Json/);
});

test('bound output is capped and a missing marker is unknown rather than completed', async function () {
  const harness = setupBasic();
  const agent = harness.webserver.addAgent('node/d1/n1');
  const started = await harness.startJob(harness.alice, 'node/d1/n1', 'diagnostics');
  const jobId = started.data.job.id;
  const command = h.findRunningCommand(agent, jobId);
  const huge = 'x'.repeat(200000);
  await h.rawAgentResult(harness.plugin, agent, command.responseid, huge + '\n' + h.markerFor(command.responseid, 0));
  const response = await harness.getJob(harness.alice, jobId);
  assert.equal(response.data.job.status, 'failed');
  assert.equal(response.data.job.errorCode, 'INVALID_RESULT');
  assert.equal(response.data.job.truncated, true);
  assert.ok(Buffer.byteLength(response.data.job.output, 'utf8') <= 64 * 1024);
});
