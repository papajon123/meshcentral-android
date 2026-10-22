'use strict';

class PluginError extends Error {
  constructor(code, message) {
    super(message || code);
    this.name = 'PluginError';
    this.code = typeof code === 'string' ? code : 'INTERNAL_ERROR';
  }
}

function asPluginError(error, fallbackCode) {
  if (error instanceof PluginError) return error;
  return new PluginError(fallbackCode || 'INTERNAL_ERROR', 'internal error');
}

module.exports = { PluginError, asPluginError };
