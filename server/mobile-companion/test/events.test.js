'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const h = require('./helpers');

const LINUX_DIAGNOSTICS = [
  'HOST\thost-1',
  'OSDESC\tUbuntu 22.04.4 LTS',
  'UPTIME\t3600',
  'CPU\t4\t0.5',
  'MEM\t1000000\t400000'
].join('\n');

async function setupEventHarness() {
  const harness = h.makeHarness();
  harness.webserver.addNode('node/d1/a', {
    agentId: 6, osdesc: 'Ubuntu 22.04 LTS',
    rightsByUser: { 'user/d1/alice': 0x20000, 'user/d1/bob': 0 }
  });
  harness.webserver.addNode('node/d1/b', {
    agentId: 6, osdesc: 'Ubuntu 22.04 LTS',
    rightsByUser: { 'user/d1/alice': 0, 'user/d1/bob': 0x20000 }
  });
  harness.webserver.addNode('node/d1/shared', {
    agentId: 6, osdesc: 'Ubuntu 22.04 LTS',
    rightsByUser: { 'user/d1/alice': 0x20000, 'user/d1/bob': 0x20000 }
  });
  harness.alice = h.makeClient('user/d1/alice', 'd1');
  harness.bob = h.makeClient('user/d1/bob', 'd1');
  harness.webserver.addUser(harness.alice.user);
  harness.webserver.addUser(harness.bob.user);
  harness.agentA = harness.webserver.addAgent('node/d1/a');
  harness.agentB = harness.webserver.addAgent('node/d1/b');

  const startA = await harness.startJob(harness.alice, 'node/d1/a', 'diagnostics');
  await h.completeJob(harness.plugin, harness.agentA, startA.data.job.id, LINUX_DIAGNOSTICS);
  const startB = await harness.startJob(harness.bob, 'node/d1/b', 'diagnostics');
  await h.completeJob(harness.plugin, harness.agentB, startB.data.job.id, LINUX_DIAGNOSTICS);

  await harness.main.dispatchEvent({
    etype: 'node', action: 'nodeconnect', nodeid: 'node/d1/shared', conn: 1, domain: 'd1'
  });
  await harness.main.dispatchEvent({
    etype: 'node', action: 'nodeconnect', nodeid: 'node/d1/shared', conn: 1, domain: 'd1'
  });
  await harness.main.dispatchEvent({
    etype: 'node', action: 'agentlog', msgid: 98, nodeid: 'node/d1/shared', domain: 'd1',
    msg: 'secret help text', msgArgs: ['secret', 'args'], sessionid: 'secret-session',
    userid: 'user/d1/alice'
  });
  await harness.main.dispatchEvent({
    etype: 'node', action: 'agentlog', msgid: 98, nodeid: 'node/d1/shared', domain: 'd1',
    msg: 'secret help text duplicate'
  });
  await harness.main.dispatchEvent({
    etype: 'node', action: 'nodeconnect', nodeid: 'node/d1/shared', conn: 0, domain: 'd1'
  });
  return harness;
}

test('per-user event filtering, cursors and fresh rights checks', async function () {
  const harness = await setupEventHarness();

  const aliceEvents = await harness.events(harness.alice, { since: 0, limit: 100 });
  assert.equal(aliceEvents.ok, true);
  assert.equal(aliceEvents.data.resetCursor, false);
  const aliceKinds = aliceEvents.data.items.map(function (item) { return item.kind; });
  assert.equal(aliceKinds.includes('job-completed'), true);
  assert.equal(aliceKinds.includes('device-online'), true);
  assert.equal(aliceKinds.includes('device-requesthelp'), true);
  assert.equal(aliceKinds.includes('device-offline'), true);

  const bobEvents = await harness.events(harness.bob, { since: 0, limit: 100 });
  assert.equal(bobEvents.ok, true);
  const bobJobIds = bobEvents.data.items.filter(function (item) {
    return item.kind === 'job-completed';
  }).map(function (item) { return item.jobId; });
  const aliceJobIds = aliceEvents.data.items.filter(function (item) {
    return item.kind === 'job-completed';
  }).map(function (item) { return item.jobId; });
  assert.equal(aliceJobIds.length, 1);
  assert.equal(bobJobIds.length, 1);
  assert.notEqual(aliceJobIds[0], bobJobIds[0]);

  for (const item of aliceEvents.data.items.concat(bobEvents.data.items)) {
    const keys = Object.keys(item).sort();
    assert.deepEqual(keys.every(function (key) {
      return ['expiresAt', 'id', 'jobId', 'kind', 'nodeid', 'sequence', 'time'].includes(key);
    }), true);
    assert.equal(Object.prototype.hasOwnProperty.call(item, 'msg'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(item, 'msgArgs'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(item, 'sessionid'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(item, 'userid'), false);
  }

  const sequences = aliceEvents.data.items.map(function (item) { return item.sequence; });
  for (let i = 1; i < sequences.length; i++) assert.ok(sequences[i] > sequences[i - 1]);

  const firstPage = await harness.events(harness.alice, { since: 0, limit: 1 });
  assert.equal(firstPage.data.items.length, 1);
  assert.ok(firstPage.data.cursor < aliceEvents.data.cursor);
  const secondPage = await harness.events(harness.alice, { since: firstPage.data.cursor, limit: 100 });
  assert.ok(secondPage.data.items.length > 0);
  assert.equal(secondPage.data.items.some(function (item) {
    return item.sequence <= firstPage.data.cursor;
  }), false);

  const reset = await harness.events(harness.alice, { since: 999999 });
  assert.equal(reset.ok, true);
  assert.equal(reset.data.resetCursor, true);
  assert.deepEqual(reset.data.items, []);
  assert.equal(typeof reset.data.cursor, 'number');

  harness.webserver.nodes['node/d1/shared'].hiddenFor.add('user/d1/alice');
  const revoked = await harness.events(harness.alice, { since: 0, limit: 100 });
  assert.equal(revoked.data.items.some(function (item) {
    return item.nodeid === 'node/d1/shared';
  }), false);
  assert.equal(revoked.data.items.some(function (item) {
    return item.kind === 'job-completed' && item.jobId === aliceJobIds[0];
  }), true);
});

test('device events are deduplicated and requesthelp never stores raw agent text', async function () {
  const harness = await setupEventHarness();
  const response = await harness.events(harness.alice, { since: 0, limit: 100 });
  const shared = response.data.items.filter(function (item) {
    return item.nodeid === 'node/d1/shared';
  });
  assert.equal(shared.filter(function (item) { return item.kind === 'device-online'; }).length, 1);
  assert.equal(shared.filter(function (item) { return item.kind === 'device-offline'; }).length, 1);
  assert.equal(shared.filter(function (item) { return item.kind === 'device-requesthelp'; }).length, 1);
  const state = harness.db.snapshot('mobilecompanion_state');
  const serialized = JSON.stringify(state.events);
  assert.equal(serialized.includes('secret help text'), false);
  assert.equal(serialized.includes('secret-session'), false);
  assert.equal(serialized.includes('secret'), false);
});

test('events expire after the seven day history window', async function () {
  const harness = await setupEventHarness();
  const before = await harness.events(harness.alice, { since: 0, limit: 100 });
  assert.ok(before.data.items.length > 0);
  harness.clock.now += (8 * 24 * 60 * 60 * 1000);
  const after = await harness.events(harness.alice, { since: 0, limit: 100 });
  assert.equal(after.ok, true);
  assert.deepEqual(after.data.items, []);
});

test('events require an authenticated user and validate since/limit', async function () {
  const harness = await setupEventHarness();
  const badSince = await harness.events(harness.alice, { since: -1 });
  assert.equal(badSince.ok, false);
  assert.equal(badSince.error.code, 'INVALID_ARGUMENT');

  const badLimit = await harness.events(harness.alice, { limit: 101 });
  assert.equal(badLimit.ok, false);
  assert.equal(badLimit.error.code, 'INVALID_ARGUMENT');

  const foreign = h.makeClient('user/d1/charlie', 'd1');
  const response = await harness.events(foreign, { since: 0 });
  assert.equal(response.ok, true);
  assert.deepEqual(response.data.items, []);
});
