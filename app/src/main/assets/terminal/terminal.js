'use strict';
(() => {
  const terminal = new Terminal({cols:80, rows:24, scrollback:3000, fontSize:16,
    fontFamily:'monospace', convertEol:false, cursorBlink:true, screenReaderMode:true,
    allowProposedApi:false, disableStdin:true, allowTransparency:false,
    linkHandler:{activate:() => {}, hover:() => {}, leave:() => {}},
    windowOptions:{}, logLevel:'off'});
  const fit = new FitAddon.FitAddon();
  terminal.loadAddon(fit);
  terminal.open(document.getElementById('terminal'));
  let enabled = false;
  let control = false;
  // Consume OSC clipboard commands. No clipboard addon or network-capable link addon is loaded.
  terminal.parser.registerOscHandler(52, () => true);
  terminal.parser.registerOscHandler(8, () => true);
  terminal.onData(data => {
    if (!enabled) return;
    if (control && data.length === 1) {
      const code = data.toUpperCase().charCodeAt(0);
      if (code >= 64 && code <= 95) data = String.fromCharCode(code - 64);
      else if (data === '?') data = '\x7f';
    }
    TerminalBridge.input(data);
  });
  terminal.onBinary(data => { if (enabled) TerminalBridge.input(data); });
  // xterm 5.5.0 Terminal._inputEvent deliberately skips direct insertText in screenReaderMode.
  // Android commitText (including CJK/emoji) can emit only beforeinput/input, with no key or
  // composition events. Preserve accessible output and forward precisely that missing path.
  // Hardware keys and compositions stay owned by xterm; never replay their accompanying input.
  let keyDown = false;
  let keyPress = false;
  let composing = false;
  let finishingComposition = false;
  const input = terminal.textarea;
  let imeValue = null;
  // Android commitText can wrap insertText in synthetic key 229. xterm's fallback for that
  // key samples textarea.value in a zero-delay task, which can run BEFORE the native insert.
  // Own only this non-composition fallback, using the actual input event instead of a timer.
  // Suppressing xterm's task also prevents duplicate data when it would run AFTER the insert.
  terminal.attachCustomKeyEventHandler(event => {
    if (event.type === 'keydown' && event.keyCode === 229 && !composing && !finishingComposition) {
      imeValue = input.value;
      return false;
    }
    return true;
  });
  input.addEventListener('keydown', event => { keyDown = event.keyCode !== 229; }, true);
  input.addEventListener('keypress', () => { keyPress = true; }, true);
  input.addEventListener('keyup', () => { keyDown = false; keyPress = false; imeValue = null; }, true);
  input.addEventListener('compositionstart', () => { composing = true; finishingComposition = false; imeValue = null; });
  input.addEventListener('compositionend', () => {
    composing = false;
    finishingComposition = true;
    // xterm's CompositionHelper finalizes the same composition in its preceding zero-delay task.
    setTimeout(() => { finishingComposition = false; }, 0);
  });
  input.addEventListener('blur', () => { keyDown = false; keyPress = false; imeValue = null; });
  input.addEventListener('input', event => {
    if (enabled && terminal.options.screenReaderMode &&
        !event.isComposing && !composing && !finishingComposition && !keyDown && !keyPress) {
      if (event.inputType === 'insertText' && event.data) {
        terminal.input(event.data, true);
      } else if (imeValue !== null) {
        // Preserve xterm's key-229 fallback for native deletion/replacement, without guessing
        // a key from inputType. Composition and ordinary Backspace/Delete remain with xterm.
        const value = input.value;
        if (value.length > imeValue.length) terminal.input(value.replace(imeValue, ''), true);
        else if (value.length < imeValue.length) terminal.input('\x7f', true);
        else if (value !== imeValue) terminal.input(value, true);
      }
      if (imeValue !== null) imeValue = input.value;
    }
  });
  const resize = () => {
    if (!document.documentElement.clientWidth || !document.documentElement.clientHeight) return;
    fit.fit();
    // Bound cell allocation even on pathological display sizes.
    if (terminal.cols > 500 || terminal.rows > 300) terminal.resize(Math.min(terminal.cols,500),Math.min(terminal.rows,300));
    TerminalBridge.resize(terminal.cols,terminal.rows,document.documentElement.clientWidth,document.documentElement.clientHeight);
  };
  new ResizeObserver(resize).observe(document.getElementById('terminal'));
  document.addEventListener('click', event => { if (event.target.closest('a')) event.preventDefault(); });
  const configure = config => {
    terminal.options.theme = {background:config.background, foreground:config.foreground, cursor:config.foreground};
    terminal.options.fontSize = config.fontSize;
    document.body.style.background = config.background;
    Terminal.strings.promptLabel = config.inputLabel;
    Terminal.strings.tooMuchOutput = config.tooMuchLabel;
    if (terminal.textarea) terminal.textarea.setAttribute('aria-label', config.inputLabel);
    resize();
  };
  window.meshTerminal = {
    write: encoded => {
      const raw = atob(encoded);
      const bytes = Uint8Array.from(raw, c => c.charCodeAt(0));
      terminal.write(bytes, () => TerminalBridge.consumed());
    },
    enable: value => { enabled = value; terminal.options.disableStdin = !value; if (!value) control = false; },
    control: value => { control = value; },
    key: value => {
      if (terminal.modes.applicationCursorKeysMode && /^\x1b\[[ABCD]$/.test(value)) value = '\x1bO' + value.slice(-1);
      if (enabled) terminal.input(value, true);
    },
    focus: () => terminal.focus(), selectAll: () => terminal.selectAll(),
    clearSelection: () => terminal.clearSelection(), selection: () => terminal.getSelection().slice(0,262144),
    configure
  };
  if (window.meshTerminalConfiguration) configure(window.meshTerminalConfiguration);
  resize();
  TerminalBridge.ready();
})();
