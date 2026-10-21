/* Run with Node, passing the unpacked official @xterm/headless 5.5.0 lib directory.
 * The package is an audit/test dependency, not shipped in the APK. No npm install required. */
'use strict';
const assert = require('node:assert/strict');
const {Terminal} = require(process.argv[2]);
const make = () => new Terminal({cols:20, rows:4, scrollback:10, allowProposedApi:true});
const write = (t, data) => new Promise(resolve => t.write(data, resolve));
const line = (t, n) => t.buffer.active.getLine(n).translateToString(true);
(async () => {
  let t = make();
  await write(t, 'hello\r\n\x1b[31mred\x1b[0m');
  assert.equal(line(t,0),'hello'); assert.equal(line(t,1),'red');
  assert.equal(t.buffer.active.getLine(1).getCell(0).getFgColor(),1);
  await write(t,'\x1b[1;1H\x1b[2Kreplace'); assert.equal(line(t,0),'replace');
  t.dispose();
  t = make();
  const bytes = Buffer.from('你好 Привет');
  for (const byte of bytes) await write(t,Uint8Array.of(byte));
  assert.equal(line(t,0),'你好 Привет');
  assert.equal(t.buffer.active.getLine(0).getCell(0).getWidth(),2);
  await write(t,'\x1b[?1049hALT'); assert.equal(t.buffer.active.type,'alternate');
  await write(t,'\x1b[?1049l'); assert.equal(line(t,0),'你好 Привет');
  t.resize(30,6); assert.equal(t.cols,30); assert.equal(t.rows,6);
  t.dispose();
  t = make();
  await write(t, Array.from({length:100},(_,i)=>'line'+i+'\r\n').join(''));
  assert.equal(t.buffer.active.length,14); // 4 visible + 10 scrollback
  let clipboardCalls = 0;
  t.parser.registerOscHandler(52, () => { clipboardCalls++; return true; });
  await write(t,'\x1b]52;c;c3ludGhldGlj\x07'); assert.equal(clipboardCalls,1);
  t.dispose();
  console.log('PASS: ANSI colour, cursor/erase, fragmented UTF-8, CJK width, alternate screen, resize, bounded scrollback, OSC52 interception');
})().catch(error => { console.error(error); process.exitCode = 1; });
