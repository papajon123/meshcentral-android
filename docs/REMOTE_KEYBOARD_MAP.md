# Remote keyboard map

`Use remote keyboard layout` is an input preference stored per server profile and device. It
mirrors the `Use Remote Keyboard Map` (`remoteKeyMap`) option of the official MeshCentral desktop
client; it does **not** change the keyboard layout of the controlled Windows machine.

## Evidence

- [agent-desktop-0.0.2.js](https://github.com/Ylianst/MeshCentral/blob/1.3.1/public/scripts/agent-desktop-0.0.2.js) (MeshCentral 1.3.1 agent, lines 664–686):
  with `remoteKeyMap == false`, a single printable `key` without Ctrl or Alt is sent as a Unicode
  key event on key press; otherwise a physical key message is sent.
- [default.handlebars](https://github.com/Ylianst/MeshCentral/blob/1.3.1/views/default.handlebars) line 1530 defines the checkbox, line 9820
  passes `desktopsettings.remotekeymap` into the desktop session, and the value is used as the
  input option, not as a remote layout switch.

## Behaviour in this app

- **Off (default).** A hardware printable character with a non-zero `KeyEvent.unicodeChar` and no
  Ctrl/Alt/Meta is sent once as Unicode text on key down. Key up is consumed without an extra
  virtual key release. Repeated key downs repeat the character once per repeat, as the browser input
  path does.
- **On.** Printable characters keep their physical virtual-key down/up pair, so the remote Windows
  layout decides the character.
- Combinations, modifiers, AltGr, special keys, soft-keyboard/IME text, arrow keys and backspace are
  unchanged in both modes. The setting is released together with held keys when it changes, capture
  stops, the input sink changes, focus is lost or the field is disposed.
- The parent `NativeRemoteInputSink` still enforces view-only and permission checks; this preference
  never bypasses it.

## Limitation

Android soft keyboards call `commitText`/`setComposingText` with composed text and expose no
physical key position. That text therefore remains Unicode in both modes and is not reverse-mapped
to a physical layout. The UI states this explicitly; the setting can only affect real hardware key
events.
