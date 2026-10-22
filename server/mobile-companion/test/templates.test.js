'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const templates = require('../lib/templates');
const schema = require('../lib/schema');

const NONCE = '0123456789abcdef0123456789abcdef';

test('service names are strict and OS-specific', function () {
  assert.equal(templates.validateServiceName('Spooler', 'windows'), true);
  assert.equal(templates.validateServiceName('My Service 1', 'windows'), true);
  assert.equal(templates.validateServiceName("Spooler'; whoami; '", 'windows'), false);
  assert.equal(templates.validateServiceName('Spooler*', 'windows'), false);
  assert.equal(templates.validateServiceName('sshd', 'linux'), true);
  assert.equal(templates.validateServiceName('getty@tty1', 'linux'), true);
  assert.equal(templates.validateServiceName('sshd; reboot', 'linux'), false);
  assert.equal(templates.validateServiceName('sshd service', 'linux'), false);
});

test('generated scripts use fixed templates and a unique completion marker', function () {
  const diagnostics = templates.buildCommand({
    templateId: 'diagnostics', platform: 'linux', parameters: {}, nonce: NONCE
  });
  assert.equal(diagnostics.type, 3);
  assert.match(diagnostics.script, /__MC_DONE_0123456789abcdef0123456789abcdef__/);
  assert.match(diagnostics.script, /systemctl|df|ip/);

  const mutation = templates.buildCommand({
    templateId: 'service-restart', platform: 'windows', parameters: { name: 'Spooler' }, nonce: NONCE
  });
  assert.equal(mutation.type, 2);
  assert.match(mutation.script, /Restart-Service -Name \$name/);
  assert.match(mutation.script, /__MC_DONE_0123456789abcdef0123456789abcdef__/);
  assert.doesNotMatch(mutation.script, /whoami|Stop-Computer/);
});

test('marker parsing requires the exact nonce at the end', function () {
  const good = templates.findMarker('payload\n__MC_DONE_' + NONCE + '__0\n', NONCE);
  assert.deepEqual(good, { found: true, exitCode: 0, payload: 'payload\n' });
  const failed = templates.findMarker('err\n__MC_DONE_' + NONCE + '__17', NONCE);
  assert.equal(failed.found, true);
  assert.equal(failed.exitCode, 17);
  assert.equal(templates.findMarker('payload\n__MC_DONE_' + NONCE + '__0\nextra', NONCE).found, false);
  assert.equal(templates.findMarker('payload', NONCE).found, false);
  assert.equal(templates.findMarker('payload\n__MC_DONE_ffff__0', NONCE).found, false);
});

test('Linux service stanzas are converted to the bounded JSON schema', function () {
  const text = [
    'Id=ssh.service',
    'Description=OpenBSD Secure Shell server',
    'ActiveState=active',
    'SubState=running',
    'UnitFileState=enabled',
    '',
    'Id=cron.service',
    'Description=Regular background program processing daemon',
    'ActiveState=inactive',
    'SubState=dead',
    'UnitFileState=disabled',
    '',
    'MC_TRUNCATED\t0'
  ].join('\n');
  const parsed = schema.parseLinuxServices(text);
  const result = schema.sanitizeServices(parsed, 'linux');
  assert.equal(result.os, 'linux');
  assert.equal(result.truncated, false);
  assert.equal(result.items.length, 2);
  assert.deepEqual(result.items[0], {
    name: 'ssh.service',
    displayName: 'OpenBSD Secure Shell server',
    state: 'active/running',
    startMode: 'enabled'
  });
  assert.equal(Object.prototype.hasOwnProperty.call(result.items[0], 'servicePath'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(result.items[0], 'args'), false);
});

test('Linux process output is parsed and capped at 200 entries', function () {
  const lines = [];
  for (let i = 1; i <= 205; i++) lines.push(i + ' process-' + i + ' 1.5 ' + (i * 10));
  const parsed = schema.parseLinuxProcesses(lines.join('\n'));
  const result = schema.sanitizeProcesses(parsed, 'linux');
  assert.equal(result.items.length, 200);
  assert.equal(result.truncated, true);
  assert.deepEqual(result.items[0], { pid: 1, name: 'process-1', cpu: 1.5, memory: 10240 });
  assert.equal(Object.prototype.hasOwnProperty.call(result.items[0], 'commandLine'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(result.items[0], 'env'), false);
});

test('Windows JSON is re-serialized through the bounded schema', function () {
  const services = schema.sanitizeServices({
    os: 'windows',
    items: [{ name: 'Spooler', displayName: 'Print Spooler', state: 'Running', startMode: 'Automatic', servicePath: 'C:\\secret.exe' }],
    truncated: false
  }, 'windows');
  assert.equal(services.items[0].name, 'Spooler');
  assert.equal(Object.prototype.hasOwnProperty.call(services.items[0], 'servicePath'), false);

  const processes = schema.sanitizeProcesses({
    os: 'windows',
    items: [{ pid: 10, name: 'app.exe', cpu: 12.5, memory: 1024, commandLine: 'secret' }],
    truncated: false
  }, 'windows');
  assert.deepEqual(processes.items[0], { pid: 10, name: 'app.exe', cpu: 12.5, memory: 1024 });

  assert.throws(function () {
    schema.sanitizeDiagnostics({ os: 'linux' }, 'windows');
  });
  assert.throws(function () {
    schema.sanitizeDiagnostics({ os: 'linux' }, 'linux');
  });
});

test('large valid results are bounded to 64 KiB without losing the schema', function () {
  const items = [];
  const long = 'x'.repeat(250);
  for (let i = 0; i < 200; i++) {
    items.push({ name: 'svc-' + i, displayName: long, state: long, startMode: long });
  }
  const result = schema.boundResultSize(schema.sanitizeServices({
    os: 'windows', items: items, truncated: false
  }, 'windows'), 64 * 1024);
  assert.ok(Buffer.byteLength(JSON.stringify(result), 'utf8') <= 64 * 1024);
  assert.equal(result.truncated, true);
  assert.ok(result.items.length < 200);
  assert.ok(result.items.length > 0);
});

test('mutation payloads must match the requested operation and service', function () {
  const good = schema.sanitizeMutation({
    os: 'linux', operation: 'service-start', name: 'sshd', changed: true, state: 'active'
  }, 'linux', 'service-start', 'sshd');
  assert.equal(good.changed, true);
  assert.equal(good.state, 'active');

  assert.throws(function () {
    schema.sanitizeMutation({
      os: 'linux', operation: 'service-stop', name: 'sshd', changed: true
    }, 'linux', 'service-start', 'sshd');
  });
  assert.throws(function () {
    schema.sanitizeMutation({
      os: 'linux', operation: 'service-start', name: 'other', changed: true
    }, 'linux', 'service-start', 'sshd');
  });
});
