# MeshPilot — Signal design system

## Product and scope

A native Android control client for a user's MeshCentral server. The primary task is finding a computer and opening its desktop, terminal or files safely. Phones and resizable tablet windows are supported; this is not an Android TV application. Existing authentication, transports, rights checks, session ownership, storage, application IDs and update identities remain authoritative.

## Direction

Considered: (1) bright consumer-style color blocking, (2) a black technical console, (3) mineral surfaces with a clear teal signal. Choose **Signal**: expressive at entry, selection and connection; quiet around remote content and dense settings. It is distinct from the former black/blue interface, supports daylight use and avoids competing with the remote desktop. Amber means caution, red means error/destruction, green means available. Status always also has text or an icon.

## Compatibility decisions

- Keep the existing Kotlin/Compose/Material 3 build. Expressive principles use the stable APIs available in the project; do not claim newer experimental Material components are present.
- No Navigation 3, experimental Grid/FlexBox, AGP or SDK migration solely for styling. The current state-based destinations and ViewModel-owned sessions remain intact.
- Keep 22 languages/23 locale variants, system language detection and RTL. New appearance strings must exist in every locale.
- No server, infrastructure, signing-key, release automation or publication changes. Work stays on local `redesign/material-expressive` branches.

## Tokens

- Palette: mineral light/dark surfaces, teal primary, muted blue-gray secondary, warm amber tertiary. Every color literal belongs in `ui/theme/Color.kt`; semantic transport colors have matching light/dark containers.
- Typography: platform sans for all scripts; compact readable body text and stronger titles, with monospace reserved for paths/identifiers/terminal data. No fixed text heights; retain line-height space for CJK and Arabic.
- Spacing: 4, 8, 12, 16, 24, 32 dp. Content gutters 20–24 dp; readable forms bounded to 640 dp, multi-region layouts on larger windows.
- Shapes: 8 dp utility elements, 12–16 dp grouped surfaces, 24–28 dp form panels, capsule selection indicators. Do not wrap every row in a card.
- Touch targets at least 48 dp; visual icons can be smaller. All icon actions have accessible labels and focus/ripple feedback.
- Motion: existing `MeshMotion` spring tokens, rapid effects and restrained spatial movement. Read system animation scale; no decorative infinite animation when disabled.

## Theme and navigation

System/light/dark selection is an independent appearance preference and must not rewrite existing settings or vault contents. Optional dynamic color uses system surface roles while retaining the teal action identity and tested semantic pairs. The remote canvas stays black in either app theme.

The same three destinations stay in the same order: computers, session, settings. Compact windows use a clearly labelled navigation bar; wider windows use a rail. A remote session and device tools retain their focused layout and explicit back control. Changing the presentation must not recreate or reconnect a session. Preserve browser fallback, busy-file confirmation, full-screen mode and all existing tool tabs.

## Screen implementation contract

1. Setup/server: concise introduction, recognizable server identity, one primary save action, inline validation and IME-safe scrolling.
2. Sign-in: clear endpoint, grouped credentials/2FA, one primary action, errors with recovery and secondary guest entry.
3. Lock: focused PIN hierarchy with one lock icon, readable masked progress, accessible keypad, existing zero/backspace/biometric semantics.
4. Computers: prominent title and connection context; compact filters; grouped device rows with separate favorite/connect actions and honest availability.
5. Device overview: structured identity/metadata and distinguishable tool choices; disabled tools retain their reason; tabs remain context navigation.
6. Desktop: uninterrupted canvas; legible compact HUD and grouped dock; all pointer, keyboard, clipboard, display and session sheets retained.
7. Terminal/SSH: compact status/controls, maximum terminal area, readable auxiliary keys and the existing authentication flow.
8. Files/SFTP: actual file rows with icons/type/size hierarchy, LTR path surface, wrapping action toolbar and distinct transfer progress/confirmation.
9. Settings: appearance, server, security/session, data and about groups; calm labelled rows, immediate switches and separated destructive actions. Retain the private build's updater when porting.
10. Sheets/dialogs: shared shape, title/action hierarchy, safe insets, large text, focus return and all dismissal/confirmation callbacks.

## Acceptance

Capture actual production Composables with synthetic data before and after. Cover setup, validation, sign-in, lock, computers (populated/empty/error), device details, desktop, terminal, files, settings and modal surfaces. Inspect the actual PNGs, not only semantics. Test both themes, narrow/landscape/tablet windows, large fonts, EN/RU/ZH and RTL, IME, Back, state restoration and reduced motion. Existing unit, native input, locale, session and transfer tests remain in force. Compare scoped frame/memory measurements on the same emulator; do not claim physical-device performance or a leak audit from one local run.

Evidence, source versions, commands and known gaps live in `docs/redesign/`; external guides are referenced with pinned commits and their original licenses. Screenshots use example hosts and synthetic identities only.

## Reference refinement

Actual app screenshots from Termius and Solid Explorer on Google Play were reviewed on 2026-10-21. Borrow the task-oriented hierarchy: short toolbars, meaningful row metadata, clear selection, actions next to their object. Do not copy promotional artwork or brand assets. Setup is a utility form with a compact app signature, not a marketing hero. Avoid repeated explanatory copy and oversized colored panels. See the reference links in the redesign report.
