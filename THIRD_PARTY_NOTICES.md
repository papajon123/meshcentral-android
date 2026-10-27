# Third-party notices

This project is distributed under Apache-2.0. This notice identifies the upstream protocol reference and direct runtime dependencies; their own licenses continue to apply. Gradle resolves transitive dependencies from the declared public repositories.

| Component | License | Source |
| --- | --- | --- |
| MeshCentral protocol reference | Apache-2.0 | https://github.com/Ylianst/MeshCentral/blob/master/LICENSE |
| AndroidX / Jetpack Compose / Material components and icons | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt |
| Kotlin and kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt ; https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt |
| OkHttp / Okio | Apache-2.0 | https://github.com/square/okhttp/blob/master/LICENSE.txt ; https://github.com/square/okio/blob/master/LICENSE.txt |
| ZXing Core 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing/tree/zxing-3.5.3/core |
| xterm.js `@xterm/xterm` 5.5.0 | MIT | https://registry.npmjs.org/@xterm/xterm/5.5.0 |
| xterm.js fit addon `@xterm/addon-fit` 0.10.0 | MIT | https://registry.npmjs.org/@xterm/addon-fit/0.10.0 |

## Bundled terminal renderer provenance

QR invitations use only `com.google.zxing:core:3.5.3` (no Android wrapper, camera permission or runtime dependencies). The upstream license from tag `zxing-3.5.3` is included in the APK at `assets/licenses/zxing-LICENSE`. Source: https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE. Copyright ZXing authors.

The APK bundles these unmodified third-party files:

| Path | Package/version |
| --- | --- |
| `app/src/main/assets/terminal/xterm.js` | `@xterm/xterm` 5.5.0 |
| `app/src/main/assets/terminal/xterm.css` | `@xterm/xterm` 5.5.0 |
| `app/src/main/assets/terminal/xterm-LICENSE` | `@xterm/xterm` 5.5.0 MIT notice |
| `app/src/main/assets/terminal/addon-fit.js` | `@xterm/addon-fit` 0.10.0 |
| `app/src/main/assets/terminal/addon-fit-LICENSE` | `@xterm/addon-fit` 0.10.0 MIT notice |

Provenance is recorded in `app/src/main/assets/terminal/RENDERER-AUDIT.md`: official npm metadata/tarballs were fetched, the tarball SHA-512 matched the published `dist.integrity`, and both packages came from upstream git revision `9ba6c00a195c95fcf8292a2b9084d91450e5daae`. The audit also lists the SHA-256 of each shipped file. `@xterm/headless` 5.5.0 was used only for Node-based verification and is **not** in the APK. Source maps are not shipped. `index.html`, `terminal.js`, `terminal.css`, and the audit are project-authored.

The complete MIT notices are reproduced below, exactly as shipped beside the code.

### xterm.js 5.5.0

```text
Copyright (c) 2017-2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)
Copyright (c) 2014-2016, SourceLair Private Company (https://www.sourcelair.com)
Copyright (c) 2012-2013, Christopher Jeffrey (https://github.com/chjj/)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### xterm.js fit addon 0.10.0

```text
Copyright (c) 2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

JUnit, AndroidX Test, MockWebServer and the JVM JSON library are test dependencies and are not intentionally included in the release APK. Gradle and Android build tools are build dependencies. Their distributions retain their own notices.

No upstream MeshCentral logo, captured remote desktop image, real terminal transcript, or private server data is distributed with this source snapshot. KVM regression fixtures use protocol headers and synthetic image payloads, as documented in the tests.
