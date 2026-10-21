# Terminal renderer decision and provenance

Audit date: 2026-10-21. Scope: the new Agent/SSH terminal, not the existing MeshCentral login WebView.
Decision: bundle xterm.js locally; keep WebSocket transport, authentication and lifecycle in Kotlin.
No new Gradle, JNI, remote JavaScript, clipboard or link addon dependency is required.

## Pinned packages

Official npm metadata and tarballs were fetched from `registry.npmjs.org`. SHA-512 of each downloaded
tarball matched the official `dist.integrity` field before files were copied. All three packages have
upstream git revision `9ba6c00a195c95fcf8292a2b9084d91450e5daae`.

| Package | Version | Use | Licence |
| --- | --- | --- | --- |
| `@xterm/xterm` | 5.5.0 | Shipped `lib/xterm.js`, `css/xterm.css`, `LICENSE` | MIT |
| `@xterm/addon-fit` | 0.10.0 | Shipped `lib/addon-fit.js`, `LICENSE`; peer xterm ^5.0.0 | MIT |
| `@xterm/headless` | 5.5.0 | Node verification only; not in the APK | MIT |

Official metadata: [xterm](https://registry.npmjs.org/@xterm/xterm/5.5.0),
[fit](https://registry.npmjs.org/@xterm/addon-fit/0.10.0),
[headless](https://registry.npmjs.org/@xterm/headless/5.5.0).
This is a deliberately pinned, tested version, not a claim that it is the latest release.

```text
@xterm/xterm: sha512-hqJHYaQb5OptNunnyAnkHyM8aCjZ1MEIDTQu1iIbbTD/xops91NB5yq1ZK/dC2JDbVWtF23zUtl9JE2NqwT87A==
@xterm/addon-fit: sha512-UFYkDm4HUahf2lnEyHvio51TNGiLK66mqP2JoATy7hRZeXaGMRDr00JiSF7m63vR5WKATF605yEggJKsw0JpMQ==
@xterm/headless: sha512-5xXB7kdQlFBP82ViMJTwwEc3gKCLGKR/eoxQm4zge7GPBl86tCdI0IdPJjoKd8mUSFXz5V7i/25sfsEkP4j46g==
```

Unmodified shipped third-party file SHA-256:

```text
xterm.js          1F991AC3B4B283EBF96E60AE23A00A52765DD3A2E46FA6FDDA9F1AAB032F7495
xterm.css         BA8E6985669488981CCF40C0CEFE3ABA80722CB6C92DE7AD628B0BD717FAF2B6
addon-fit.js      BDAEFA370B1BFC42EE88D46FE6072400902A4D4B2D45CD93438DDA9B23C97089
xterm-LICENSE     B569F629D00F2626A8100DF2A1798210535621E42164DFD426A6FE5AAC7B0CCD
addon-fit-LICENSE E256F01188AF527E4D06D21D06FBF785AE9C50D4B328BF03CBE0BA7F0AA4228F
```

The two complete MIT notices are adjacent to the shipped code. `index.html`, `terminal.js`,
`terminal.css` and this audit are project-authored. Third-party payload including notices is
298,861 bytes before APK compression. Source maps are not shipped and their requests are rejected.
The package bundles have no additional runtime package installation requirement.

## Native alternative: file-level audit and prototype

Inspected Termux `v0.118.3`, commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.
The [root licence](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/LICENSE.md)
states GPLv3-only for the repository and an explicit Apache-2.0 exception for Terminal Emulator for
Android code in `terminal-emulator` and `terminal-view`. It is incorrect to reject these modules
solely because the repository root is GPL. Conversely, this short exception should not be presented
as a full provenance clearance for every subsequent contribution.

All 21 production Java files below were downloaded and inspected for headers, imports and interface
coupling. None carries its own Apache/GPL/copyright header in this pinned revision. All reference
the root exception by directory membership; that fact does not establish individual contribution
history. No Termux source is included in this app.

| Directory | Files inspected | Additional observation |
| --- | --- | --- |
| `terminal-emulator/src/main/java/com/termux/terminal` | `ByteQueue.java`, `JNI.java`, `KeyHandler.java`, `Logger.java`, `TerminalBuffer.java`, `TerminalColorScheme.java`, `TerminalColors.java`, `TerminalEmulator.java`, `TerminalOutput.java`, `TerminalRow.java`, `TerminalSession.java`, `TerminalSessionClient.java`, `TextStyle.java`, `WcWidth.java` | `WcWidth` attributes tables to jquast/wcwidth, including commit `1b9b6585b0080ea5cb88dc9815796505724793fe`; a native distribution would need that separate provenance/licence review. |
| `terminal-view/src/main/java/com/termux/view` | `GestureAndScaleRecognizer.java`, `TerminalRenderer.java`, `TerminalView.java`, `TerminalViewClient.java` | `TerminalView.attachSession` accepts the concrete final `TerminalSession`. Input methods call that session directly. |
| `terminal-view/src/main/java/com/termux/view/textselection` | `CursorController.java`, `TextSelectionCursorController.java`, `TextSelectionHandleView.java` | Uses Android selection/clipboard APIs and module resources; resources/JNI/other non-Java files would require audit before native redistribution. |

The engine itself accepts `TerminalOutput` and byte input, so native rendering is technically viable.
A desktop-JVM prototype compiled 11 unmodified engine sources with `javac`, Android SDK constants,
and small test-only Android Base64/Color/Log/session stubs. It passed the same synthetic VT streams
as the xterm prototype: cursor positioning/erase, colour sequence handling, byte-fragmented UTF-8,
CJK double-cell width, alternate-screen restore and resize. This was **not** an Android TerminalView,
IME, accessibility, performance or APK-size comparison.

The native integration seam is the obstacle: `TerminalView` expects `TerminalSession`, a final
class whose `updateSize` starts a local process through JNI. `TerminalSession.write` targets a PTY,
not a WebSocket. A production remote-only view would require an audited fork of that seam plus
resources and IME/selection regression coverage. xterm provides the remote I/O seam directly.

## Implemented isolation and limits

- Only `https://terminal.meshcentral.invalid/` and six exact asset paths are served. Every other
  request receives 403. `blockNetworkLoads`, disabled file/content access, disabled storage and
  navigation rejection are independent safeguards. CSP rejects connections, frames, images,
  objects and forms. Scripts come only from bundled assets.
- The JavaScript bridge accepts terminal input, dimensions and acknowledgements only. It receives
  no server URL, cookie, SSH username/password/key, path or filesystem operation.
- Remote bytes enter `xterm.write(Uint8Array)` through base64. They never become JavaScript source
  or HTML. OSC 52 and OSC 8 are consumed; no terminal-driven clipboard writes or links are enabled.
  Copy requires the explicit Kotlin toolbar action and is capped at 262,144 characters.
- One 16 KiB render write is outstanding at a time. Kotlin pending output is bounded to 1 MiB;
  a full buffer suspends the background consumer until renderer acknowledgement frees capacity.
  The transport's eight-frame channel backpressures the OkHttp reader outside its connection lock.
  Close/cancellation wakes blocked senders and drops queued session data. An individual frame above
  1 MiB is still rejected explicitly; moderate bursts never trigger a queue-overflow failure. Scrollback is
  3,000 lines, screen dimensions are bounded to 500 columns and 300 rows. Input is capped at 64 KiB.
- WebView uses application context and is not saved. Compose detach/reattach retains the same VT
  instance; `TerminalController.close()` destroys it. Closed connections cannot send or reconnect.
  ViewModel disposal/server/account changes must call `close()`.
- SSH keeps authentication in Kotlin, always sends `keep:0` for new credentials and supports distinct
  `sshauth`, `sshkeyauth`, `sshautoauth`, `autherror`, `sessiontimeout`, `c`, `~data` and resize messages.
  No password/key model has a data-class `toString`; no saved-state or logging path was added.
- `c` confirms SSH authentication only. Input stays disabled until the first nonempty `~data`
  message proves that the shell stream is attached; a bare `~` does not count. A 30-second timeout
  after `c` closes a silent shell with a visible error. No delayed readiness guess, command queue or
  retry is used. This guards the confirmed MeshCentral 1.2.5 race where `c` precedes `sshClient.shell`.

## Verification and remaining gates

Completed before integration: Node syntax check; official package integrity checks; engine fixtures
in `app/src/test/java/ru/papajon/meshcentralmobile/terminal/renderer-fixtures.cjs`; native engine JVM
prototype described above. These are synthetic fixtures, **not captured live server transcripts**.

Added ten Kotlin controller tests, four byte-buffer concurrency tests and four Android renderer tests.
Transport tests cover slow-consumer bursts, ordering, close/cancel unblocking and oversize rejection.
The lead integrator owns
Gradle/emulator execution and records their actual results. In particular the Android tests exercise
bundled loading, ANSI/UTF-8 output, selection/copy and OSC isolation, real WebView `InputConnection`,
Ctrl input, disconnect rejection and detach/reattach. Test presence is not a pass result.

Still required: independent security/design review; physical Android IMEs (composition and repeat),
TalkBack/selection ergonomics, 320dp and short landscape/large text acceptance; real Windows/Linux
Agent and SSH server handshake/consent/rejection/resize, server host-key policy, network loss.
MeshCentral's audited SSH relay converts each incoming SSH chunk using Node `Buffer.toString()`;
fragmented UTF-8 at that server boundary remains an upstream limitation requiring a real fixture.
The Android renderer preserves split UTF-8 for raw Agent bytes.

### Android IME compatibility

An API 36.1 Android `InputConnection.commitText` trace showed only `beforeinput` and `input` with
`inputType=insertText`, with correct native/DOM focus and a ready controller. xterm 5.5.0
`src/browser/Terminal.ts:1172-1194` intentionally ignores that path when `screenReaderMode=true`.
The project adapter handles this direct, non-composing input while keeping accessible output enabled.
Keyboard and active/finalizing composition events remain handled by xterm to prevent duplicate input.
The vendor bundle and its checksums are unchanged. Android regression coverage includes direct CJK
commit, composing CJK commit, hardware input, Ctrl and disconnect rejection.
All four renderer tests passed on the dedicated API 36.1 emulator after this correction. Runtime
acceptance on API 26 remains a separate gate. Missing WebView construction, failed local script
initialization and a 15-second initialization timeout produce a localized update/reopen instruction.
