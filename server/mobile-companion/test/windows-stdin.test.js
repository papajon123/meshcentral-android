'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');
const templates = require('../lib/templates');
const NONCE = '0123456789abcdef0123456789abcdef';

// Real Windows PowerShell parser and stdin transport; synthetic cmdlets keep
// this test independent of machine data and never change real services.
function fixture(initialState, failServices) {
  return [
    "$global:fixtureState = '" + initialState + "'",
    'function Get-Service { [CmdletBinding()] param([string]$Name) ' +
      (failServices ? "throw ([string][char]0x041F+[char]0x4E2D)" :
        "[pscustomobject]@{ Name='ExampleService'; DisplayName=([string][char]0x041F+[char]0x4E2D); Status=$global:fixtureState; StartType='Automatic' }") + ' }',
    "function Start-Service { [CmdletBinding()] param([string]$Name) $global:fixtureState = 'Running' }",
    "function Stop-Service { [CmdletBinding()] param([string]$Name) $global:fixtureState = 'Stopped' }",
    "function Restart-Service { [CmdletBinding()] param([string]$Name) $global:fixtureState = 'Running' }",
    "function Get-CimInstance { [CmdletBinding()] param([string]$ClassName,[string]$Filter) switch ($ClassName) { " +
      "'Win32_OperatingSystem' { [pscustomobject]@{ Caption='Fixture'; LastBootUpTime=(Get-Date).AddHours(-1); TotalVisibleMemorySize=1000; FreePhysicalMemory=500 } } " +
      "'Win32_Processor' { [pscustomobject]@{ NumberOfLogicalProcessors=2; LoadPercentage=10 } } " +
      "'Win32_PerfFormattedData_PerfProc_Process' { [pscustomobject]@{ Name=([string][char]0x041F+[char]0x4E2D); IDProcess=42; WorkingSet=1024; PercentProcessorTime=5 } } " +
      'default { @() } } }',
    ''
  ].join('\r\n\r\n');
}

function runLikeMeshAgent(script) {
  return new Promise((resolve, reject) => {
    const child = spawn(path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
      ['-NoProfile', '-NoLogo', '-Command', '-'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const chunks = [];
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill(); }, 5000);
    child.stdout.on('data', chunk => { chunks.push(chunk); });
    child.stderr.on('data', chunk => { chunks.push(chunk); });
    child.stdin.on('error', () => {});
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', code => {
      clearTimeout(timer);
      const bytes = Buffer.concat(chunks);
      let output, validUtf8 = true;
      try { output = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
      catch { validUtf8 = false; output = bytes.toString('utf8'); }
      resolve({ output, code, timedOut, validUtf8 });
    });
    // MeshCentral 1.3.0 agents/meshcore.js keeps stdin open, writes an exit
    // command, and sends the accumulated response only when PowerShell exits.
    // Calling stdin.end() here would hide the regression.
    child.stdin.write(script + '\r\nexit\r\n');
  });
}

for (const templateId of templates.ALL_TEMPLATES) {
  test('Windows stdin completes ' + templateId, { skip: process.platform !== 'win32', timeout: 10000 }, async () => {
    const mutation = templateId.startsWith('service-');
    const parameters = mutation ? { name: 'ExampleService' } : {};
    const command = templates.buildCommand({ templateId, platform: 'windows', parameters, nonce: NONCE });
    const result = await runLikeMeshAgent(fixture(templateId === 'service-start' ? 'Stopped' : 'Running', false) + command.script);
    assert.equal(result.timedOut, false, 'PowerShell must exit without closing its stdin');
    assert.equal(result.code, 0);
    assert.equal(result.validUtf8, true, 'Mesh Agent JSON/WebSocket requires UTF-8 output');
    const marker = templates.findMarker(result.output, NONCE);
    assert.equal(marker.found, true);
    assert.equal(marker.exitCode, 0);
    const parsed = templates.parseResult({ templateId, platform: 'windows', parameters, payload: marker.payload });
    assert.equal(parsed.os, 'windows');
    if (templateId === 'services') {
      assert.equal(parsed.items[0].name, 'ExampleService');
      assert.equal(parsed.items[0].displayName, '\u041f\u4e2d');
    }
    if (templateId === 'processes') {
      assert.equal(parsed.items[0].pid, 42);
      assert.equal(parsed.items[0].name, '\u041f\u4e2d');
    }
    if (mutation) assert.equal(parsed.state, templateId === 'service-stop' ? 'Stopped' : 'Running');
  });
}

test('Windows stdin reports command failure without hanging', { skip: process.platform !== 'win32', timeout: 10000 }, async () => {
  const command = templates.buildCommand({ templateId: 'services', platform: 'windows', parameters: {}, nonce: NONCE });
  const result = await runLikeMeshAgent(fixture('Running', true) + command.script);
  assert.equal(result.timedOut, false);
  assert.equal(result.code, 0);
  assert.equal(result.validUtf8, true);
  const marker = templates.findMarker(result.output, NONCE);
  assert.equal(marker.found, true);
  assert.equal(marker.exitCode, 1);
  assert.match(marker.payload, /\u041f\u4e2d/);
});
