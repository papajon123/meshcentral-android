## MeshPilot 0.10.5 candidate

MeshPilot is an independent client for MeshCentral servers, not affiliated with or endorsed by the MeshCentral project.

**Android verification:** this candidate retains the 0.10.1 improvements and corrects the Android 8 TLS test-server fixture. Incomplete instrumentation runs cannot be accepted through partial retries. The application's TLS trust and signing identity are unchanged.

**Release verification:** ignore resource names in jarsigner's entry listing when checking certificate diagnostics. Strict signatures and the pinned signing identity remain required. Real document-picker checks use Window Manager focus, read the complete system output so Android 8 keeps the test connection alive, and clean up the picker before removing its callback.

**System file picker:** opening and creating documents use Fragment 1.9.1. Real Android picker and cancellation tests cover the Activity Result path, while device-credential fallback remains available.

**File selection:** the transfer service stays active while Android's file picker is open. A cancelled picker cannot supply the result of a later upload or delete the source document.

**Unlock and languages:** without an app PIN, device authentication accepts biometrics or Android's screen lock. With an app PIN, that PIN remains the fallback. The app UI can be set to any of 23 languages.

**Inspector while loading:** computer-inspector tabs stay selectable while details load, so a slow read no longer traps you on one tab.

**Remote keyboard layout:** an optional per-computer setting sends physical keys as remote virtual keys and phone-composed text as Unicode, keeping the phone IME unchanged.

**User consent:** new desktop, terminal and file sessions can ask the computer user for permission. The server and agent decide, and their automatic approval rules still apply.

**Session navigation:** the main navigation stays available during an ordinary session; only explicit fullscreen hides it.

**Build and distribution:** AGP 9.5.0, Gradle 9.8.2 and OkHttp 5.5.1. The Play distribution hides the external updater and donation link and ships offline English and Russian privacy texts. The package name and signing configuration are unchanged. A debug-build APK upgrade from 0.8.4 to 0.10.0 preserved encrypted credentials.

**Русский:** вход по биометрии или блокировке экрана Android, в том числе без PIN приложения; заданный PIN по-прежнему доступен. Интерфейс переключается между 23 языками. Вкладки сведений о компьютере не блокируются, пока идёт загрузка. Необязательная раскладка удалённой клавиатуры передаёт физические клавиши как виртуальные клавиши удалённого компьютера, а набранный на телефоне текст — как Unicode, не меняя IME. Для новых сеансов рабочего стола, терминала и файлов можно запрашивать согласие пользователя компьютера; решение принимают сервер и агент, их правила автоодобрения действуют. Во время обычного сеанса основная навигация остаётся на экране и скрывается только в полноэкранном режиме. Обновлены AGP 9.5.0, Gradle 9.8.2 и OkHttp 5.5.1. В сборке Play скрыты внешнее обновление и карточка поддержки, добавлены офлайн-тексты политики на английском и русском. Имя пакета и настройка подписи сохранены; отладочное обновление с 0.8.4 сохранило зашифрованные учётные данные.

**Исправление 0.10.1:** устранено падение при открытии системного выбора или сохранения файла. Проверены настоящие окна Android и отмена выбора; разблокировка через блокировку экрана Android сохранена.

See [CHANGELOG](https://github.com/papajon123/meshcentral-android/blob/main/CHANGELOG.md) and [known limitations](https://github.com/papajon123/meshcentral-android/blob/main/docs/KNOWN_LIMITATIONS.md). This is a prerelease. Physical-phone acceptance and Google Play distribution remain pending.
