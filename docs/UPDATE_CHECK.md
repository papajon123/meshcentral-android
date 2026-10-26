# Opt-in GitHub update checks

The public client reads the ten latest public releases, including prereleases, and selects the highest
verified `versionCode`. Tags must be `vX.Y.Z`; the release must contain the matching APK,
`release-info.json` and `SHA256SUMS`. Metadata must agree on the application ID, version name, APK size,
checksum and the certificate pinned in `UpdatePolicy.CERTIFICATE_SHA256` from `ci/release-identity.json`.
The installed package and its single current signer must match before an update is offered.

This checks metadata, not APK bytes. The APK is never downloaded by the client. **Download** opens
the computed GitHub release page using `ACTION_VIEW`; installation is the user's choice in Android.
Release tags and response bodies never become arbitrary browser URLs or log messages.

Consent is requested once per installed `versionCode`, after the app lock if enabled, including before
server setup. Dismissal means **Not now**. Consent and update preferences are global, independent of
server profiles. A new installed version needs a new answer before requests can resume.

Both automatic foreground entry and **Check now** obey a 24-hour interval. Errors, cancellation and
restarts do not bypass it. **Later** hides a version for 24 hours; **Skip this version** hides that code
permanently, including on a manual check. A higher version remains eligible. The saved candidate is
revalidated locally before display. No update worker, service or installation permission is added.

Requests use an independent OkHttp client with platform TLS validation, no auth/cookies, a generic
app User-Agent, bounded response bodies and timeouts. Only fixed GitHub metadata addresses and HTTPS
redirects to `release-assets.githubusercontent.com` are accepted. HTTP 403/429 has a separate result;
invalid-only release lists are not presented as a successful up-to-date check. See [Privacy](../PRIVACY.md).
GitHub's public endpoint is documented in [List releases](https://docs.github.com/en/rest/releases/releases#list-releases).

## Repeatable checks

In PowerShell, with the JDK and Android SDK configured:

```powershell
python ci/check_translations.py
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

`GitHubReleaseSourceTest` uses synthetic JSON fixtures and MockWebServer, with no external network.
`UpdatePolicyTest` covers consent, timing, skipped/deferred versions and signer/version compatibility.
The optional live test is disabled by default. To explicitly check the public release expected by M2.3:

```powershell
$env:MESH_UPDATE_LIVE_CHECK = '1'
.\gradlew.bat testDebugUnitTest --tests 'ru.papajon.meshcentralmobile.update.GitHubReleaseSourceLiveTest'
Remove-Item Env:MESH_UPDATE_LIVE_CHECK
```

It expects v0.7.3 / code 18, so a future release requires updating that explicit acceptance expectation.
It performs one source scan (list plus metadata GETs), without credentials, APK downloads or saved
GitHub responses. Routine builds must not require the network test.

## Manual Android acceptance (not performed in M2.3)

Use an isolated emulator/device with synthetic server information. For this assignment only AVD
`Medium_Phone_API_36.1`, read-only, port 5574 is permitted, and only with more than 6 GiB of free memory.
Do not use a running personal emulator. Stop the dedicated emulator after testing.

1. Fresh install: the dialog appears once. Choose **Not now**, recreate the Activity and restart the
   process: no second prompt for this code and no GitHub traffic. In Settings the switch is off and
   **Check now** is disabled. Test back/outside dismissal too.
2. Enable the switch: the notice explains GitHub/CDN network metadata. A foreground check shows progress,
   then an honest result and localized attempt time. Repeat the button and restart: no additional scan
   within 24 hours. Disable during a request: it is cancelled and cannot restore an enabled state/card.
3. Offline / 403 / malformed metadata: see the appropriate result, never “no newer release”. A restart
   keeps the result and attempt time. A changed installed version prompts once again before networking.
4. On a build signed with the public identity and an older installed code, verify the candidate card
   and device-list banner. A debug APK is intentionally incompatible; do not bypass the signer check
   or copy production keys into a test build. Synthetic candidate UI fixtures can test rendering.
5. **Download** opens exactly `https://github.com/papajon123/meshcentral-android/releases/tag/vX.Y.Z` in
   a browser. With no browser, show the inline error. The app itself does not fetch the APK.
6. **Later** and **Skip this version** hide both surfaces, persist across restarts, and remain effective
   if pressed while a new check is running. Later expires after a day; a newer code survives a skip.
7. Check light/dark, 360 dp portrait, landscape, tablet, large font, English/Russian/Chinese and Arabic
   RTL. Actions wrap, settings scroll, the banner scrolls with a populated list, and keyboard/TalkBack
   can operate the switch and buttons. Rotate during the consent dialog and network request.

No emulator was started during implementation because free RAM was below the required threshold.
Physical-device behavior, visual layout and browser/installation acceptance remain for the reviewer.
