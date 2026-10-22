'use strict';

const { createPlugin } = require('../mobilecompanion.js');
const { STATE_ID, STATE_TYPE } = require('../lib/state.js');

function clone(value) {
  return (value == null) ? value : JSON.parse(JSON.stringify(value));
}

async function settle() {
  for (let i = 0; i < 8; i++) await new Promise(function (resolve) { setImmediate(resolve); });
}

class FakeDb {
  constructor(initialDoc) {
    this.docs = new Map();
    this.getCount = 0;
    this.setCount = 0;
    this.failGet = false;
    this.failSet = false;
    if (initialDoc) this.docs.set(initialDoc._id, clone(initialDoc));
  }

  Get(id, callback) {
    this.getCount++;
    const self = this;
    setImmediate(function () {
      if (self.failGet) { callback(new Error('fake db get failure')); return; }
      const doc = self.docs.get(id);
      callback(null, doc ? [clone(doc)] : []);
    });
  }

  Set(doc, callback) {
    this.setCount++;
    const self = this;
    setImmediate(function () {
      if (self.failSet) { callback(new Error('fake db set failure')); return; }
      self.docs.set(doc._id, clone(doc));
      callback(null);
    });
  }

  snapshot(id) {
    return clone(this.docs.get(id) || null);
  }
}

class FakeTimers {
  constructor(clock) {
    this.clock = clock;
    this.nextId = 1;
    this.timers = new Map();
  }

  setTimeout(fn, ms) {
    const id = this.nextId++;
    this.timers.set(id, { fn: fn, at: this.clock.now + ms });
    return id;
  }

  clearTimeout(id) {
    this.timers.delete(id);
  }

  async advance(ms) {
    this.clock.now += ms;
    const due = Array.from(this.timers.entries())
      .filter(function (entry) { return entry[1].at <= this.clock.now; }, this)
      .sort(function (a, b) { return a[1].at - b[1].at; });
    for (const entry of due) {
      if (!this.timers.has(entry[0])) continue;
      this.timers.delete(entry[0]);
      entry[1].fn();
    }
    await settle();
  }
}

function makeAgent(nodeid) {
  const agent = {
    dbNodeKey: nodeid,
    authenticated: 2,
    agentInfo: {},
    sent: [],
    onSend: null,
    throwOnSend: false,
    send: function (raw) {
      if (agent.throwOnSend) throw new Error('fake send failure');
      const command = JSON.parse(raw);
      agent.sent.push(command);
      if (typeof agent.onSend === 'function') agent.onSend(command);
    }
  };
  return agent;
}

class FakeWebserver {
  constructor() {
    this.wsagents = {};
    this.users = {};
    this.nodes = {};
    this.calls = [];
    this.holdNodes = false;
    this.heldNodes = [];
    const app = {
      routes: [],
      get: function (path, handler) {
        app.routes.push({ path: path, handler: handler });
      }
    };
    this.app = app;
  }

  addUser(user) {
    this.users[user._id] = user;
    return user;
  }

  addNode(nodeid, options) {
    const opts = options || {};
    const parts = nodeid.split('/');
    const domain = (opts.domain !== undefined) ? opts.domain : parts[1];
    const agentId = (opts.agentId !== undefined) ? opts.agentId : 6;
    const osdesc = (opts.osdesc !== undefined) ? opts.osdesc : 'Ubuntu 22.04 LTS';
    const entry = {
      domain: domain,
      agentId: agentId,
      osdesc: osdesc,
      rights: (opts.rights !== undefined) ? opts.rights : 0x20000,
      visible: (opts.visible !== undefined) ? opts.visible : true,
      rightsByUser: opts.rightsByUser || null,
      hiddenFor: new Set(opts.hiddenFor || []),
      node: opts.node || {
        _id: nodeid,
        meshid: 'mesh/' + domain + '/mesh1',
        agent: { id: agentId, ver: 1300, caps: 24 },
        osdesc: osdesc
      }
    };
    this.nodes[nodeid] = entry;
    return entry;
  }

  addAgent(nodeid, options) {
    const agent = makeAgent(nodeid);
    if (options && typeof options.onSend === 'function') agent.onSend = options.onSend;
    this.wsagents[nodeid] = agent;
    return agent;
  }

  removeAgent(nodeid) {
    delete this.wsagents[nodeid];
  }

  GetNodeWithRights(domain, user, nodeid, callback) {
    this.calls.push({ domainId: domain ? domain.id : null, userId: user ? user._id : null, nodeid: nodeid });
    const self = this;
    const respond = function () {
      const entry = self.nodes[nodeid];
      if (!entry || (domain != null && domain.id !== entry.domain)) { callback(null, 0, false); return; }
      if (entry.hiddenFor.has(user._id)) { callback(entry.node, 0, false); return; }
      let rights = entry.rights;
      let visible = entry.visible !== false;
      if (entry.rightsByUser) {
        if (Object.prototype.hasOwnProperty.call(entry.rightsByUser, user._id)) {
          rights = entry.rightsByUser[user._id];
        } else {
          rights = 0;
          visible = false;
        }
      }
      callback(entry.node, rights, visible);
    };
    if (this.holdNodes) { this.heldNodes.push(respond); return; }
    setImmediate(respond);
  }

  releaseHeldNodes() {
    const held = this.heldNodes.slice();
    this.heldNodes = [];
    this.holdNodes = false;
    for (const respond of held) respond();
  }
}

class FakeMain {
  constructor(db, webserver) {
    this.db = db;
    this.webserver = webserver;
    this.targets = [];
    this.config = { domains: { '': { url: '/' }, d1: { url: '/d1/' }, d2: { url: '/d2/' } } };
  }

  AddEventDispatch(ids, target) {
    this.targets.push({ ids: ids, target: target });
  }

  RemoveEventDispatch(ids, target) {
    this.targets = this.targets.filter(function (entry) { return entry.target !== target; });
  }

  async dispatchEvent(event) {
    const targets = this.targets.map(function (entry) { return entry.target; });
    for (const target of targets) target.HandleEvent(null, event, [], '*');
    await settle();
  }
}

function makeClient(userid, domainid, options) {
  const opts = options || {};
  const responses = [];
  const client = {
    user: { _id: userid, domain: domainid, name: userid.split('/').pop() },
    domain: { id: domainid },
    ws: { sessionId: 'user/' + domainid + '/' + userid.split('/').pop() + '/test', closed: false },
    responses: responses,
    throwOnSend: opts.throwOnSend === true,
    send: function (message) {
      if (client.throwOnSend || client.ws.closed) throw new Error('web socket is closed');
      responses.push(clone(message));
    }
  };
  return client;
}

let requestCounter = 0;
async function request(plugin, client, webserver, command) {
  requestCounter++;
  const payload = Object.assign({
    action: 'plugin',
    plugin: 'mobilecompanion',
    requestId: 'req-' + requestCounter
  }, command);
  if (client && client.user && payload.userid === undefined) payload.userid = client.user._id;
  await plugin.serveraction(payload, client, webserver);
  await settle();
  return client ? client.responses[client.responses.length - 1] : undefined;
}

let uuidCounter = 0;
function uuid() {
  uuidCounter++;
  return '00000000-0000-4000-8000-' + String(uuidCounter).padStart(12, '0');
}

function makeHttpRequest(options) {
  const opts = options || {};
  const headers = Object.assign({}, opts.headers || {});
  if (opts.token !== undefined || opts.authorization !== undefined) {
    headers.authorization = (opts.authorization !== undefined) ? opts.authorization : ('Bearer ' + opts.token);
  }
  const domainid = (opts.domain !== undefined) ? opts.domain : 'd1';
  const defaultUrl = ((domainid === '') ? '/' : ('/' + domainid + '/')) + 'mobilecompanion/events';
  return {
    method: opts.method || 'GET',
    url: opts.url || (defaultUrl + ((opts.query !== undefined) ? ('?' + opts.query) : '')),
    headers: headers,
    secure: opts.secure !== false,
    xdomain: { id: domainid },
    connection: opts.connection || { encrypted: false },
    socket: opts.socket || { encrypted: false }
  };
}

function makeHttpResponse() {
  const res = {
    statusCode: 200,
    headers: {},
    body: null,
    json: null,
    setHeader: function (name, value) { res.headers[String(name).toLowerCase()] = String(value); },
    end: function (body) {
      res.body = body;
      try { res.json = JSON.parse(body); } catch (error) { res.json = null; }
    }
  };
  return res;
}

function installHttpRoutes(harness) {
  if (harness.httpRoutesInstalled === true) return;
  harness.plugin.hook_setupHttpHandlers(harness.webserver, harness.main);
  harness.httpRoutesInstalled = true;
}

async function httpEvents(harness, options) {
  const opts = options || {};
  const req = makeHttpRequest(opts);
  const res = makeHttpResponse();
  if (opts.viaRoute === true) {
    installHttpRoutes(harness);
    const route = harness.webserver.app.routes.find(function (entry) { return entry.path === req.url.split('?')[0]; });
    if (route == null) throw new Error('HTTP route was not registered: ' + req.url.split('?')[0]);
    await route.handler(req, res);
  } else {
    await harness.plugin._handleHttpEvents(req, res, harness.webserver);
  }
  await settle();
  return res;
}

function makeHarness(options) {
  const opts = options || {};
  const clock = { now: (opts.now !== undefined) ? opts.now : 1700000000000 };
  const db = new FakeDb(opts.state);
  const webserver = new FakeWebserver();
  const main = new FakeMain(db, webserver);
  const timers = new FakeTimers(clock);
  const plugin = createPlugin({ parent: main }, {
    now: function () { return clock.now; },
    timers: timers,
    limits: opts.limits
  });
  return {
    clock: clock,
    db: db,
    webserver: webserver,
    main: main,
    timers: timers,
    plugin: plugin,
    startJob: function (client, nodeid, templateId, extra) {
      const more = extra || {};
      return request(plugin, client, webserver, {
        op: 'startJob',
        nodeid: nodeid,
        templateId: templateId,
        parameters: more.parameters || {},
        operationId: more.operationId || uuid(),
        createdAt: (more.createdAt !== undefined) ? more.createdAt : clock.now,
        confirmed: more.confirmed === true
      });
    },
    getJob: function (client, jobId) {
      return request(plugin, client, webserver, { op: 'getJob', jobId: jobId });
    },
    listJobs: function (client, extra) {
      return request(plugin, client, webserver, Object.assign({ op: 'listJobs' }, extra || {}));
    },
    cancelJob: function (client, jobId) {
      return request(plugin, client, webserver, { op: 'cancelJob', jobId: jobId });
    },
    events: function (client, extra) {
      return request(plugin, client, webserver, Object.assign({ op: 'events' }, extra || {}));
    },
    registerNotifications: function (client) {
      return request(plugin, client, webserver, { op: 'registerNotifications' });
    },
    revokeNotifications: function (client, tokenId) {
      return request(plugin, client, webserver, { op: 'revokeNotifications', tokenId: tokenId });
    },
    deleteJob: function (client, jobId) {
      return request(plugin, client, webserver, { op: 'deleteJob', jobId: jobId });
    },
    installHttpRoutes: function () { return installHttpRoutes(this); },
    httpEvents: function (options) { return httpEvents(this, options); }
  };
}

function findRunningCommand(agent, jobId) {
  return agent.sent.find(function (command) {
    return command.action === 'runcommands' && command.sessionid === 'mobilecompanion/' + jobId;
  });
}

function markerFor(nonce, exitCode) {
  return '__MC_DONE_' + nonce + '__' + exitCode;
}

async function rawAgentResult(plugin, agent, nonce, result) {
  await plugin.hook_processAgentData({
    action: 'msg',
    type: 'runcommands',
    responseid: nonce,
    sessionid: 'mobilecompanion/unknown',
    result: result
  }, agent, {});
  await settle();
}

async function completeJob(plugin, agent, jobId, payload, exitCode, responseidOverride) {
  const command = findRunningCommand(agent, jobId);
  if (command == null) throw new Error('command was not sent for job ' + jobId);
  const result = (typeof payload === 'string') ? payload : JSON.stringify(payload);
  const responseid = responseidOverride || command.responseid;
  const code = (exitCode === undefined) ? 0 : exitCode;
  await rawAgentResult(plugin, agent, responseid, result + '\n' + markerFor(command.responseid, code));
}

function makeStoredJob(options) {
  const opts = options || {};
  return {
    id: opts.id,
    owner: opts.owner || 'user/d1/alice',
    domain: opts.domain || 'd1',
    nodeid: opts.nodeid,
    templateId: opts.templateId || 'diagnostics',
    parameters: opts.parameters || {},
    operationId: opts.operationId || uuid(),
    createdAt: (opts.createdAt !== undefined) ? opts.createdAt : 1700000000000,
    startedAt: (opts.startedAt !== undefined) ? opts.startedAt : null,
    finishedAt: (opts.finishedAt !== undefined) ? opts.finishedAt : null,
    status: opts.status || 'completed',
    result: opts.result || null,
    exitCode: (opts.exitCode !== undefined) ? opts.exitCode : null,
    output: opts.output || null,
    truncated: opts.truncated === true,
    errorCode: opts.errorCode || null,
    _nonce: opts.nonce || ('nonce' + Math.random().toString(16).slice(2)),
    _platform: opts.platform || 'linux',
    _operation: opts.templateId || 'diagnostics',
    _waitUntil: (opts.waitUntil !== undefined) ? opts.waitUntil : null
  };
}

function makeState(jobs, events, tokens) {
  return {
    _id: STATE_ID,
    type: STATE_TYPE,
    version: 1,
    seq: 0,
    jobs: jobs || [],
    events: events || [],
    nodeStates: {},
    tokens: tokens || []
  };
}

module.exports = {
  clone: clone,
  settle: settle,
  FakeDb: FakeDb,
  FakeTimers: FakeTimers,
  FakeWebserver: FakeWebserver,
  FakeMain: FakeMain,
  makeAgent: makeAgent,
  makeClient: makeClient,
  makeHttpRequest: makeHttpRequest,
  makeHttpResponse: makeHttpResponse,
  installHttpRoutes: installHttpRoutes,
  httpEvents: httpEvents,
  makeHarness: makeHarness,
  request: request,
  uuid: uuid,
  findRunningCommand: findRunningCommand,
  markerFor: markerFor,
  rawAgentResult: rawAgentResult,
  completeJob: completeJob,
  makeStoredJob: makeStoredJob,
  makeState: makeState
};
