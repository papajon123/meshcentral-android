# Windows data and terminal fixes

## JSON null

Android's `JSONObject.optString` coerces `JSONObject.NULL` to the string `"null"`, even
when a fallback is supplied. The JVM test dependency does not behave the same way.
`json/JsonText.kt` checks `isNull` before delegating to `optString`, keeping existing
number/string coercions and fallbacks. All production `optString` reads use this helper, including the phone `sysinfohash`
event dispatch added on the newer main branch. Its known-device lookup and phone
capability logic remain unchanged.
The literal JSON string `"null"` remains data. Typed `opt` casts and guarded scalar
parsers were also checked; they already exclude the null sentinel. Android `getString`
has the same coercion trap: file listings now require an actual string filename,
and the phone adapter rejects outgoing null paths while retaining incoming null
paths for the listing-path fallback. Other `getString` reads either construct local
commands or validate the result before using it.

JVM regression tests emulate Android's behavior at the parser boundary. The Android
tests use the real platform JSON implementation and exact synthetic control frames.

## Windows command encoding

MeshCentral 1.3.1 `agents/meshcore.js`, `runcommands` (lines 1974–2036), launches
`cmd.exe` for type 1 and Windows PowerShell with `-noprofile -nologo -command -` for
type 2. Both append each stdout/stderr buffer using `c.toString()`, then send a JSON
`{action:"msg",type:"runcommands",result:...,responseid:...}` reply. The reply carries
neither original bytes nor a code-page identifier. The web console receives the same
converted text and HTML-escapes it without a Windows-code-page conversion
(`views/default.handlebars`, `p15consoleReceive`, lines 14274–14291).

A Russian Windows OEM CP866 fixture for `Привет\r\n` is
`8f e0 a8 a2 a5 e2 0d 0a`. It was verified with a separate, hidden local cmd process
using CP866. Console code pages can differ per process; Android must not assume every
Windows response is CP866 or reinterpret arbitrary Unicode as Latin-1.
The original live Windows report did not retain raw output bytes, so its exact
process code page cannot be proved retrospectively. MeshAgent's Buffer conversion
can already lose byte information before Android sees JSON. A local standalone
probe of the pinned legacy agent executable was blocked by Microsoft Defender;
no security settings were changed.

The default type-0 request and command body remain unchanged. On Windows, users can
explicitly select **PowerShell 5+ (UTF-8)** before reviewing and confirming a command.
This sends type 2, sets console input/output and `$OutputEncoding` to UTF-8, and
dot-sources an ASCII-encoded envelope containing the original UTF-8 command body.
The envelope is necessary: a local test with the actual `-command -` invocation
showed that changing `InputEncoding` alone still corrupts Cyrillic already buffered
by PowerShell's stdin reader. The envelope test returned `Привет, мир` as the exact
UTF-8 bytes, with no stderr and exit code zero.

The mode requires Windows PowerShell 5 or newer and normal language permissions; it
does not bypass Constrained Language/WDAC policy. Three local synthetic cases also
passed with CREATE_NO_WINDOW (redirected stdin/stdout, no console): Cyrillic output,
multiline variables, and an explicit nonzero exit.

The confirmation identifies the selected shell and displays the original body.
This is an explicit shell choice, not a heuristic repair of received text. Programs
that choose their own output encoding, or agent-side chunk-boundary conversion,
remain limitations. The client does not infer command success from its output.

## Favorite commands and the terminal grid

Favorites already sent literal input to the relay; they did not populate an overlay
editor or append Enter. However, the dialog keyboard could temporarily resize the
underlying xterm grid and remote PTY. An absolute-cursor shell redraw could then put
the inserted text below the old prompt. Freeze fitting while the favorites dialog
is open and resume after the native viewport settles on dismissal. Cancel pending
settle work on reopening/destroying the renderer. Layout and window-focus events
schedule a single debounced fit; background views do not poll, and no timeout forces
a fit while the viewport is still changing. No terminal input is queued or replayed.

The regression uses real bundled xterm, a synthetic relay, a temporarily shortened
viewport and absolute ANSI cursor positioning. With the old JavaScript, it observed
an unwanted remote resize. With the fix, the prompt and echoed favorite must remain
on one line and the relay receives the text once; Enter is a separate action.
The original Windows/PowerShell scenario still needs a real Windows acceptance run.

## BIOS release date

Only complete, valid CIM_DATETIME timestamps (`yyyyMMddHHmmss.ffffff±UUU`) are
recognized. Format their calendar date in the Android resource locale without a
timezone conversion that could change the BIOS release day. Null remains unknown;
invalid, partial, interval and non-CIM strings are displayed unchanged.
