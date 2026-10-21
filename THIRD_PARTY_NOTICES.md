# Third-party notices

This project is distributed under Apache-2.0. This notice identifies the upstream
protocol reference and direct runtime dependencies; their own licenses continue
to apply. Gradle resolves transitive dependencies from the declared public repositories.

| Component | License | Source |
| --- | --- | --- |
| MeshCentral protocol reference | Apache-2.0 | https://github.com/Ylianst/MeshCentral/blob/master/LICENSE |
| AndroidX / Jetpack Compose / Material components and icons | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt |
| Kotlin and kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt ; https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt |
| OkHttp / Okio | Apache-2.0 | https://github.com/square/okhttp/blob/master/LICENSE.txt ; https://github.com/square/okio/blob/master/LICENSE.txt |

JUnit, AndroidX Test, MockWebServer and the JVM JSON library are test dependencies
and are not intentionally included in the release APK. Gradle and Android build
tools are build dependencies. Their distributions retain their own notices.

No upstream MeshCentral logo, captured remote desktop image, or private server
data is distributed with this source snapshot. KVM regression fixtures use
protocol headers and synthetic image payloads, as documented in the tests.
