'use strict';

// Single entry point for environments where node's test runner is not allowed
// to spawn one child process per test file.
require('./core.test.js');
require('./events.test.js');
require('./notifications.test.js');
require('./templates.test.js');
require('./windows-stdin.test.js');
