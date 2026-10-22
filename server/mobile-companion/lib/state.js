'use strict';

const { PluginError } = require('./errors');

const STATE_ID = 'mobilecompanion_state';
const STATE_TYPE = 'mobilecompanion_state';
const STATE_VERSION = 1;
const MAX_JOBS = 40;
const MAX_EVENTS = 500;
const MAX_OUTPUT_BYTES = 64 * 1024;
const MAX_STATE_BYTES = 4 * 1024 * 1024;
const HISTORY_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_NODE_STATES = 2000;
const MAX_NOTIFICATION_TOKENS = 200;
const MAX_NOTIFICATION_TOKENS_PER_USER = 5;
const TOKEN_HASH_RE = /^[0-9a-f]{64}$/;
const JOB_STATUSES = new Set(['queued', 'running', 'completed', 'failed', 'expired', 'cancelled', 'unknown']);

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function isPlainObject(value) {
  return (value !== null) && (typeof value === 'object') && !Array.isArray(value);
}

function isActiveJob(job) {
  return (job != null) && ((job.status === 'queued') || (job.status === 'running'));
}

function jobFinishedAt(job) {
  if (typeof job.finishedAt === 'number') return job.finishedAt;
  if (typeof job.startedAt === 'number') return job.startedAt;
  if (typeof job.createdAt === 'number') return job.createdAt;
  return 0;
}

function createDbAdapter(dbOrGetter) {
  function resolveDb() {
    const db = (typeof dbOrGetter === 'function') ? dbOrGetter() : dbOrGetter;
    if ((db == null) || (typeof db.Get !== 'function') || (typeof db.Set !== 'function')) {
      throw new PluginError('DB_UNAVAILABLE', 'database is not available');
    }
    return db;
  }

  return {
    get: function (id) {
      return new Promise(function (resolve, reject) {
        let settled = false;
        const done = function (err, docs) {
          if (settled) return;
          settled = true;
          if (err) { reject(new PluginError('DB_ERROR', 'database read failed')); return; }
          resolve(docs);
        };
        try {
          const db = resolveDb();
          db.Get(id, done);
        } catch (error) {
          done(error);
        }
      });
    },
    set: function (doc) {
      return new Promise(function (resolve, reject) {
        let settled = false;
        const done = function (err) {
          if (settled) return;
          settled = true;
          if (err) { reject(new PluginError('DB_ERROR', 'database write failed')); return; }
          resolve();
        };
        try {
          const db = resolveDb();
          db.Set(doc, done);
        } catch (error) {
          done(error);
        }
      });
    }
  };
}

class StateStore {
  constructor(options) {
    this.db = options.db;
    this.now = options.now;
    this.limits = Object.assign({
      maxJobs: MAX_JOBS,
      maxEvents: MAX_EVENTS,
      maxOutputBytes: MAX_OUTPUT_BYTES,
      maxStateBytes: MAX_STATE_BYTES,
      historyMs: HISTORY_MS,
      maxNotificationTokens: MAX_NOTIFICATION_TOKENS,
      maxNotificationTokensPerUser: MAX_NOTIFICATION_TOKENS_PER_USER
    }, options.limits || {});
    this.state = null;
    this._loaded = false;
    this._loadPromise = null;
    this._queue = Promise.resolve();
  }

  _enqueue(fn) {
    const run = this._queue.then(fn, fn);
    this._queue = run.then(function () { }, function () { });
    return run;
  }

  _freshState() {
    return {
      _id: STATE_ID,
      type: STATE_TYPE,
      version: STATE_VERSION,
      seq: 0,
      jobs: [],
      events: [],
      nodeStates: {},
      tokens: []
    };
  }

  _validateState(doc) {
    if (!isPlainObject(doc)) throw new PluginError('DB_STATE_INVALID', 'state document is invalid');
    if ((doc._id !== STATE_ID) || (doc.type !== STATE_TYPE)) {
      throw new PluginError('DB_STATE_INVALID', 'state document identity mismatch');
    }
    if ((doc.version != null) && (doc.version !== STATE_VERSION)) {
      throw new PluginError('DB_STATE_INVALID', 'state document version is not supported');
    }
    if ((doc.seq != null) && (!Number.isSafeInteger(doc.seq) || doc.seq < 0)) {
      throw new PluginError('DB_STATE_INVALID', 'state sequence is invalid');
    }
    if ((doc.jobs != null) && !Array.isArray(doc.jobs)) throw new PluginError('DB_STATE_INVALID', 'state jobs are invalid');
    if ((doc.events != null) && !Array.isArray(doc.events)) throw new PluginError('DB_STATE_INVALID', 'state events are invalid');
    if ((doc.nodeStates != null) && !isPlainObject(doc.nodeStates)) throw new PluginError('DB_STATE_INVALID', 'state node states are invalid');
    if ((doc.tokens != null) && !Array.isArray(doc.tokens)) throw new PluginError('DB_STATE_INVALID', 'state tokens are invalid');

    const state = {
      _id: STATE_ID,
      type: STATE_TYPE,
      version: STATE_VERSION,
      seq: (typeof doc.seq === 'number') ? doc.seq : 0,
      jobs: Array.isArray(doc.jobs) ? doc.jobs : [],
      events: Array.isArray(doc.events) ? doc.events : [],
      nodeStates: isPlainObject(doc.nodeStates) ? doc.nodeStates : {},
      tokens: []
    };

    // Only the SHA-256 hash plus non-secret metadata is persisted. Rebuild the
    // objects instead of cloning them so a stray plaintext token field from an
    // older/broken document can never be written back by this plugin.
    if (Array.isArray(doc.tokens)) {
      for (const token of doc.tokens) {
        if (!isPlainObject(token) || (typeof token.id !== 'string') || (token.id.length < 1) || (token.id.length > 64) ||
            (typeof token.hash !== 'string') || !TOKEN_HASH_RE.test(token.hash) ||
            (typeof token.owner !== 'string') || (token.owner.length < 1) || (token.owner.length > 2048) ||
            (typeof token.domain !== 'string') || (token.domain.length > 128) ||
            (!Number.isSafeInteger(token.expiresAt)) || (token.expiresAt <= 0)) {
          throw new PluginError('DB_STATE_INVALID', 'state token is invalid');
        }
        state.tokens.push({
          id: token.id,
          hash: token.hash,
          owner: token.owner,
          domain: token.domain,
          expiresAt: token.expiresAt
        });
      }
    }

    for (const job of state.jobs) {
      if (!isPlainObject(job) || (typeof job.id !== 'string') || (typeof job.owner !== 'string') ||
          (typeof job.domain !== 'string') || (typeof job.nodeid !== 'string') ||
          (typeof job.templateId !== 'string') || (typeof job.status !== 'string') ||
          !JOB_STATUSES.has(job.status)) {
        throw new PluginError('DB_STATE_INVALID', 'state job is invalid');
      }
    }
    for (const event of state.events) {
      if (!isPlainObject(event) || (typeof event.id !== 'string') ||
          (!Number.isSafeInteger(event.sequence)) || (typeof event.kind !== 'string') ||
          (typeof event.nodeid !== 'string')) {
        throw new PluginError('DB_STATE_INVALID', 'state event is invalid');
      }
    }
    return state;
  }

  _prune(state, nowMs) {
    const historyMs = this.limits.historyMs;
    state.jobs = state.jobs.filter(function (job) {
      if (isActiveJob(job)) return true; // active jobs are never evicted
      return (nowMs - jobFinishedAt(job)) <= historyMs;
    });
    state.events = state.events.filter(function (event) {
      const expiresAt = (typeof event.expiresAt === 'number') ? event.expiresAt : ((event.time || 0) + historyMs);
      return expiresAt > nowMs;
    });
    if (state.events.length > this.limits.maxEvents) {
      state.events = state.events.slice(state.events.length - this.limits.maxEvents);
    }

    const entries = Object.keys(state.nodeStates);
    for (const nodeid of entries) {
      const item = state.nodeStates[nodeid];
      if (!isPlainObject(item) || (typeof item.time !== 'number') || ((nowMs - item.time) > historyMs)) {
        delete state.nodeStates[nodeid];
      }
    }
    const remaining = Object.keys(state.nodeStates);
    if (remaining.length > MAX_NODE_STATES) {
      remaining.sort(function (a, b) {
        return (state.nodeStates[a].time || 0) - (state.nodeStates[b].time || 0);
      });
      for (let i = 0; i < remaining.length - MAX_NODE_STATES; i++) delete state.nodeStates[remaining[i]];
    }

    // Expired notification tokens are removed. The global cap is enforced at
    // registration; this branch also repairs a document imported from a
    // version with a larger cap.
    const maxTokens = this.limits.maxNotificationTokens;
    state.tokens = state.tokens.filter(function (token) {
      return (typeof token.expiresAt === 'number') && (token.expiresAt > nowMs);
    });
    if (state.tokens.length > maxTokens) {
      state.tokens = state.tokens.slice().sort(function (a, b) {
        return a.expiresAt - b.expiresAt;
      }).slice(state.tokens.length - maxTokens);
    }
  }

  async _loadOnce() {
    const docs = await this.db.get(STATE_ID);
    let state;
    if ((docs == null) || (Array.isArray(docs) && docs.length === 0)) {
      state = this._freshState();
    } else {
      if (!Array.isArray(docs) || (docs.length !== 1)) {
        throw new PluginError('DB_STATE_INVALID', 'unexpected state document count');
      }
      state = this._validateState(docs[0]);
    }

    const nowMs = this.now();
    let changed = false;
    for (const job of state.jobs) {
      if (job.status === 'running') {
        job.status = 'unknown';
        job.errorCode = 'SERVER_RESTART';
        job.finishedAt = nowMs;
        delete job._waitUntil;
        changed = true;
      } else if (job.status === 'queued') {
        if (typeof job._waitUntil !== 'number') {
          // A queued job without a wait deadline cannot be dispatched safely
          // after a restart; never execute it again automatically.
          job.status = 'unknown';
          job.errorCode = 'SERVER_RESTART';
          job.finishedAt = nowMs;
          changed = true;
        } else if (nowMs >= job._waitUntil) {
          job.status = 'expired';
          job.errorCode = 'AGENT_WAIT_TIMEOUT';
          job.finishedAt = nowMs;
          changed = true;
        }
      }
    }
    const beforeJobs = state.jobs.length;
    const beforeEvents = state.events.length;
    const beforeNodeStates = Object.keys(state.nodeStates).length;
    const beforeTokens = state.tokens.length;
    this._prune(state, nowMs);
    if ((beforeJobs !== state.jobs.length) || (beforeEvents !== state.events.length) ||
        (beforeNodeStates !== Object.keys(state.nodeStates).length) ||
        (beforeTokens !== state.tokens.length)) {
      changed = true;
    }
    if (changed) await this.db.set(state);
    this.state = state;
    this._loaded = true;
  }

  async _ensureLoaded() {
    if (this._loaded) return;
    if (this._loadPromise == null) {
      const self = this;
      this._loadPromise = this._loadOnce().finally(function () { self._loadPromise = null; });
    }
    await this._loadPromise;
  }

  read(fn) {
    const self = this;
    return this._enqueue(async function () {
      await self._ensureLoaded();
      return fn(clone(self.state));
    });
  }

  transact(fn) {
    const self = this;
    return this._enqueue(async function () {
      await self._ensureLoaded();
      const draft = clone(self.state);
      self._prune(draft, self.now());
      const outcome = await fn(draft);
      if ((outcome == null) || (outcome.dirty !== true)) {
        return (outcome == null) ? undefined : outcome.result;
      }
      const serialized = JSON.stringify(draft);
      if (Buffer.byteLength(serialized, 'utf8') > self.limits.maxStateBytes) {
        throw new PluginError('QUOTA_EXCEEDED', 'persistent state limit exceeded');
      }
      await self.db.set(draft);
      self.state = draft;
      return outcome.result;
    });
  }
}

module.exports = {
  STATE_ID,
  STATE_TYPE,
  STATE_VERSION,
  MAX_JOBS,
  MAX_EVENTS,
  MAX_OUTPUT_BYTES,
  MAX_STATE_BYTES,
  HISTORY_MS,
  MAX_NOTIFICATION_TOKENS,
  MAX_NOTIFICATION_TOKENS_PER_USER,
  clone,
  isPlainObject,
  isActiveJob,
  createDbAdapter,
  StateStore
};
