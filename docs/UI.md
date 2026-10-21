# Interface

The app is a control tool used while looking at someone else's computer, so the interface is dark, quiet, and dense: the remote screen or terminal is the focus and the controls are a thin layer around it.

This document describes the Compose interface prepared for 0.5.1. The default interface language is English; the release ships 22 languages in 23 locale variants and follows the system language.

## Design tokens

All tokens live in `app/src/main/java/ru/papajon/meshcentralmobile/ui/theme/`.

| File | Contents |
|---|---|
| `Color.kt` | Material 3 dark colour scheme plus `MeshColors`: online, offline, warning, danger, view-only, latency quality, glass, and canvas colours. |
| `Type.kt` | Type scale with tighter line heights, emphasised hero text, tabular numerals for live values, and a monospace style for identifiers. |
| `Shape.kt` | Shape scale (10/14/20/26/34 dp) plus pill, squircle, and sheet shapes. |
| `Motion.kt` | Spring tokens for spatial movement, effect fades, colour changes, and shared spacing. |
| `Theme.kt` | Provides colours, motion, spacing, and a `Surface` so `LocalContentColor` is correct. |

Rules the code follows:

- Springs for anything interruptible: spatial springs for movement, critically damped springs for colour and alpha.
- Colour by role: `primary` for the single main action, `secondaryContainer` for selected navigation and cards, `tertiary` for favourites, error containers for problems.
- Varied shapes rather than one radius everywhere: pill chips and buttons, 14–20 dp cards, 26–34 dp sheets and badges, a squircle for the session dock.
- Emphasised typography at hero moments (screen titles, device names, live metrics) and quiet supporting text elsewhere.
- 48 dp touch targets and content descriptions for icon-only controls are the target; accessibility is checked as part of UI review rather than assumed from the design.

## Touch model in a live session

The gesture model follows common mobile remote-desktop behaviour: a tap clicks, a long press is the right button, a long press that drags drags on the computer, and two fingers move or zoom the view.

| Gesture | Direct touch | Trackpad | Zoom / pan |
|---|---|---|---|
| Tap | left click at that point | left click at the pointer | — |
| Drag | move the view | move the pointer | move the view |
| Long press | right click | right click | — |
| Long press + drag | drag on the computer | drag on the computer | — |
| Double tap | double click | double click | — |
| Two-finger drag | pan the view | scroll the computer | pan the view |
| Two-finger tap | right click | right click | — |
| Pinch | zoom the view | zoom the view | zoom the view |

Implementation rules:

- **The remote pointer is drawn by the client.** MeshCentral sends only the cursor *shape* index (command 88), never its position, and the agent does not paint the cursor into the tiles. The session draws a white arrow with a dark outline at the last position it sent, so the screen does not look frozen when clicks land correctly.
- **Typing uses the phone's own keyboard.** A hidden text field replaces its `InputConnection`: committed characters become MeshCentral Unicode key packets, Backspace and the arrow keys become normal key packets, and composing text is ignored so a word under IME suggestions is typed once, when the IME commits it.
- **The view can always move.** At fit zoom the framebuffer is not larger than the canvas, so the viewport allows a bounded free pan (35% of the canvas) and **Fit** recentres it.
- **Zoom keeps the touched pixel still** (`RemoteViewport.zoomAround`), so pinch feels attached to the content. Zoom is clamped between 1x and 16x.
- **A tap outside the picture is ignored.** Without that check, a tap in the black letterbox would clamp to the nearest framebuffer edge and click whatever sits there.
- **A moving finger is never a long press.** The long-press timer is cancelled as soon as the pointer moves beyond the tap slop.
- Debug builds show a live readout above the dock and a dismissible gesture guide once per session.

## Device details and tool tabs

Opening a computer from the list now leads to a device screen headed by its name, with a scrollable tab row: **Details**, **Desktop**, **Terminal**, **Files**, **SSH**, **SFTP**. The status of each connection is visible on the Details tab, so an unavailable tool is not a silent dead end.

- Details shows group, operating system, address and Agent version, plus one card per connection. Cards explain the current availability, for example offline, unsupported, no permission, or “support or permission is unknown; the server will check”.
- The Desktop tab keeps the existing native KVM flow. The Terminal and Files tabs control the Mesh Agent; SSH and SFTP use the server's SSH relays.
- A known denial disables the tab. Unknown metadata enables an explicit attempt, with the server's answer shown in the tool surface.
- Only one tool is selected at a time. Choosing another tab or opening Desktop closes the previous tool.
- **Open web panel** launches the configured MeshCentral URL in a browser with a separate sign-in. It is the handoff for functions outside the native client and for entries that must not be operated from the app.

## Terminal

The terminal screen is a full-height local xterm renderer with a compact status line and a horizontally scrollable control strip: Back, Disconnect, Keyboard, Ctrl, Esc, Tab, arrows, Enter, Select all, Copy selection and Clear selection. On-screen status distinguishes connecting, waiting for the computer/consent, authentication required, waiting for SSH shell output, connected, ended and failed states.

The renderer follows the app theme and font scale. It receives no server address or credentials; a narrow local bridge carries input, resize, ready and consumed signals. A server-reported recording marker is shown as a plain warning. When a session ends, the scrollback may remain visible for selection, input is disabled, and the user is told to reopen the tab; the UI never resends previous input automatically.

The terminal requires Android System WebView. If it cannot start, the error area gives an update/reopen instruction instead of replacing failure with a generic blank view.

## Files and SSH/SFTP

The Files screen uses a list with a path header, Parent/Refresh/New folder/Upload actions, transfer progress, and a message region. Tapping an entry opens a sheet with Open, Download, Rename and Delete as applicable. Download first asks for a SAF destination; upload first asks for a source through SAF. Destructive changes use confirmation dialogs, and uploads show an overwrite warning. Read-only entries explain that the name is unavailable and leave the web panel as the safe handoff.

SSH and SFTP authentication appears inline where the server requests it. The password, private-key and passphrase fields use password visual transformation, are not `rememberSaveable`, and are cleared after submission. The app does not offer to save SSH credentials.

## Switching remote displays

The dock has a display button between the pointer-mode button and the keyboard button. It shows the current display (`All displays`, `Display 1`, `Display 2`, … in English; labels follow the selected locale) and one tap moves to the next display in the agent's list, wrapping around. The full list with sizes is in **Session → Displays**.

The switch happens on the agent, so the phone receives one display at a time and keeps the whole canvas for it. A split-canvas experiment was rejected: on a phone the halves are small, zoom in one pane bleeds into the other, and a switching button is the clearer interaction.

## Language

The default interface language is English. The 0.5.1 interface ships 22 languages in 23 locale variants: English, Russian, Simplified Chinese, Traditional Chinese, Spanish, French, Portuguese, German, Arabic, Hindi, Bengali, Urdu, Japanese, Korean, Indonesian, Turkish, Vietnamese, Thai, Italian, Persian, Tamil, Telugu, and Marathi. Simplified and Traditional Chinese are counted as one language and two locale variants. Each locale catalog contains the same 529 translated strings; the shared application name is not translated.

- Android 8–12 follows the system language. Android 13+ also exposes the app in the system per-app language settings; there is no separate in-app language picker.
- When no supported locale matches, Android falls back to the default catalog, which is English.
- Arabic, Persian, and Urdu use a right-to-left layout; the session navigation arrow uses `Icons.AutoMirrored.Outlined.ArrowBack` and is mirrored automatically.
- User-visible strings live in `res/values/strings.xml` and `res/values/runtime_strings.xml`, with one catalog per `values-*` locale directory. Runtime messages from `mesh/`, `input/`, `settings/`, and `web/` are referenced through `UiText.Resource`, so a language change re-renders pending status text. Server-provided text stays `UiText.Literal` and is shown verbatim.
- Server messages, device names, and physical key captions are not translated.

Conventions:

- Product and protocol names stay in Latin script: MeshCentral, Mesh Agent, KVM, WebView, Android Keystore, `x-meshauth`, `noauth-2d`, `notools`.
- Key captions stay Latin (`Ctrl`, `Alt`, `Shift`, `Win`, `Esc`, `Tab`, `Enter`, `Backspace`, `Del`, `F1`–`F12`) because they name the physical keys of the controlled computer.
- Enum labels use resource identifiers or are mapped to resources in `ui/components/StatusLabels.kt`; text is resolved when rendered, so the model does not cache a language.
- The navigation uses the short label **PC**; the full name **Computers** is used on the devices screen.
- Translations were produced with DeepSeek and have not yet been proofread by native speakers of every language.

## Screens

| Screen | File | Shape |
|---|---|---|
| Onboarding | `ui/screens/OnboardingScreen.kt` | Brand header, feature rows, the server form, and one action that saves the server and ends the flow. |
| Sign in | `ui/screens/LoginScreen.kt` | Lock badge, user name and password fields with a reveal toggle, two-factor card with e-mail/SMS/push request actions, status, and "continue without signing in". |
| Computers | `ui/screens/DevicesScreen.kt` | Large title with live "n of m online", search field, All/Online/Favourites chips, grouped device cards with status, OS/address, favourite star, and Connect or a reason. |
| Device | `ui/screens/DeviceScreen.kt` | Computer name, scrollable Details/Desktop/Terminal/Files/SSH/SFTP tabs, availability cards, web-panel handoff, and the selected tool surface. |
| Terminal | `ui/screens/TerminalScreen.kt` | Local xterm canvas, connection/auth status, recording notice, reconnect instructions, keyboard/control strip, explicit selection and copy. |
| Files / SFTP | `ui/screens/FilesScreen.kt` | Path header, parent/refresh/new folder/upload, progress, file list, per-entry sheet, SAF pickers, confirmations, optional SSH/SFTP authentication form. |
| Session | `ui/screens/RemoteDesktopScreen.kt` | Edge-to-edge canvas, floating HUD (back, computer, latency, hide), zoom pill, dock with pointer / keys / clipboard / more, connection stage overlay. |
| Pointer sheet | `ui/screens/session/SessionSheets.kt` | Pointer modes with descriptions and an active marker, plus double-click and drag controls. |
| Keyboard sheet | `SessionSheets.kt` | Modifier chips, key grid (Esc/Tab/Enter/Backspace/Del/arrows/F1–F12), shortcut chips. |
| Text sheet | `SessionSheets.kt` | Draft field with Send / Paste / Copy / Clear. |
| Clipboard sheet | `SessionSheets.kt` | Two large actions (get from computer / send to computer), transfer status, and preview. |
| Session options | `SessionSheets.kt` | Displays, geometry, quality and frame-rate sliders with presets, redraw, reconnect, full screen, hide controls. |
| Settings | `ui/screens/SettingsScreen.kt` | Status strip plus Updates, Server, Security, Session, Data, and About sections. |
| Server setup | `ui/screens/ServerSettingsScreen.kt` | Endpoint card with validation, debug-only switches, and one save action. |
| Lock | `ui/screens/LockScreen.kt` | Lock badge, digit dots, feedback line, 3×4 keypad, Unlock and optional biometrics. |

Shared components live in `ui/components/Common.kt` (`MeshCard`, `SectionHeader`, `EmptyState`, `MetricChip`, `StatusPill`, `StatusDot`, `IconBadge`, `MeshLoadingIndicator`, `MeshSheet`, `SheetHeader`) and `ui/components/Header.kt` (`MeshScreenHeader`, `MeshNavigationBar`).

## Navigation

Three destinations — **PC**, **Session**, **Settings** — in a floating pill bar. Signing in and choosing a server are flows inside those destinations rather than separate tabs: the sign-in screen appears when the session is not authenticated and can be dismissed, onboarding runs once on first launch, and the session hides the tab bar so the canvas owns the window.

The adaptive-layout work included in 0.5.1 keeps the active label visible: on narrow windows with a
font scale of at least 1.3, it occupies a full-width caption below the three touch targets. Normal
text sizes retain the inline active pill. The introduction and server form share one scroll area;
2FA delivery actions wrap to additional rows. URL values use LTR text direction within RTL screens.
See [validation](VALIDATION.md) for tested cases and the distinction between resizing a Compose
viewport and recreating an Android Activity. The device and tool tabs add new surfaces that still
need the full physical-device, font-scale, RTL and TalkBack pass.

## Motion

- Navigation pills and session dock items scale from a pressed state and never move layout.
- The session HUD and dock slide in and out; opacity uses the critically damped effect springs.
- `MeshLoadingIndicator` morphs a rotating rounded square between square and circle corners, built from primitives available on this Compose version.

## Design references

The visual direction follows Material 3 guidance (colour roles, shape variety, emphasised typography, spring motion) and the interaction conventions of mainstream mobile remote-desktop clients. The app does not bundle or depend on any third-party design skill, icon set, or screenshot.
