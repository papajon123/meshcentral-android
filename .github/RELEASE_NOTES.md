## MeshCentral Mobile 0.5.1 — preview

### English

Control your own MeshCentral server from Android 8 or newer.

- New device details and tabs: Desktop, Terminal, Files, SSH and SFTP.
- Native Agent terminal and file operations; SSH/SFTP through your MeshCentral server.
- Upload verification by reading back SHA-256; downloads through Android's system file picker.
- Sessions survive Activity recreation without replaying commands. No automatic reconnect for terminal/file tools.
- 22 languages (23 locale variants), 529 translated strings each, automatic system-language selection and improved layouts for larger text.
- Terminal uses bundled xterm.js and requires a working, updated Android System WebView. Old providers receive an update instruction. Files and SFTP do not need WebView.

Download the APK below and allow installation from the browser/file manager when Android asks. It updates public 0.3.0/0.4.0 with the same package and signing key; earlier private builds remain separate. Use your own HTTPS server; none is preconfigured.

Validated locally: 170 JVM tests, 66 regular Android tests and six isolated live scenarios on MeshCentral 1.2.5. The live evidence spans two runs after one HTTP-fixture bootstrap error; details are in docs/VALIDATION.md. GitHub Actions supplies separate API 26/35 results. Physical phones, Windows Agent tools, third-party servers and live restrictive-right/consent flows remain unverified. Translations are machine-produced and not yet proofread in every language. See [known limitations](https://github.com/papajon123/meshcentral-android/blob/v0.5.1/docs/KNOWN_LIMITATIONS.md).

### По-русски

Управление вашим сервером MeshCentral с Android 8 или новее.

- Карточка компьютера и вкладки: рабочий стол, терминал, файлы, SSH и SFTP.
- Терминал и файловые операции Mesh Agent; SSH/SFTP через ваш сервер MeshCentral.
- Проверка загруженного файла обратным чтением SHA-256; скачивание через системный выбор файлов Android.
- Сеанс сохраняется при пересоздании окна, команды не повторяются. Автоматического переподключения терминала и файлов нет.
- 22 языка (23 языковых варианта), по 529 переведённых строк, автоматический выбор языка системы и улучшенная работа с крупным текстом.
- Терминалу нужен исправный обновлённый Android System WebView. Для старого компонента показана инструкция обновления. Файлы и SFTP работают без WebView.

Скачайте APK ниже и при запросе Android разрешите установку из браузера или файлового менеджера. Выпуск обновляет публичные 0.3.0/0.4.0 с сохранением имени пакета и ключа подписи; старые частные сборки остаются отдельными приложениями. Нужен ваш сервер по HTTPS, предустановленного сервера нет.

Локально прошли 170 модульных, 66 обычных проверок Android и шесть живых сценариев на отдельном стенде MeshCentral 1.2.5. Живые сценарии подтверждены в двух запусках после одного сбоя HTTP-стенда до открытия приложения; подробности в docs/VALIDATION.md. Результаты API 26/35 доступны в GitHub Actions. Физические телефоны, инструменты Windows Agent, чужие серверы и реальные сценарии ограниченных прав/согласия ещё не проверены. Переводы выполнены машинно и не вычитаны носителями всех языков. Подробнее — в [ограничениях](https://github.com/papajon123/meshcentral-android/blob/v0.5.1/docs/KNOWN_LIMITATIONS.md).

`SHA256SUMS` verifies APK bytes; `release-info.json` records package, version and signing certificate.