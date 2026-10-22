'use strict';

// Optional MeshCentral plugin: mobile jobs and events.
//
// This file is loaded by MeshCentral's pluginHandler as:
//   exports.mobilecompanion = function (pluginHandler) { ... }
//
// It deliberately uses only small adapters around MeshCentral internals
// (webserver.GetNodeWithRights, webserver.wsagents, main.db, event dispatch)
// so the same code can be exercised with stubs in the test suite.  There are
// no HTTP routes and no npm dependencies.

const nodeCrypto = require('node:crypto');
const templates = require('./lib/templates');
const { PluginError, asPluginError } = require('./lib/errors');
const {
  StateStore,
  createDbAdapter,
  clone,
  isPlainObject,
  isActiveJob,
  MAX_EVENTS,
  MAX_OUTPUT_BYTES,
  HISTORY_MS,
  MAX_NOTIFICATION_TOKENS,
  MAX_NOTIFICATION_TOKENS_PER_USER
} = require('./lib/state');

const PLUGIN_ID = 'mobilecompanion';
const PROTOCOL_VERSION = 1;
const REQUEST_ID_MAX = 128;
const OP_MAX = 64;
const JOB_ID_MAX = 128;
const NODE_ID_MAX = 128;
const MAX_RAW_OUTPUT = 1024 * 1024;
const ACCESS_BITS = 0x20000; // MESHRIGHT_REMOTECOMMAND: shell/PowerShell, not type-4 agent console.
const OPERATION_MAX_AGE_MS = 5 * 60 * 1000;
const CLOCK_SKEW_MS = 60 * 1000;
const AGENT_WAIT_MS = 5 * 60 * 1000;
const EXECUTION_TIMEOUT_MS = 5 * 60 * 1000;
const REQUESTHELP_DEDUPE_MS = 60 * 1000;
const NOTIFICATION_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const NOTIFICATION_TOKEN_BYTES = 32;
const NOTIFICATION_TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;
const NOTIFICATION_ID_MAX = 64;
const HTTP_EVENT_PATH = 'mobilecompanion/events';
const HTTP_RATE_WINDOW_MS = 60 * 1000;
const HTTP_RATE_MAX = 20;
const HTTP_RATE_ENTRY_TTL_MS = 2 * 60 * 1000;
const HTTP_RATE_ENTRIES_MAX = 512;
const HTTP_INFLIGHT_MAX = 20;
const HTTP_RESPONSE_MAX_BYTES = 128 * 1024;
const ALERT_DISK_PERCENT = 90;
const ALERT_MEMORY_PERCENT = 95;
const ALERT_DEDUPE_MS = 60 * 60 * 1000;

const OPS = new Set([
  'capabilities', 'startJob', 'getJob', 'listJobs', 'cancelJob', 'events',
  'registerNotifications', 'revokeNotifications', 'deleteJob'
]);
const STATUSES = new Set(['queued', 'running', 'completed', 'failed', 'expired', 'cancelled', 'unknown']);

const SAFE_MESSAGES = {
  INVALID_REQUEST_ID: 'requestId must be a string of 1..128 printable characters',
  INVALID_ARGUMENT: 'invalid argument',
  UNAUTHENTICATED: 'authentication required',
  FORBIDDEN: 'access denied',
  UNKNOWN_OP: 'unknown operation',
  NODE_NOT_FOUND: 'device not found',
  AGENT_OFFLINE: 'agent is not connected',
  UNSUPPORTED_OS: 'device operating system is not supported',
  NODE_BUSY: 'another job is active on this device',
  QUOTA_EXCEEDED: 'job history limit reached',
  STALE_OPERATION: 'operation timestamp is outside the allowed window',
  OPERATION_CONFLICT: 'operationId was already used with a different request',
  CONFIRMATION_REQUIRED: 'confirmed:true is required for mutations',
  JOB_NOT_FOUND: 'job not found',
  JOB_ACTIVE: 'active job cannot be deleted',
  NOTIFICATION_NOT_FOUND: 'notification token not found',
  TOKEN_QUOTA_EXCEEDED: 'notification token limit reached',
  CANCEL_UNSUPPORTED: 'running commands cannot be cancelled',
  DB_UNAVAILABLE: 'persistent state is unavailable',
  DB_ERROR: 'persistent state is unavailable',
  DB_STATE_INVALID: 'persistent state is invalid',
  AUTH_UNAVAILABLE: 'user is no longer available',
  INVALID_RESULT: 'agent returned an invalid result',
  COMMAND_FAILED: 'agent command failed',
  DISPATCH_FAILED: 'command dispatch failed',
  TIMEOUT: 'job timed out',
  INTERNAL_ERROR: 'internal error'
};

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const NODEID_RE = /^node\/[^/]*\/[^/]+$/;
const CONTROL_RE = /[\u0000-\u001f\u007f]/;

function safeMessage(code) {
  return SAFE_MESSAGES[code] || 'request failed';
}

function errorBody(error) {
  const pe = asPluginError(error);
  return { code: pe.code, message: safeMessage(pe.code) };
}

function normalizeRequestId(value) {
  if (typeof value !== 'string') return null;
  if (value.length < 1 || value.length > REQUEST_ID_MAX) return null;
  if (CONTROL_RE.test(value)) return null;
  return value;
}

function normalizeOp(value) {
  if (typeof value !== 'string' || CONTROL_RE.test(value)) return '';
  return value.slice(0, OP_MAX);
}

function sendPluginResponse(client, op, requestId, ok, payload) {
  // Only a user web session receives plugin responses.  Agent contexts have a
  // string-based send() and must never receive a JSON object from this plugin.
  if ((client == null) || (typeof client.send !== 'function') ||
      !isPlainObject(client.user) || !isPlainObject(client.domain)) return;
  const response = {
    action: 'plugin',
    plugin: PLUGIN_ID,
    op: op,
    requestId: requestId,
    ok: ok === true
  };
  if (ok === true) response.data = payload || {};
  else response.error = payload || { code: 'INTERNAL_ERROR', message: safeMessage('INTERNAL_ERROR') };
  try { client.send(response); } catch (error) { /* a closed web socket must not break the plugin */ }
}

function createPlugin(pluginHandler, options) {
  if ((pluginHandler == null) || (pluginHandler.parent == null)) {
    throw new Error('mobilecompanion: pluginHandler.parent is required');
  }
  const opts = options || {};
  const main = pluginHandler.parent;
  const now = typeof opts.now === 'function' ? opts.now : Date.now;
  const timerApi = opts.timers || { setTimeout: setTimeout, clearTimeout: clearTimeout };
  const randomBytes = opts.randomBytes || nodeCrypto.randomBytes;
  const store = new StateStore({
    db: opts.dbAdapter || createDbAdapter(function () { return main.db; }),
    now: now,
    limits: opts.limits
  });

  const jobTimers = new Map();
  let restored = false;

  function randomHex(byteCount) {
    const buffer = randomBytes(byteCount);
    if (!Buffer.isBuffer(buffer)) throw new PluginError('INTERNAL_ERROR', 'random source failed');
    return buffer.toString('hex');
  }

  function newJobId() {
    return 'mc_' + randomHex(12);
  }

  function newNonce() {
    return randomHex(16);
  }

  // ---------------------------------------------------------------------
  // Notification tokens and the read-only HTTP events endpoint.
  // The raw token exists only in the registration response; everything that
  // reaches memory/disk after registration is a SHA-256 hash plus metadata.
  // ---------------------------------------------------------------------

  const httpRateEntries = new Map();
  let httpInflight = 0;

  function hashNotificationToken(token) {
    return nodeCrypto.createHash('sha256').update(token, 'utf8').digest('hex');
  }

  function newNotificationId() {
    return 'mcn_' + randomHex(8);
  }

  function domainPath(domainid) {
    const domains = (main != null && isPlainObject(main.config)) ? main.config.domains : null;
    const entry = isPlainObject(domains) ? domains[domainid] : null;
    let url = (entry != null && typeof entry.url === 'string' && entry.url.startsWith('/')) ? entry.url : null;
    if (url == null) url = (domainid === '') ? '/' : ('/' + domainid + '/');
    if ((url.indexOf('?') >= 0) || (url.indexOf('#') >= 0)) url = '/';
    if (url.endsWith('/') === false) url += '/';
    return url;
  }

  function notificationEndpoint(domainid) {
    return domainPath(domainid) + HTTP_EVENT_PATH;
  }

  function accountDisabled(user) {
    if (!isPlainObject(user)) return true;
    if (typeof user.siteadmin !== 'number') return false;
    if (user.siteadmin === 0xFFFFFFFF) return false;
    return (user.siteadmin & 0x20) !== 0; // SITERIGHT_LOCKED in MeshCentral 1.3.0
  }

  function isSecureRequest(req, webserver) {
    if (req == null) return false;
    if (req.secure === true) return true; // Express only trusts X-Forwarded-Proto when trust proxy is configured
    const socket = req.connection || req.socket;
    if ((socket != null) && (socket.encrypted === true)) return true;
    // MeshCentral binds the main app only to its local https server when
    // tlsoffload is not used, so a non-null tlsServer means this app is TLS.
    if ((webserver != null) && (webserver.tlsServer != null)) return true;
    // MeshCentral 1.3.0 does not call app.set('trust proxy') for the boolean
    // tlsoffload form, so mirror that configured reverse-proxy mode explicitly.
    // Raw forwarded headers are ignored in every other configuration.
    const args = (webserver != null) ? webserver.args : null;
    if ((args != null) && ((args.tlsoffload === true) || (args.trustedproxy === true))) {
      const proto = (req.headers != null) ? req.headers['x-forwarded-proto'] : null;
      if (typeof proto === 'string') {
        const first = proto.split(',')[0].trim().toLowerCase();
        if (first === 'https') return true;
      }
    }
    return false;
  }

  function findNotificationToken(state, candidateHash) {
    const candidate = Buffer.from(candidateHash, 'hex');
    let found = null;
    for (const token of state.tokens) {
      if ((typeof token.hash !== 'string') || (token.hash.length !== 64)) continue;
      const stored = Buffer.from(token.hash, 'hex');
      if ((stored.length !== candidate.length) || (candidate.length !== 32)) continue;
      if (nodeCrypto.timingSafeEqual(stored, candidate) && (found == null)) found = token;
    }
    return found;
  }

  function currentUserForNotification(webserver, record) {
    if ((webserver == null) || !isPlainObject(webserver.users)) return null;
    const user = webserver.users[record.owner];
    if (!isPlainObject(user)) return null;
    if (user._id !== record.owner) return null;
    if ((typeof user.domain !== 'string') || (user.domain !== record.domain)) return null;
    if (accountDisabled(user)) return null;
    return user;
  }

  async function authenticateHttpToken(req, webserver) {
    const headers = (req != null) ? req.headers : null;
    if (!isPlainObject(headers)) return null;
    const raw = headers.authorization;
    if (typeof raw !== 'string') return null;
    const match = /^Bearer[ \t]+([A-Za-z0-9_-]{43})$/i.exec(raw.trim());
    if (match == null) return null;

    const candidateHash = hashNotificationToken(match[1]);
    const record = await store.read(function (state) {
      const found = findNotificationToken(state, candidateHash);
      return (found == null) ? null : clone(found);
    });
    if (record == null) return null;
    if (!(record.expiresAt > now())) return null;
    if ((req.xdomain == null) || !isPlainObject(req.xdomain) || (typeof req.xdomain.id !== 'string')) return null;
    if (req.xdomain.id !== record.domain) return null;
    const user = currentUserForNotification(webserver, record);
    if (user == null) return null;
    return { user: user, userid: record.owner, domainid: record.domain, tokenId: record.id };
  }

  function parseHttpEventsQuery(req) {
    let parsed;
    try {
      parsed = new URL((req != null && typeof req.url === 'string') ? req.url : '/', 'http://meshcentral.invalid');
    } catch (error) {
      return null;
    }
    const values = {};
    for (const pair of parsed.searchParams) {
      const key = pair[0];
      if ((key !== 'since') && (key !== 'limit')) return null;
      if (Object.prototype.hasOwnProperty.call(values, key)) return null;
      values[key] = pair[1];
    }
    let since = 0;
    if (Object.prototype.hasOwnProperty.call(values, 'since')) {
      if (!/^(0|[1-9][0-9]{0,15})$/.test(values.since)) return null;
      since = Number(values.since);
      if (!Number.isSafeInteger(since) || (since < 0)) return null;
    }
    let limit = 50;
    if (Object.prototype.hasOwnProperty.call(values, 'limit')) {
      if (!/^[1-9][0-9]{0,2}$/.test(values.limit)) return null;
      limit = Number(values.limit);
      if (!Number.isSafeInteger(limit) || (limit < 1) || (limit > 100)) return null;
    }
    return { since: since, limit: limit, path: parsed.pathname };
  }

  function trimHttpRateEntries() {
    while (httpRateEntries.size > HTTP_RATE_ENTRIES_MAX) {
      const oldest = httpRateEntries.keys().next().value;
      httpRateEntries.delete(oldest);
    }
  }

  function allowHttpRate(tokenId) {
    const nowMs = now();
    for (const entry of httpRateEntries) {
      if ((nowMs - entry[1].start) >= HTTP_RATE_ENTRY_TTL_MS) httpRateEntries.delete(entry[0]);
    }
    let entry = httpRateEntries.get(tokenId);
    if ((entry == null) || ((nowMs - entry.start) >= HTTP_RATE_WINDOW_MS)) {
      httpRateEntries.delete(tokenId);
      entry = { start: nowMs, count: 0 };
      httpRateEntries.set(tokenId, entry);
    }
    if (entry.count >= HTTP_RATE_MAX) {
      trimHttpRateEntries();
      return false;
    }
    entry.count++;
    trimHttpRateEntries();
    return true;
  }

  function httpResponseHeaders(res) {
    if (res == null) return;
    try {
      if (typeof res.setHeader === 'function') {
        res.setHeader('Cache-Control', 'no-store');
        res.setHeader('Pragma', 'no-cache');
        res.setHeader('X-Content-Type-Options', 'nosniff');
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
      }
    } catch (error) { }
  }

  function sendHttpJson(res, status, payload) {
    if (res == null) return;
    httpResponseHeaders(res);
    let body;
    try { body = JSON.stringify(payload); } catch (error) { body = '{"error":"internal error"}'; status = 500; }
    if (Buffer.byteLength(body, 'utf8') > HTTP_RESPONSE_MAX_BYTES) {
      body = '{"error":"internal error"}';
      status = 500;
    }
    try {
      if (typeof res.status === 'function') res.status(status);
      else res.statusCode = status;
      if (typeof res.setHeader === 'function') res.setHeader('Content-Length', String(Buffer.byteLength(body, 'utf8')));
      if (typeof res.end === 'function') res.end(body);
      else if (typeof res.send === 'function') res.send(body);
    } catch (error) { }
  }

  async function handleHttpEventsRequest(req, res, webserverArg) {
    httpResponseHeaders(res);
    const webserver = webserverArg || main.webserver;
    if (req == null || req.method !== 'GET') {
      try { if (typeof res.setHeader === 'function') res.setHeader('Allow', 'GET'); } catch (error) { }
      sendHttpJson(res, 405, { error: 'method not allowed' });
      return;
    }
    if (isSecureRequest(req, webserver) !== true) {
      // A reverse proxy must be configured as MeshCentral's trustedproxy or
      // tlsoffload for req.secure to become true. Raw forwarded headers are
      // never consulted here.
      sendHttpJson(res, 401, { error: 'unauthorized' });
      return;
    }
    const query = parseHttpEventsQuery(req);
    if (query == null) {
      sendHttpJson(res, 400, { error: 'bad request' });
      return;
    }
    let auth = null;
    try {
      auth = await authenticateHttpToken(req, webserver);
    } catch (error) {
      auth = null;
    }
    if (auth == null) {
      sendHttpJson(res, 401, { error: 'unauthorized' });
      return;
    }
    // The endpoint path and the token domain must agree. This keeps a token
    // from another domain from being used on a shared app even if a caller
    // supplies a forged req.xdomain in a non-core test harness.
    if (query.path !== notificationEndpoint(auth.domainid)) {
      sendHttpJson(res, 401, { error: 'unauthorized' });
      return;
    }
    if (allowHttpRate(auth.tokenId) !== true) {
      try { if (typeof res.setHeader === 'function') res.setHeader('Retry-After', '60'); } catch (error) { }
      sendHttpJson(res, 429, { error: 'rate limited' });
      return;
    }
    if (httpInflight >= HTTP_INFLIGHT_MAX) {
      try { if (typeof res.setHeader === 'function') res.setHeader('Retry-After', '1'); } catch (error) { }
      sendHttpJson(res, 429, { error: 'busy' });
      return;
    }
    httpInflight++;
    try {
      const data = await handleEvents({ since: query.since, limit: query.limit }, auth, webserver);
      sendHttpJson(res, 200, data);
    } catch (error) {
      const pe = asPluginError(error);
      if (pe.code === 'INVALID_ARGUMENT') sendHttpJson(res, 400, { error: 'bad request' });
      else sendHttpJson(res, 500, { error: 'internal error' });
    } finally {
      httpInflight--;
    }
  }

  function installHttpHandlers(webserver) {
    if ((webserver == null) || (webserver.app == null) || (typeof webserver.app.get !== 'function')) return;
    const domains = (main != null && isPlainObject(main.config) && isPlainObject(main.config.domains))
      ? main.config.domains
      : { '': { url: '/' } };
    const registered = new Set();
    for (const domainid of Object.keys(domains)) {
      const endpoint = notificationEndpoint(domainid);
      if (registered.has(endpoint)) continue; // DNS domains share '/' and are resolved by Host/req.xdomain
      registered.add(endpoint);
      webserver.app.get(endpoint, function (req, res) {
        return Promise.resolve(handleHttpEventsRequest(req, res, webserver)).catch(function () {
          sendHttpJson(res, 500, { error: 'internal error' });
        });
      });
    }
  }

  function clearJobTimer(jobId) {
    const timer = jobTimers.get(jobId);
    if (timer === undefined) return;
    jobTimers.delete(jobId);
    try { timerApi.clearTimeout(timer); } catch (error) { }
  }

  function setJobTimer(jobId, delayMs, callback) {
    clearJobTimer(jobId);
    const timer = timerApi.setTimeout(function () {
      jobTimers.delete(jobId);
      Promise.resolve(callback()).catch(function () { });
    }, Math.max(0, delayMs));
    if ((timer != null) && (typeof timer.unref === 'function')) timer.unref();
    jobTimers.set(jobId, timer);
  }

  function findJob(state, jobId) {
    for (const job of state.jobs) { if (job.id === jobId) return job; }
    return null;
  }

  function findJobByNonce(state, nonce) {
    for (const job of state.jobs) { if (job._nonce === nonce) return job; }
    return null;
  }

  function findJobByOperation(state, owner, domain, operationId) {
    for (const job of state.jobs) {
      if ((job.owner === owner) && (job.domain === domain) && (job.operationId === operationId)) return job;
    }
    return null;
  }

  function appendEvent(state, event) {
    const nowMs = now();
    const historyMs = (store.limits && store.limits.historyMs) || HISTORY_MS;
    const maxEvents = (store.limits && store.limits.maxEvents) || MAX_EVENTS;

    state.events = state.events.filter(function (item) {
      const expiresAt = (typeof item.expiresAt === 'number') ? item.expiresAt : ((item.time || 0) + historyMs);
      return expiresAt > nowMs;
    });
    while (state.events.length >= maxEvents) state.events.shift(); // bounded history; active jobs are unaffected

    const sequence = state.seq + 1;
    state.seq = sequence;
    const entry = {
      id: 'mce_' + sequence,
      sequence: sequence,
      kind: event.kind,
      nodeid: event.nodeid,
      time: nowMs,
      expiresAt: nowMs + historyMs,
      domain: event.domain,
      owner: event.owner == null ? null : event.owner,
      dedupeKey: event.dedupeKey == null ? null : event.dedupeKey
    };
    if (event.jobId != null) entry.jobId = event.jobId;
    if (event.metric === 'disk' || event.metric === 'memory') entry.metric = event.metric;
    if (event.direction === 'high') entry.direction = 'high';
    state.events.push(entry);
    return entry;
  }

  function appendJobEvent(state, job, status) {
    if (!STATUSES.has(status)) return;
    const kind = status === 'completed' ? 'job-completed' : 'job-failed';
    const key = 'job:' + job.id + ':' + kind;
    for (const event of state.events) {
      if (event.dedupeKey === key) return;
    }
    appendEvent(state, {
      kind: kind,
      nodeid: job.nodeid,
      domain: job.domain,
      owner: job.owner,
      jobId: job.id,
      dedupeKey: key
    });
  }

  function appendAlertEvents(state, job) {
    // Only successful diagnostics results are evaluated. The stored event
    // carries the allowlisted metric/direction pair, never the raw percentage,
    // disk list, command output, or any other agent data.
    if ((job.templateId !== 'diagnostics') || (job.status !== 'completed')) return;
    const result = job.result;
    if (!isPlainObject(result)) return;
    const metrics = [];
    if (isPlainObject(result.memory) && (typeof result.memory.usedPercent === 'number') &&
        (result.memory.usedPercent >= ALERT_MEMORY_PERCENT)) {
      metrics.push('memory');
    }
    if (Array.isArray(result.disks)) {
      for (const disk of result.disks) {
        if (isPlainObject(disk) && (typeof disk.usedPercent === 'number') &&
            (disk.usedPercent >= ALERT_DISK_PERCENT)) {
          metrics.push('disk');
          break;
        }
      }
    }
    const nowMs = now();
    for (const metric of metrics) {
      const key = 'alert:' + job.nodeid + ':' + metric;
      let duplicate = false;
      for (const event of state.events) {
        if ((event.dedupeKey === key) && ((nowMs - event.time) < ALERT_DEDUPE_MS)) { duplicate = true; break; }
      }
      if (duplicate) continue;
      appendEvent(state, {
        kind: 'device-alert',
        nodeid: job.nodeid,
        domain: job.domain,
        owner: null,
        metric: metric,
        direction: 'high',
        dedupeKey: key
      });
    }
  }

  function publicJob(job) {
    const result = {
      id: job.id,
      nodeid: job.nodeid,
      templateId: job.templateId,
      parameters: isPlainObject(job.parameters) ? clone(job.parameters) : {},
      createdAt: (typeof job.createdAt === 'number') ? job.createdAt : null,
      startedAt: (typeof job.startedAt === 'number') ? job.startedAt : null,
      finishedAt: (typeof job.finishedAt === 'number') ? job.finishedAt : null,
      status: job.status,
      truncated: job.truncated === true
    };
    if (typeof job.exitCode === 'number') result.exitCode = job.exitCode;
    if (job.result !== undefined && job.result !== null) result.result = clone(job.result);
    if (typeof job.output === 'string' && job.output.length > 0) result.output = job.output;
    if (typeof job.errorCode === 'string') result.errorCode = job.errorCode;
    return result;
  }

  function isWithinHistory(job) {
    if (isActiveJob(job)) return true;
    const historyMs = (store.limits && store.limits.historyMs) || HISTORY_MS;
    let finishedAt = job.finishedAt;
    if (typeof finishedAt !== 'number') finishedAt = job.startedAt;
    if (typeof finishedAt !== 'number') finishedAt = job.createdAt;
    if (typeof finishedAt !== 'number') return false;
    return (now() - finishedAt) <= historyMs;
  }

  function publicEvent(event) {
    const item = {
      id: event.id,
      sequence: event.sequence,
      kind: event.kind,
      nodeid: event.nodeid,
      time: event.time,
      expiresAt: event.expiresAt
    };
    if (typeof event.jobId === 'string') item.jobId = event.jobId;
    if (event.metric === 'disk' || event.metric === 'memory') item.metric = event.metric;
    if (event.direction === 'high') item.direction = 'high';
    return item;
  }

  function validateId(value, max) {
    if (typeof value !== 'string') return null;
    if (value.length < 1 || value.length > max) return null;
    if (CONTROL_RE.test(value)) return null;
    return value;
  }

  function validateNodeId(value, domainid) {
    if (typeof value !== 'string' || value.length < 1 || value.length > NODE_ID_MAX) {
      throw new PluginError('INVALID_ARGUMENT', 'invalid nodeid');
    }
    if (!NODEID_RE.test(value)) throw new PluginError('INVALID_ARGUMENT', 'invalid nodeid');
    const parts = value.split('/');
    if (parts[1] !== domainid) throw new PluginError('INVALID_ARGUMENT', 'invalid nodeid domain');
    return value;
  }

  function validateOperationId(value) {
    if (typeof value !== 'string' || !UUID_RE.test(value)) {
      throw new PluginError('INVALID_ARGUMENT', 'invalid operationId');
    }
    return value.toLowerCase();
  }

  function validateCreatedAt(value) {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) {
      throw new PluginError('INVALID_ARGUMENT', 'invalid createdAt');
    }
    return value;
  }

  function validateParameters(templateId, parameters, platform) {
    let raw = parameters;
    if (raw == null) raw = {};
    if (!isPlainObject(raw)) throw new PluginError('INVALID_ARGUMENT', 'invalid parameters');
    const keys = Object.keys(raw);
    if (templates.isReadTemplate(templateId)) {
      if (keys.length !== 0) throw new PluginError('INVALID_ARGUMENT', 'this template takes no parameters');
      return {};
    }
    if (keys.length !== 1 || keys[0] !== 'name') {
      throw new PluginError('INVALID_ARGUMENT', 'service template requires exactly one name parameter');
    }
    if (!templates.validateServiceName(raw.name, platform)) {
      throw new PluginError('INVALID_ARGUMENT', 'invalid service name');
    }
    return { name: raw.name };
  }

  function detectPlatform(node) {
    const agentId = node && node.agent && node.agent.id;
    if (typeof agentId !== 'number' || !Number.isInteger(agentId) || agentId <= 0) return null;
    // Same audited Windows agent-id range used by meshuser.js runcommands.
    if (((agentId > 0) && (agentId < 5)) || ((agentId > 41) && (agentId < 44))) return 'windows';
    // Non-Windows agents use shell type 3.  We only claim Linux when the
    // audited device description identifies a Linux distribution; macOS/BSD
    // are rejected instead of being run through systemctl.
    const osdesc = (node != null && typeof node.osdesc === 'string') ? node.osdesc : '';
    if (osdesc !== '' && /(linux|ubuntu|debian|centos|fedora|red hat|rhel|rocky|alma|suse|opensuse|oracle linux|amazon linux|arch linux|manjaro|alpine|gentoo|slackware|kali|linux mint|raspbian|proxmox|flatcar|coreos)/i.test(osdesc)) {
      return 'linux';
    }
    return null;
  }

  function getAgent(webserver, nodeid) {
    if (webserver == null || !isPlainObject(webserver.wsagents)) return null;
    const agent = webserver.wsagents[nodeid];
    if (agent == null) return null;
    if (agent.authenticated !== 2 || agent.agentInfo == null || typeof agent.send !== 'function') return null;
    if (typeof agent.dbNodeKey === 'string' && agent.dbNodeKey !== nodeid) return null;
    return agent;
  }

  function getNodeWithRights(webserver, domain, user, nodeid) {
    return new Promise(function (resolve, reject) {
      if (webserver == null || typeof webserver.GetNodeWithRights !== 'function') {
        reject(new PluginError('INTERNAL_ERROR', 'GetNodeWithRights is unavailable'));
        return;
      }
      let settled = false;
      const done = function (node, rights, visible) {
        if (settled) return;
        settled = true;
        resolve({ node: node, rights: (typeof rights === 'number') ? rights : 0, visible: visible === true });
      };
      try {
        webserver.GetNodeWithRights(domain, user, nodeid, done);
      } catch (error) {
        if (!settled) { settled = true; reject(new PluginError('INTERNAL_ERROR', 'GetNodeWithRights failed')); }
      }
    });
  }

  async function requireJobAccess(webserver, domainid, user, nodeid) {
    const result = await getNodeWithRights(webserver, { id: domainid }, user, nodeid);
    if (result.node == null || result.visible !== true) throw new PluginError('FORBIDDEN', 'device is not visible');
    if ((result.rights & ACCESS_BITS) !== ACCESS_BITS) throw new PluginError('FORBIDDEN', 'device rights are insufficient');
    return result;
  }

  function authenticate(command, client, webserver) {
    if (!isPlainObject(command)) throw new PluginError('UNAUTHENTICATED', 'command is missing');
    if (client == null || !isPlainObject(client.user) || !isPlainObject(client.domain) || client.ws == null) {
      throw new PluginError('UNAUTHENTICATED', 'not an authenticated user websocket');
    }
    const user = client.user;
    const domain = client.domain;
    if (typeof user._id !== 'string' || user._id.length < 1 || user._id.length > 2048) {
      throw new PluginError('UNAUTHENTICATED', 'user is invalid');
    }
    if (typeof domain.id !== 'string' || domain.id.length > 128) {
      throw new PluginError('UNAUTHENTICATED', 'domain is invalid');
    }
    if (typeof command.userid !== 'string' || command.userid !== user._id) {
      throw new PluginError('UNAUTHENTICATED', 'command user does not match session user');
    }
    if (user.domain != null && user.domain !== domain.id) {
      throw new PluginError('UNAUTHENTICATED', 'user domain does not match session domain');
    }
    if (webserver == null || typeof webserver.GetNodeWithRights !== 'function') {
      throw new PluginError('INTERNAL_ERROR', 'webserver is unavailable');
    }
    return { user: user, domain: domain, userid: user._id, domainid: domain.id };
  }

  async function ensureRestored() {
    if (restored) return;
    const queued = await store.read(function (state) {
      return state.jobs.filter(function (job) { return job.status === 'queued'; }).map(clone);
    });
    const webserver = main.webserver;
    for (const job of queued) {
      if (typeof job._waitUntil !== 'number') continue;
      if (now() >= job._waitUntil) {
        await expireQueued(job.id);
        continue;
      }
      const agent = getAgent(webserver, job.nodeid);
      if (agent == null) {
        setJobTimer(job.id, job._waitUntil - now(), function () { return expireQueued(job.id); });
        continue;
      }
      const user = webserver && webserver.users ? webserver.users[job.owner] : null;
      if (user == null) {
        await failJob(job.id, 'AUTH_UNAVAILABLE');
        continue;
      }
      // The job was never dispatched (status queued), so resuming it after a
      // process restart is not a replay of an in-flight command.
      await dispatchJob(job, webserver, user);
    }
    restored = true;
  }

  async function readJob(jobId) {
    return store.read(function (state) {
      const job = findJob(state, jobId);
      return job == null ? null : clone(job);
    });
  }

  async function failJob(jobId, code) {
    try {
      const changed = await store.transact(function (state) {
        const job = findJob(state, jobId);
        if (job == null || job.status !== 'queued') return { result: false, dirty: false };
        job.status = 'failed';
        job.errorCode = code;
        job.finishedAt = now();
        delete job._waitUntil;
        appendJobEvent(state, job, 'failed');
        return { result: true, dirty: true };
      });
      if (changed) clearJobTimer(jobId);
      return changed;
    } catch (error) {
      // Retry only the terminal-state write. Never resend a remote command.
      setJobTimer(jobId, 30000, function () { return failJob(jobId, code); });
      return false;
    }
  }

  async function markUnknown(jobId, code) {
    try {
      const changed = await store.transact(function (state) {
        const job = findJob(state, jobId);
        if (job == null || job.status !== 'running') return { result: false, dirty: false };
        job.status = 'unknown';
        job.errorCode = code;
        job.finishedAt = now();
        return { result: true, dirty: true };
      });
      if (changed) clearJobTimer(jobId);
      return changed;
    } catch (error) {
      setJobTimer(jobId, 30000, function () { return markUnknown(jobId, code); });
      return false;
    }
  }

  async function expireQueued(jobId) {
    try {
      const changed = await store.transact(function (state) {
        const job = findJob(state, jobId);
        if (job == null || job.status !== 'queued') return { result: false, dirty: false };
        job.status = 'expired';
        job.errorCode = 'AGENT_WAIT_TIMEOUT';
        job.finishedAt = now();
        delete job._waitUntil;
        return { result: true, dirty: true };
      });
      if (changed) clearJobTimer(jobId);
      return changed;
    } catch (error) {
      setJobTimer(jobId, 30000, function () { return expireQueued(jobId); });
      return false;
    }
  }

  async function queueOrFail(job) {
    if (templates.isMutationTemplate(job.templateId)) {
      await failJob(job.id, 'AGENT_OFFLINE');
      return;
    }
    const waitUntil = (typeof job._waitUntil === 'number') ? job._waitUntil : (now() + AGENT_WAIT_MS);
    if (now() >= waitUntil) {
      await expireQueued(job.id);
      return;
    }
    await store.transact(function (state) {
      const current = findJob(state, job.id);
      if (current == null || current.status !== 'queued') return { result: false, dirty: false };
      if (typeof current._waitUntil !== 'number') {
        current._waitUntil = waitUntil;
        return { result: true, dirty: true };
      }
      return { result: false, dirty: false };
    });
    setJobTimer(job.id, waitUntil - now(), function () { return expireQueued(job.id); });
  }

  async function dispatchJob(job, webserver, user) {
    const agent = getAgent(webserver, job.nodeid);
    if (agent == null) { await queueOrFail(job); return; }
    if (user == null) { await failJob(job.id, 'AUTH_UNAVAILABLE'); return; }

    let access;
    try {
      access = await requireJobAccess(webserver, job.domain, user, job.nodeid);
    } catch (error) {
      await failJob(job.id, 'FORBIDDEN');
      return;
    }
    if (access.node == null) { await failJob(job.id, 'FORBIDDEN'); return; }

    let built;
    try {
      built = templates.buildCommand({
        templateId: job.templateId,
        platform: job._platform,
        parameters: job.parameters,
        nonce: job._nonce
      });
    } catch (error) {
      await failJob(job.id, 'UNSUPPORTED_OS');
      return;
    }

    const starting = await store.transact(function (state) {
      const current = findJob(state, job.id);
      if (current == null || current.status !== 'queued') return { result: null, dirty: false };
      if ((typeof current._waitUntil === 'number') && (now() >= current._waitUntil)) {
        current.status = 'expired';
        current.errorCode = 'AGENT_WAIT_TIMEOUT';
        current.finishedAt = now();
        delete current._waitUntil;
        return { result: 'expired', dirty: true };
      }
      current.status = 'running';
      current.startedAt = now();
      current.errorCode = null;
      delete current._waitUntil;
      return { result: clone(current), dirty: true };
    });
    if (starting === 'expired') { clearJobTimer(job.id); return; }
    if (starting == null) return;

    const command = {
      action: 'runcommands',
      type: built.type,
      cmds: built.script,
      runAsUser: 0,
      reply: true,
      responseid: starting._nonce,
      // Synthetic, non-live session id: core's routeAgentCommand only accepts
      // 'user/<domain>/<user>/<session>' and therefore drops this reply.
      sessionid: PLUGIN_ID + '/' + starting.id
    };
    setJobTimer(starting.id, EXECUTION_TIMEOUT_MS, function () { return markUnknown(job.id, 'TIMEOUT'); });
    try {
      agent.send(JSON.stringify(command));
    } catch (error) {
      await markUnknown(starting.id, 'DISPATCH_FAILED');
      return;
    }
  }

  async function dispatchOrQueue(job, webserver, user) {
    if (job == null || job.status !== 'queued') return;
    const agent = getAgent(webserver, job.nodeid);
    if (agent == null) { await queueOrFail(job); return; }
    await dispatchJob(job, webserver, user);
  }

  async function handleCapabilities() {
    const limits = store.limits;
    return {
      protocolVersion: PROTOCOL_VERSION,
      plugin: PLUGIN_ID,
      schemaVersion: 1,
      operations: Array.from(OPS),
      templates: [
        { templateId: 'diagnostics', kind: 'read', parameters: {} },
        { templateId: 'services', kind: 'read', parameters: {} },
        { templateId: 'processes', kind: 'read', parameters: {} },
        { templateId: 'service-start', kind: 'mutation', parameters: { name: 'string' } },
        { templateId: 'service-stop', kind: 'mutation', parameters: { name: 'string' } },
        { templateId: 'service-restart', kind: 'mutation', parameters: { name: 'string' } }
      ],
      platforms: ['windows', 'linux'],
      events: ['job-completed', 'job-failed', 'device-online', 'device-offline', 'device-requesthelp', 'device-alert'],
      alerts: {
        source: 'diagnostics',
        diskUsedPercent: ALERT_DISK_PERCENT,
        memoryUsedPercent: ALERT_MEMORY_PERCENT,
        dedupeSeconds: ALERT_DEDUPE_MS / 1000,
        push: false
      },
      notifications: {
        scope: 'events-read-only',
        method: 'GET',
        path: HTTP_EVENT_PATH,
        endpoint: 'domain.url + "' + HTTP_EVENT_PATH + '"',
        endpointExamples: ['/mobilecompanion/events', '/d1/mobilecompanion/events'],
        auth: 'Authorization: Bearer',
        tokenTtlDays: NOTIFICATION_TOKEN_TTL_MS / (24 * 60 * 60 * 1000),
        maxTokensPerUser: store.limits.maxNotificationTokensPerUser,
        maxTokensGlobal: store.limits.maxNotificationTokens,
        ratePerMinute: HTTP_RATE_MAX,
        maxInflight: HTTP_INFLIGHT_MAX,
        sinceMax: Number.MAX_SAFE_INTEGER,
        limitMax: 100,
        push: false,
        serverThresholds: {
          source: 'diagnostics',
          diskUsedPercent: ALERT_DISK_PERCENT,
          memoryUsedPercent: ALERT_MEMORY_PERCENT,
          dedupeSeconds: ALERT_DEDUPE_MS / 1000
        }
      },
      limits: {
        maxJobs: limits.maxJobs,
        maxEvents: limits.maxEvents,
        maxOutputBytes: limits.maxOutputBytes,
        historyDays: Math.floor(limits.historyMs / (24 * 60 * 60 * 1000)),
        requestIdMaxLength: REQUEST_ID_MAX,
        eventLimitMax: 100,
        diagnosticsAgentWaitSeconds: AGENT_WAIT_MS / 1000,
        executionTimeoutSeconds: EXECUTION_TIMEOUT_MS / 1000
      }
    };
  }

  async function handleStartJob(command, auth, webserver) {
    const nodeid = validateNodeId(command.nodeid, auth.domainid);
    const templateId = command.templateId;
    if (typeof templateId !== 'string' || !templates.isValidTemplate(templateId)) {
      throw new PluginError('INVALID_ARGUMENT', 'invalid templateId');
    }
    const operationId = validateOperationId(command.operationId);
    const createdAt = validateCreatedAt(command.createdAt);
    const readOnly = templates.isReadTemplate(templateId);
    const confirmed = command.confirmed === true;

    const access = await requireJobAccess(webserver, auth.domainid, auth.user, nodeid);
    const platform = detectPlatform(access.node);
    if (platform == null) throw new PluginError('UNSUPPORTED_OS', 'unsupported device operating system');
    const parameters = validateParameters(templateId, command.parameters, platform);

    if (!readOnly && !confirmed) throw new PluginError('CONFIRMATION_REQUIRED', 'mutation requires confirmation');
    if (!readOnly && getAgent(webserver, nodeid) == null) throw new PluginError('AGENT_OFFLINE', 'agent is not connected');

    const nowMs = now();
    const agentOnline = getAgent(webserver, nodeid) != null;
    let outcome;
    try {
      outcome = await store.transact(function (state) {
        const existing = findJobByOperation(state, auth.userid, auth.domainid, operationId);
        if (existing != null) {
          const sameRequest = (existing.nodeid === nodeid) &&
            (existing.templateId === templateId) &&
            (JSON.stringify(existing.parameters) === JSON.stringify(parameters));
          if (!sameRequest) throw new PluginError('OPERATION_CONFLICT', 'operationId already used');
          return { result: { job: clone(existing), isNew: false }, dirty: false };
        }

        if ((nowMs - createdAt) > (OPERATION_MAX_AGE_MS + CLOCK_SKEW_MS) ||
            (createdAt > (nowMs + CLOCK_SKEW_MS))) {
          throw new PluginError('STALE_OPERATION', 'operation timestamp is stale');
        }
        if (state.jobs.length >= store.limits.maxJobs) {
          throw new PluginError('QUOTA_EXCEEDED', 'job history limit reached');
        }
        for (const current of state.jobs) {
          if ((current.nodeid === nodeid) && isActiveJob(current)) {
            throw new PluginError('NODE_BUSY', 'another job is active on this device');
          }
        }

        const job = {
          id: newJobId(),
          owner: auth.userid,
          domain: auth.domainid,
          nodeid: nodeid,
          templateId: templateId,
          parameters: clone(parameters),
          operationId: operationId,
          createdAt: createdAt,
          startedAt: null,
          finishedAt: null,
          status: 'queued',
          result: null,
          exitCode: null,
          output: null,
          truncated: false,
          errorCode: null,
          _nonce: newNonce(),
          _platform: platform,
          _operation: templateId,
          _waitUntil: readOnly ? (nowMs + AGENT_WAIT_MS) : null
        };
        state.jobs.push(job);
        return { result: { job: clone(job), isNew: true }, dirty: true };
      });
    } catch (error) {
      throw asPluginError(error);
    }

    let current = outcome.job;
    if (outcome.isNew) {
      // Arm a deadline before any further database access. Even a failed queued->running write
      // must eventually release this node; mutations remain non-resumable across server restart.
      setJobTimer(outcome.job.id, AGENT_WAIT_MS, function () { return expireQueued(outcome.job.id); });
      try {
        current = await readJob(outcome.job.id);
        if (current != null) await dispatchOrQueue(current, webserver, auth.user);
      } catch (error) {
        // Running is persisted before send, so a still-queued job was never dispatched.
        await failJob(outcome.job.id, 'DISPATCH_FAILED');
      }
      try {
        current = (await readJob(outcome.job.id)) || current;
      } catch (error) {
        // Keep the last known copy; the job was already persisted.
      }
      if (current == null) current = outcome.job;
    }
    return { job: publicJob(current) };
  }

  async function getAccessibleJob(command, auth, webserver) {
    const jobId = validateId(command.jobId, JOB_ID_MAX);
    if (jobId == null) throw new PluginError('INVALID_ARGUMENT', 'invalid jobId');
    const job = await readJob(jobId);
    if (job == null || job.owner !== auth.userid || job.domain !== auth.domainid || !isWithinHistory(job)) {
      throw new PluginError('JOB_NOT_FOUND', 'job not found');
    }
    try {
      await requireJobAccess(webserver, job.domain, auth.user, job.nodeid);
    } catch (error) {
      throw new PluginError('JOB_NOT_FOUND', 'job not found');
    }
    return job;
  }

  async function handleGetJob(command, auth, webserver) {
    const job = await getAccessibleJob(command, auth, webserver);
    return { job: publicJob(job) };
  }

  async function handleListJobs(command, auth, webserver) {
    let limit = 20;
    const maxJobs = store.limits.maxJobs;
    if (command.limit !== undefined) {
      if (typeof command.limit !== 'number' || !Number.isInteger(command.limit) ||
          command.limit < 1 || command.limit > maxJobs) {
        throw new PluginError('INVALID_ARGUMENT', 'invalid limit');
      }
      limit = command.limit;
    }
    let nodeFilter = null;
    if (command.nodeid !== undefined) {
      nodeFilter = validateNodeId(command.nodeid, auth.domainid);
      await requireJobAccess(webserver, auth.domainid, auth.user, nodeFilter);
    }

    const jobs = await store.read(function (state) {
      return state.jobs.filter(function (job) {
        if (job.owner !== auth.userid || job.domain !== auth.domainid) return false;
        if (!isWithinHistory(job)) return false;
        if (nodeFilter != null && job.nodeid !== nodeFilter) return false;
        return true;
      }).sort(function (a, b) {
        const at = (typeof a.createdAt === 'number') ? a.createdAt : 0;
        const bt = (typeof b.createdAt === 'number') ? b.createdAt : 0;
        if (at !== bt) return bt - at;
        return String(b.id).localeCompare(String(a.id));
      }).map(clone);
    });

    const result = [];
    const rightsCache = new Map();
    for (const job of jobs) {
      if (result.length >= limit) break;
      let allowed = rightsCache.get(job.nodeid);
      if (allowed === undefined) {
        try {
          await requireJobAccess(webserver, job.domain, auth.user, job.nodeid);
          allowed = true;
        } catch (error) {
          allowed = false;
        }
        rightsCache.set(job.nodeid, allowed);
      }
      if (allowed) result.push(publicJob(job));
    }
    return { jobs: result };
  }

  async function handleCancelJob(command, auth, webserver) {
    const job = await getAccessibleJob(command, auth, webserver);
    let result = null;
    try {
      result = await store.transact(function (state) {
        const current = findJob(state, job.id);
        if (current == null) throw new PluginError('JOB_NOT_FOUND', 'job not found');
        if (current.status === 'queued') {
          current.status = 'cancelled';
          current.finishedAt = now();
          current.errorCode = null;
          delete current._waitUntil;
          return { result: clone(current), dirty: true };
        }
        if (current.status === 'running') {
          throw new PluginError('CANCEL_UNSUPPORTED', 'running commands cannot be cancelled');
        }
        return { result: clone(current), dirty: false };
      });
    } catch (error) {
      throw asPluginError(error);
    }
    if (result != null && result.status === 'cancelled') clearJobTimer(result.id);
    return { job: publicJob(result || job) };
  }

  async function handleEvents(command, auth, webserver) {
    let since = 0;
    if (command.since !== undefined) {
      if (typeof command.since !== 'number' || !Number.isInteger(command.since) ||
          command.since < 0 || !Number.isSafeInteger(command.since)) {
        throw new PluginError('INVALID_ARGUMENT', 'invalid since');
      }
      since = command.since;
    }
    let limit = 50;
    if (command.limit !== undefined) {
      if (typeof command.limit !== 'number' || !Number.isInteger(command.limit) ||
          command.limit < 1 || command.limit > 100) {
        throw new PluginError('INVALID_ARGUMENT', 'invalid limit');
      }
      limit = command.limit;
    }

    const snapshot = await store.read(function (state) {
      return {
        seq: state.seq,
        events: state.events.slice().sort(function (a, b) { return a.sequence - b.sequence; })
      };
    });

    if (since > snapshot.seq) {
      return { items: [], cursor: snapshot.seq, resetCursor: true };
    }

    const rightsCache = new Map();
    const checkNode = async function (nodeid, jobAccess) {
      const key = (jobAccess ? 'job:' : 'visible:') + nodeid;
      if (rightsCache.has(key)) return rightsCache.get(key);
      let allowed = false;
      try {
        const access = await getNodeWithRights(webserver, { id: nodeid.split('/')[1] }, auth.user, nodeid);
        allowed = (access.node != null) && (access.visible === true);
        if (allowed && jobAccess) allowed = (access.rights & ACCESS_BITS) === ACCESS_BITS;
      } catch (error) {
        allowed = false;
      }
      rightsCache.set(key, allowed);
      return allowed;
    };

    const nowMs = now();
    const items = [];
    let cursor = since;
    let scannedAll = true;
    for (const event of snapshot.events) {
      if (event.sequence <= since) continue;
      if (items.length >= limit) { scannedAll = false; break; }
      cursor = event.sequence;
      if ((typeof event.expiresAt === 'number') && (event.expiresAt <= nowMs)) continue;
      let visible;
      if (event.owner != null) {
        visible = (event.owner === auth.userid) && (event.domain === auth.domainid) &&
          (await checkNode(event.nodeid, true));
      } else {
        visible = (event.domain === auth.domainid) && (await checkNode(event.nodeid, event.kind === 'device-alert'));
      }
      if (visible) items.push(publicEvent(event));
    }
    if (scannedAll) cursor = snapshot.seq;
    return { items: items, cursor: cursor, resetCursor: false };
  }

  async function handleRegisterNotifications(command, auth) {
    if (accountDisabled(auth.user)) throw new PluginError('AUTH_UNAVAILABLE', 'user is no longer available');
    const buffer = randomBytes(NOTIFICATION_TOKEN_BYTES);
    if (!Buffer.isBuffer(buffer) || buffer.length !== NOTIFICATION_TOKEN_BYTES) {
      throw new PluginError('INTERNAL_ERROR', 'random source failed');
    }
    const token = buffer.toString('base64url');
    if (!NOTIFICATION_TOKEN_RE.test(token)) throw new PluginError('INTERNAL_ERROR', 'random source failed');
    const tokenHash = hashNotificationToken(token);
    const nowMs = now();
    const expiresAt = nowMs + NOTIFICATION_TOKEN_TTL_MS;
    const tokenId = newNotificationId();

    await store.transact(function (state) {
      let userCount = 0;
      for (const current of state.tokens) {
        if ((current.owner === auth.userid) && (current.domain === auth.domainid)) userCount++;
      }
      if (userCount >= store.limits.maxNotificationTokensPerUser) {
        throw new PluginError('TOKEN_QUOTA_EXCEEDED', 'per-user token limit reached');
      }
      if (state.tokens.length >= store.limits.maxNotificationTokens) {
        throw new PluginError('TOKEN_QUOTA_EXCEEDED', 'global token limit reached');
      }
      state.tokens.push({
        id: tokenId,
        hash: tokenHash,
        owner: auth.userid,
        domain: auth.domainid,
        expiresAt: expiresAt
      });
      return { result: true, dirty: true };
    });

    return {
      token: token,
      tokenId: tokenId,
      expiresAt: expiresAt,
      endpoint: notificationEndpoint(auth.domainid)
    };
  }

  async function handleRevokeNotifications(command, auth) {
    const tokenId = validateId(command.tokenId, NOTIFICATION_ID_MAX);
    if (tokenId == null) throw new PluginError('INVALID_ARGUMENT', 'invalid tokenId');
    await store.transact(function (state) {
      let index = -1;
      for (let i = 0; i < state.tokens.length; i++) {
        const token = state.tokens[i];
        if ((token.id === tokenId) && (token.owner === auth.userid) && (token.domain === auth.domainid)) {
          index = i;
          break;
        }
      }
      if (index < 0) throw new PluginError('NOTIFICATION_NOT_FOUND', 'notification token not found');
      state.tokens.splice(index, 1);
      return { result: true, dirty: true };
    });
    return { revoked: true, tokenId: tokenId };
  }

  async function handleDeleteJob(command, auth, webserver) {
    const job = await getAccessibleJob(command, auth, webserver);
    await store.transact(function (state) {
      const current = findJob(state, job.id);
      if (current == null) throw new PluginError('JOB_NOT_FOUND', 'job not found');
      if (current.owner !== auth.userid || current.domain !== auth.domainid) {
        throw new PluginError('JOB_NOT_FOUND', 'job not found');
      }
      if (isActiveJob(current)) throw new PluginError('JOB_ACTIVE', 'active job cannot be deleted');
      state.jobs = state.jobs.filter(function (item) { return item.id !== job.id; });
      state.events = state.events.filter(function (event) { return event.jobId !== job.id; });
      return { result: true, dirty: true };
    });
    clearJobTimer(job.id);
    return { deleted: true, jobId: job.id };
  }

  async function handleRequest(command, client, webserver) {
    const opOut = normalizeOp(command && command.op);
    const rawRequestId = (command && typeof command.requestId === 'string') ? command.requestId : null;
    const requestIdOut = (rawRequestId !== null && !CONTROL_RE.test(rawRequestId))
      ? rawRequestId.slice(0, REQUEST_ID_MAX)
      : '';
    try {
      const requestId = normalizeRequestId(command && command.requestId);
      if (requestId == null) throw new PluginError('INVALID_REQUEST_ID', 'invalid requestId');
      const auth = authenticate(command, client, webserver);
      if (!OPS.has(command.op)) throw new PluginError('UNKNOWN_OP', 'unknown operation');

      let data;
      if (command.op === 'capabilities') {
        data = await handleCapabilities();
      } else {
        await ensureRestored();
        if (command.op === 'startJob') data = await handleStartJob(command, auth, webserver);
        else if (command.op === 'getJob') data = await handleGetJob(command, auth, webserver);
        else if (command.op === 'listJobs') data = await handleListJobs(command, auth, webserver);
        else if (command.op === 'cancelJob') data = await handleCancelJob(command, auth, webserver);
        else if (command.op === 'events') data = await handleEvents(command, auth, webserver);
        else if (command.op === 'registerNotifications') data = await handleRegisterNotifications(command, auth);
        else if (command.op === 'revokeNotifications') data = await handleRevokeNotifications(command, auth);
        else if (command.op === 'deleteJob') data = await handleDeleteJob(command, auth, webserver);
        else throw new PluginError('UNKNOWN_OP', 'unknown operation');
      }
      sendPluginResponse(client, opOut, requestIdOut, true, data);
    } catch (error) {
      sendPluginResponse(client, opOut, requestIdOut, false, errorBody(error));
    }
  }

  async function handleAgentData(command, agent) {
    if (!isPlainObject(command) || command.action !== 'msg' || command.type !== 'runcommands') return;
    if (typeof command.responseid !== 'string' || command.responseid.length > 128) return;
    if (agent == null || typeof agent.dbNodeKey !== 'string') return;

    const job = await store.read(function (state) {
      const found = findJobByNonce(state, command.responseid);
      if (found == null || found.nodeid !== agent.dbNodeKey || found.status !== 'running') return null;
      return clone(found);
    });
    if (job == null) return;

    let raw = (typeof command.result === 'string') ? command.result : '';
    let rawTruncated = false;
    if (raw.length > MAX_RAW_OUTPUT) {
      raw = raw.slice(-MAX_RAW_OUTPUT);
      rawTruncated = true;
    }
    const marker = templates.findMarker(raw, job._nonce);
    let outcome;
    if (marker.found !== true) {
      outcome = {
        status: 'unknown',
        errorCode: 'MARKER_MISSING',
        truncated: rawTruncated || exceedsOutput(raw),
        output: boundOutput(raw)
      };
    } else if (marker.exitCode !== 0) {
      outcome = {
        status: 'failed',
        exitCode: marker.exitCode,
        errorCode: 'COMMAND_FAILED',
        truncated: rawTruncated || exceedsOutput(marker.payload),
        output: boundOutput(marker.payload)
      };
    } else {
      try {
        const result = templates.parseResult({
          templateId: job.templateId,
          platform: job._platform,
          parameters: job.parameters,
          payload: marker.payload
        });
        outcome = {
          status: 'completed',
          exitCode: 0,
          result: result,
          truncated: rawTruncated || (result != null && result.truncated === true),
          output: null
        };
      } catch (error) {
        outcome = {
          status: 'failed',
          exitCode: 0,
          errorCode: 'INVALID_RESULT',
          truncated: rawTruncated || exceedsOutput(marker.payload),
          output: boundOutput(marker.payload)
        };
      }
    }

    const changed = await store.transact(function (state) {
      const current = findJobByNonce(state, command.responseid);
      if (current == null || current.nodeid !== agent.dbNodeKey || current.status !== 'running') {
        return { result: false, dirty: false };
      }
      current.status = outcome.status;
      current.finishedAt = now();
      if (typeof outcome.exitCode === 'number') current.exitCode = outcome.exitCode;
      else delete current.exitCode;
      if (outcome.result !== undefined) current.result = outcome.result;
      else delete current.result;
      if (outcome.output != null) current.output = outcome.output;
      else delete current.output;
      current.truncated = outcome.truncated === true;
      if (outcome.errorCode != null) current.errorCode = outcome.errorCode;
      else delete current.errorCode;
      if (current.status === 'completed' || current.status === 'failed') {
        appendJobEvent(state, current, current.status);
      }
      if (current.status === 'completed') appendAlertEvents(state, current);
      return { result: true, dirty: true };
    });
    if (changed) clearJobTimer(job.id);
  }

  function exceedsOutput(value) {
    return typeof value === 'string' && Buffer.byteLength(value, 'utf8') > MAX_OUTPUT_BYTES;
  }

  function boundOutput(value) {
    if (typeof value !== 'string') return null;
    let out = value.replace(/\u0000/g, '');
    let buffer = Buffer.from(out, 'utf8');
    if (buffer.length > MAX_OUTPUT_BYTES) {
      buffer = buffer.slice(buffer.length - MAX_OUTPUT_BYTES);
      // Do not split a UTF-8 continuation byte at the start of the tail.
      while (buffer.length > 0 && (buffer[0] & 0xC0) === 0x80) buffer = buffer.slice(1);
      out = buffer.toString('utf8');
    }
    return out.length > 0 ? out : null;
  }

  async function handleAgentStable(agent, webserverArg) {
    if (agent == null || typeof agent.dbNodeKey !== 'string') return;
    if (agent.authenticated !== 2) return;
    const webserver = webserverArg || main.webserver;
    if (webserver == null) return;
    const queued = await store.read(function (state) {
      return state.jobs.filter(function (job) {
        return job.status === 'queued' && job.nodeid === agent.dbNodeKey;
      }).map(clone);
    });
    for (const job of queued) {
      if ((typeof job._waitUntil === 'number') && (now() >= job._waitUntil)) {
        await expireQueued(job.id);
        continue;
      }
      const user = webserver.users ? webserver.users[job.owner] : null;
      if (user == null) {
        await failJob(job.id, 'AUTH_UNAVAILABLE');
        continue;
      }
      await dispatchJob(job, webserver, user);
    }
  }

  async function handleMainEvent(event) {
    if (!isPlainObject(event)) return;
    if (event.action === 'nodeconnect' && typeof event.nodeid === 'string' && typeof event.conn === 'number') {
      const parts = event.nodeid.split('/');
      if (parts.length !== 3 || parts[0] !== 'node' || parts[2] === '') return;
      const online = (event.conn & 1) !== 0;
      await store.transact(function (state) {
        const previous = state.nodeStates[event.nodeid];
        if (previous != null && previous.online === online) return { result: false, dirty: false };
        state.nodeStates[event.nodeid] = { online: online, time: now() };
        appendEvent(state, {
          kind: online ? 'device-online' : 'device-offline',
          nodeid: event.nodeid,
          domain: parts[1],
          owner: null,
          dedupeKey: 'state:' + event.nodeid + ':' + (online ? '1' : '0') + ':' + now()
        });
        return { result: true, dirty: true };
      });
      return;
    }

    if (event.etype === 'node' && event.action === 'agentlog' &&
        (event.msgid === 98 || event.msgid === '98') && typeof event.nodeid === 'string') {
      const parts = event.nodeid.split('/');
      if (parts.length !== 3 || parts[0] !== 'node' || parts[2] === '') return;
      const nowMs = now();
      await store.transact(function (state) {
        const key = 'requesthelp:' + event.nodeid;
        for (const item of state.events) {
          if ((item.dedupeKey === key) && (nowMs - item.time) < REQUESTHELP_DEDUPE_MS) {
            return { result: false, dirty: false };
          }
        }
        appendEvent(state, {
          kind: 'device-requesthelp',
          nodeid: event.nodeid,
          domain: parts[1],
          owner: null,
          dedupeKey: key
        });
        return { result: true, dirty: true };
      });
    }
  }

  const eventSink = {
    HandleEvent: function (source, event) {
      Promise.resolve(handleMainEvent(event)).catch(function () { });
    }
  };

  if (main.__mobilecompanionEventSink != null && typeof main.RemoveEventDispatch === 'function') {
    try { main.RemoveEventDispatch(['*'], main.__mobilecompanionEventSink); } catch (error) { }
  }
  main.__mobilecompanionEventSink = eventSink;
  if (typeof main.AddEventDispatch === 'function') main.AddEventDispatch(['*'], eventSink);

  const plugin = {
    exports: null,
    serveraction: function (command, client, webserver) {
      return handleRequest(command, client, webserver);
    },
    hook_processAgentData: function (command, agent, parent) {
      return Promise.resolve(handleAgentData(command, agent)).catch(function () { });
    },
    hook_agentCoreIsStable: function (agent, parent) {
      return Promise.resolve(handleAgentStable(agent, parent)).catch(function () { });
    },
    server_startup: function () {
      ensureRestored().catch(function () { });
    },
    hook_setupHttpHandlers: function (webserver, parent) {
      installHttpHandlers(webserver);
    },
    _stateStore: store,
    _eventSink: eventSink,
    _ensureRestored: ensureRestored,
    _notificationEndpoint: notificationEndpoint,
    _hashNotificationToken: hashNotificationToken,
    _handleHttpEvents: handleHttpEventsRequest,
    _installHttpHandlers: installHttpHandlers,
    _httpRateAllowed: allowHttpRate,
    _httpRateSize: function () { return httpRateEntries.size; },
    _httpInflight: function () { return httpInflight; }
  };
  plugin.exports = {};
  return plugin;
}

module.exports.mobilecompanion = function (pluginHandler) {
  return createPlugin(pluginHandler);
};
module.exports._createPlugin = createPlugin;
module.exports.createPlugin = createPlugin;
module.exports._internals = {
  PLUGIN_ID,
  PROTOCOL_VERSION,
  REQUEST_ID_MAX,
  OPS,
  SAFE_MESSAGES,
  NOTIFICATION_TOKEN_TTL_MS,
  HTTP_EVENT_PATH,
  HTTP_RATE_MAX,
  HTTP_INFLIGHT_MAX,
  ALERT_DISK_PERCENT,
  ALERT_MEMORY_PERCENT,
  ALERT_DEDUPE_MS
};
