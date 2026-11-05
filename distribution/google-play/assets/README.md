# Google Play graphics

- `icon.png`: the existing public MeshPilot icon, RGBA PNG, 512 × 512, below 1 MiB.
- `featureGraphic.png`: the existing public feature graphic, RGB PNG, 1024 × 500.
- `en-US/phoneScreenshots/` and `ru-RU/phoneScreenshots/`: four RGB PNGs per language, 1080 × 1920. Accessible descriptions are in `screenshots.json`.

The maintainer confirmed that the icon and feature graphic were created with AI. Only those two assets are declared as AI-generated in Play Console; the eight native screenshots are not.

The phone images were captured from the production Compose components of the Play variant on an isolated Android 16 emulator with synthetic data. They were copied unchanged from the native captures. Both languages were visually reviewed. The selected views show the computer list, device tools, settings and PIN lock; debug configuration screens are excluded from the listing.

The original 1080 × 2400 images in the general Fastlane metadata exceed Play's maximum screenshot aspect ratio of 2:1. Use this directory for Google Play. Dimensions and PNG modes were checked against the [Google Play preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

To regenerate, install the Play debug and instrumentation APKs on a dedicated emulator, temporarily set its resolution to 1080 × 1920, and run `ci/capture-redesign.py` with `--locale en` and `--locale ru`. Restore the emulator resolution afterwards. Select these four generated files in order:

1. `redesign-after-devices-loaded.png`
2. `redesign-after-device-overview-available.png`
3. `redesign-after-settings-has-pin.png`
4. `redesign-after-lock-empty.png`

Do not substitute the onboarding or server-configuration debug captures for release screenshots.
