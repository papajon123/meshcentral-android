# Interface

The app is a control tool used while looking at someone else's desktop, so the interface is dark, quiet, and dense: the remote screen is the focus and the controls are a thin layer over it.

This document describes the current Compose interface. The UI is Russian; English documentation does not imply an English build.

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

## Switching remote displays

The dock has a display button between the pointer-mode button and the keyboard button. It shows the display the stream is currently on (`Все экраны`, `Экран 1`, `Экран 2`, …) and one tap moves to the next display in the agent's list, wrapping around. The full list with sizes is in **Сеанс → Экраны**.

The switch happens on the agent, so the phone receives one display at a time and keeps the whole canvas for it. A split-canvas experiment was rejected: on a phone the halves are small, zoom in one pane bleeds into the other, and a switching button is the clearer interaction.

## Language

The interface is Russian. User-visible strings live in `app/src/main/res/values/strings.xml` and are read with `stringResource`. The engine layer (`mesh/`, `input/`, `settings/`, `web/`) keeps its user-facing messages as Russian literals because those classes have no `Context`; the text is shown verbatim in snackbars, error banners, the connection overlay, and sheets.

Conventions:

- Product and protocol names stay in Latin script: MeshCentral, Mesh Agent, KVM, WebView, Android Keystore, `x-meshauth`, `noauth-2d`, `notools`.
- Key captions stay Latin (`Ctrl`, `Alt`, `Shift`, `Win`, `Esc`, `Tab`, `Enter`, `Backspace`, `Del`, `F1`–`F12`) because they name the physical keys of the controlled computer.
- Enum labels the model carries in English are translated at the UI boundary in `ui/components/StatusLabels.kt`, so the model stays language-neutral.
- The navigation label «ПК» is used instead of «Компьютеры» because the full word does not fit the tab.
- There is no second locale and no complete localisation framework yet; do not assume that changing the phone language changes the app.

## Screens

| Screen | File | Shape |
|---|---|---|
| Onboarding | `ui/screens/OnboardingScreen.kt` | Brand header, feature rows, the server form, and one action that saves the server and ends the flow. |
| Sign in | `ui/screens/LoginScreen.kt` | Lock badge, user name and password fields with a reveal toggle, two-factor card with e-mail/SMS/push request actions, status, and "continue without signing in". |
| Computers | `ui/screens/DevicesScreen.kt` | Large title with live "n of m online", search field, All/Online/Favourites chips, grouped device cards with status, OS/address, favourite star, and Connect or a reason. |
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

Three destinations — **ПК**, **Сеанс**, **Настройки** — in a floating pill bar. Signing in and choosing a server are flows inside those destinations rather than separate tabs: the sign-in screen appears when the session is not authenticated and can be dismissed, onboarding runs once on first launch, and the session hides the tab bar so the canvas owns the window.

## Motion

- Navigation pills and session dock items scale from a pressed state and never move layout.
- The session HUD and dock slide in and out; opacity uses the critically damped effect springs.
- `MeshLoadingIndicator` morphs a rotating rounded square between square and circle corners, built from primitives available on this Compose version.

## Design references

The visual direction follows Material 3 guidance (colour roles, shape variety, emphasised typography, spring motion) and the interaction conventions of mainstream mobile remote-desktop clients. The app does not bundle or depend on any third-party design skill, icon set, or screenshot.
