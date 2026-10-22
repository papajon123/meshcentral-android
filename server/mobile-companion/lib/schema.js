'use strict';

// Bounded output schemas for the three read-only templates.  Everything that
// comes back from an agent is untrusted: it must be parsed and re-serialized
// through these helpers before it is stored or returned to a web client.

const MAX_ITEMS = 200;
const MAX_DIAGNOSTIC_ITEMS = 100;
const MAX_STRING = 512;
const MAX_DISPLAY_STRING = 256;

const CONTROL_CHARS = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g;

function isPlainObject(value) {
  return (value !== null) && (typeof value === 'object') && !Array.isArray(value);
}

function cleanString(value, max, ctx) {
  if (typeof value !== 'string') return null;
  let out = value.replace(CONTROL_CHARS, ' ').trim();
  if (out.length === 0) return null;
  if (out.length > max) {
    out = out.slice(0, max);
    if (ctx) ctx.truncated = true;
  }
  return out;
}

function cleanNumber(value, ctx, options) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  const opts = options || {};
  if (typeof opts.min === 'number' && value < opts.min) { if (ctx) ctx.truncated = true; return null; }
  if (typeof opts.max === 'number' && value > opts.max) { if (ctx) ctx.truncated = true; return null; }
  if (opts.integer === true) {
    if (!Number.isSafeInteger(value)) return null;
    return value;
  }
  return value;
}

function cleanBoolean(value) {
  return value === true;
}

function sanitizeDiagnostics(parsed, platform) {
  if (!isPlainObject(parsed)) throw new Error('diagnostics payload is not an object');
  if ((parsed.os != null) && (parsed.os !== platform)) throw new Error('diagnostics os mismatch');
  const ctx = { truncated: false };

  const cpuRaw = isPlainObject(parsed.cpu) ? parsed.cpu : {};
  const memoryRaw = isPlainObject(parsed.memory) ? parsed.memory : {};

  const totalBytes = cleanNumber(memoryRaw.totalBytes, ctx, { min: 0, integer: true });
  const availableBytes = cleanNumber(memoryRaw.availableBytes, ctx, { min: 0, integer: true });
  let usedPercent = cleanNumber(memoryRaw.usedPercent, ctx, { min: 0, max: 100 });
  if ((usedPercent === null) && (totalBytes !== null) && (totalBytes > 0) && (availableBytes !== null)) {
    usedPercent = Math.round(((totalBytes - availableBytes) / totalBytes) * 10000) / 100;
  }
  if (usedPercent !== null) usedPercent = Math.max(0, Math.min(100, usedPercent));

  const disksRaw = Array.isArray(parsed.disks) ? parsed.disks : [];
  if (disksRaw.length > MAX_DIAGNOSTIC_ITEMS) ctx.truncated = true;
  const disks = disksRaw.slice(0, MAX_DIAGNOSTIC_ITEMS).map(function (disk) {
    if (!isPlainObject(disk)) return null;
    const total = cleanNumber(disk.totalBytes, ctx, { min: 0, integer: true });
    const available = cleanNumber(disk.availableBytes, ctx, { min: 0, integer: true });
    let used = cleanNumber(disk.usedPercent, ctx, { min: 0, max: 100 });
    if ((used === null) && (total !== null) && (total > 0) && (available !== null)) {
      used = Math.round(((total - available) / total) * 10000) / 100;
      used = Math.max(0, Math.min(100, used));
    }
    return {
      device: cleanString(disk.device, MAX_STRING, ctx),
      mount: cleanString(disk.mount, MAX_STRING, ctx),
      totalBytes: total,
      availableBytes: available,
      usedPercent: used
    };
  }).filter(function (disk) { return disk !== null; });

  const networkRaw = Array.isArray(parsed.network) ? parsed.network : [];
  if (networkRaw.length > MAX_DIAGNOSTIC_ITEMS) ctx.truncated = true;
  const network = networkRaw.slice(0, MAX_DIAGNOSTIC_ITEMS).map(function (iface) {
    if (!isPlainObject(iface)) return null;
    const addressesRaw = Array.isArray(iface.addresses) ? iface.addresses : [];
    if (addressesRaw.length > 32) ctx.truncated = true;
    const addresses = [];
    for (let i = 0; i < addressesRaw.length && i < 32; i++) {
      const address = cleanString(addressesRaw[i], MAX_STRING, ctx);
      if (address !== null) addresses.push(address);
    }
    return {
      name: cleanString(iface.name, MAX_STRING, ctx),
      mac: cleanString(iface.mac, MAX_STRING, ctx),
      addresses: addresses
    };
  }).filter(function (iface) { return iface !== null; });

  const result = {
    os: platform,
    hostname: cleanString(parsed.hostname, MAX_STRING, ctx),
    osDescription: cleanString(parsed.osDescription, MAX_STRING, ctx),
    uptimeSeconds: cleanNumber(parsed.uptimeSeconds, ctx, { min: 0, integer: true }),
    cpu: {
      logicalCount: cleanNumber(cpuRaw.logicalCount, ctx, { min: 1, integer: true }),
      load1: cleanNumber(cpuRaw.load1, ctx, { min: 0 }),
      loadPercent: cleanNumber(cpuRaw.loadPercent, ctx, { min: 0, max: 100 })
    },
    memory: {
      totalBytes: totalBytes,
      availableBytes: availableBytes,
      usedPercent: usedPercent
    },
    disks: disks,
    network: network,
    truncated: ctx.truncated || (parsed.truncated === true)
  };
  const hasData = (result.hostname !== null) || (result.osDescription !== null) ||
    (result.uptimeSeconds !== null) || (result.cpu.logicalCount !== null) ||
    (result.memory.totalBytes !== null) || (result.disks.length > 0) ||
    (result.network.length > 0);
  if (!hasData) throw new Error('diagnostics payload has no data');
  return result;
}

function sanitizeServices(parsed, platform) {
  if (!isPlainObject(parsed)) throw new Error('services payload is not an object');
  if ((parsed.os != null) && (parsed.os !== platform)) throw new Error('services os mismatch');
  if (!Array.isArray(parsed.items)) throw new Error('services items is not an array');
  const ctx = { truncated: parsed.truncated === true };
  if (parsed.items.length > MAX_ITEMS) ctx.truncated = true;

  const items = parsed.items.slice(0, MAX_ITEMS).map(function (item) {
    if (!isPlainObject(item)) return null;
    return {
      name: cleanString(item.name, MAX_DISPLAY_STRING, ctx),
      displayName: cleanString(item.displayName, MAX_DISPLAY_STRING, ctx),
      state: cleanString(item.state, MAX_DISPLAY_STRING, ctx),
      startMode: cleanString(item.startMode, MAX_DISPLAY_STRING, ctx)
    };
  }).filter(function (item) { return item !== null; });

  return { os: platform, items: items, truncated: ctx.truncated };
}

function sanitizeProcesses(parsed, platform) {
  if (!isPlainObject(parsed)) throw new Error('processes payload is not an object');
  if ((parsed.os != null) && (parsed.os !== platform)) throw new Error('processes os mismatch');
  if (!Array.isArray(parsed.items)) throw new Error('processes items is not an array');
  const ctx = { truncated: parsed.truncated === true };
  if (parsed.items.length > MAX_ITEMS) ctx.truncated = true;

  const items = parsed.items.slice(0, MAX_ITEMS).map(function (item) {
    if (!isPlainObject(item)) return null;
    return {
      pid: cleanNumber(item.pid, ctx, { min: 0, integer: true }),
      name: cleanString(item.name, MAX_DISPLAY_STRING, ctx),
      cpu: cleanNumber(item.cpu, ctx, { min: 0 }),
      memory: cleanNumber(item.memory, ctx, { min: 0, integer: true })
    };
  }).filter(function (item) { return item !== null; });

  return { os: platform, items: items, truncated: ctx.truncated };
}

function sanitizeMutation(parsed, platform, operation, requestedName) {
  if (!isPlainObject(parsed)) throw new Error('mutation payload is not an object');
  if (parsed.os !== platform) throw new Error('mutation os mismatch');
  if (parsed.operation !== operation) throw new Error('mutation operation mismatch');
  if (parsed.name !== requestedName) throw new Error('mutation name mismatch');
  if (typeof parsed.changed !== 'boolean') throw new Error('mutation changed is not a boolean');
  const ctx = { truncated: false };
  const result = {
    os: platform,
    operation: operation,
    name: cleanString(parsed.name, MAX_DISPLAY_STRING, ctx),
    changed: parsed.changed
  };
  const state = cleanString(parsed.state, MAX_DISPLAY_STRING, ctx);
  if (state !== null) result.state = state;
  return result;
}

function decodeField(value) {
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const ch = value[i];
    if (ch !== '\\') { out += ch; continue; }
    const next = value[i + 1];
    if (next === undefined) { out += '\\'; break; }
    if (next === '\\') { out += '\\'; i++; }
    else if (next === 't') { out += '\t'; i++; }
    else if (next === 'r') { out += '\r'; i++; }
    else if (next === 'n') { out += '\n'; i++; }
    else { out += next; i++; }
  }
  return out;
}

function numberOrNull(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function parseLinuxDiagnostics(text) {
  const parsed = { os: 'linux', disks: [], network: [] };
  const networkByName = new Map();
  const lines = text.replace(/\r/g, '').split('\n');
  let recognized = 0;
  for (const line of lines) {
    if (line === '') continue;
    const fields = line.split('\t');
    const kind = fields[0];
    if (kind === 'HOST') { recognized++; parsed.hostname = decodeField(fields[1] || ''); }
    else if (kind === 'OSDESC') { recognized++; parsed.osDescription = decodeField(fields[1] || ''); }
    else if (kind === 'UPTIME') { recognized++; parsed.uptimeSeconds = numberOrNull(fields[1]); }
    else if (kind === 'CPU') {
      recognized++;
      parsed.cpu = { logicalCount: numberOrNull(fields[1]), load1: numberOrNull(fields[2]), loadPercent: null };
    } else if (kind === 'MEM') {
      recognized++;
      parsed.memory = { totalBytes: numberOrNull(fields[1]), availableBytes: numberOrNull(fields[2]) };
    } else if (kind === 'DISK') {
      recognized++;
      parsed.disks.push({
        device: decodeField(fields[1] || ''),
        mount: decodeField(fields[2] || ''),
        totalBytes: numberOrNull(fields[3]),
        availableBytes: numberOrNull(fields[4])
      });
    } else if (kind === 'NET') {
      recognized++;
      const name = decodeField(fields[1] || '');
      const address = decodeField(fields[2] || '');
      if (name !== '' && address !== '') {
        if (!networkByName.has(name)) {
          const iface = { name: name, mac: null, addresses: [] };
          networkByName.set(name, iface);
          parsed.network.push(iface);
        }
        networkByName.get(name).addresses.push(address);
      }
    }
  }
  if (recognized === 0) throw new Error('diagnostics payload has no recognizable records');
  return parsed;
}

function parseLinuxServices(text) {
  const parsed = { os: 'linux', items: [], truncated: false };
  const lines = text.replace(/\r/g, '').split('\n');
  let current = {};
  let recognized = 0;
  const flush = function () {
    if (typeof current.Id === 'string' && current.Id !== '') {
      recognized++;
      const stateParts = [];
      if (typeof current.ActiveState === 'string' && current.ActiveState !== '') stateParts.push(current.ActiveState);
      if (typeof current.SubState === 'string' && current.SubState !== '') stateParts.push(current.SubState);
      parsed.items.push({
        name: current.Id,
        displayName: current.Description || null,
        state: stateParts.length > 0 ? stateParts.join('/') : null,
        startMode: current.UnitFileState || null
      });
    }
    current = {};
  };
  for (const line of lines) {
    if (line.startsWith('MC_TRUNCATED\t')) {
      recognized++;
      parsed.truncated = line.split('\t')[1] === '1';
      continue;
    }
    if (line === '') { flush(); continue; }
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    current[line.slice(0, eq)] = line.slice(eq + 1);
  }
  flush();
  if (recognized === 0 && text.trim() !== '') {
    throw new Error('services payload has no recognizable records');
  }
  if (parsed.items.length > MAX_ITEMS) {
    parsed.items = parsed.items.slice(0, MAX_ITEMS);
    parsed.truncated = true;
  }
  return parsed;
}

function parseLinuxProcesses(text) {
  const parsed = { os: 'linux', items: [], truncated: false };
  const lines = text.replace(/\r/g, '').split('\n');
  for (const line of lines) {
    if (line.trim() === '') continue;
    const match = /^\s*(\d+)\s+(.+?)\s+([0-9]+(?:\.[0-9]+)?)\s+(\d+)\s*$/.exec(line);
    if (match === null) continue;
    parsed.items.push({
      pid: Number(match[1]),
      name: match[2],
      cpu: Number(match[3]),
      memory: Number(match[4]) * 1024
    });
  }
  if (parsed.items.length === 0 && text.trim() !== '') {
    throw new Error('processes payload has no recognizable records');
  }
  if (parsed.items.length > MAX_ITEMS) {
    parsed.items = parsed.items.slice(0, MAX_ITEMS);
    parsed.truncated = true;
  }
  return parsed;
}

function parseJson(text) {
  const trimmed = text.trim();
  if (trimmed === '') throw new Error('empty payload');
  return JSON.parse(trimmed);
}

function boundResultSize(result, maxBytes) {
  if (result == null || typeof result !== 'object') return result;
  let serialized = JSON.stringify(result);
  if (Buffer.byteLength(serialized, 'utf8') <= maxBytes) return result;

  result.truncated = true;
  const arrays = [];
  if (Array.isArray(result.items)) arrays.push(result.items);
  if (Array.isArray(result.disks)) arrays.push(result.disks);
  if (Array.isArray(result.network)) {
    arrays.push(result.network);
    for (const iface of result.network) {
      if (iface != null && Array.isArray(iface.addresses)) arrays.push(iface.addresses);
    }
  }

  let guard = 0;
  while (Buffer.byteLength(serialized, 'utf8') > maxBytes && guard++ < 64) {
    let target = null;
    for (const candidate of arrays) {
      if (candidate.length === 0) continue;
      if (target === null || candidate.length > target.length) target = candidate;
    }
    if (target === null) break;
    target.splice(Math.max(0, Math.floor(target.length / 2)));
    serialized = JSON.stringify(result);
  }
  return result;
}

module.exports = {
  MAX_ITEMS,
  MAX_DIAGNOSTIC_ITEMS,
  MAX_STRING,
  isPlainObject,
  sanitizeDiagnostics,
  sanitizeServices,
  sanitizeProcesses,
  sanitizeMutation,
  parseLinuxDiagnostics,
  parseLinuxServices,
  parseLinuxProcesses,
  parseJson,
  boundResultSize
};
