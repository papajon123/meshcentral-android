## MeshCentral Mobile 0.5.4 — preview

English · [简体中文](#简体中文) · [Русский](#русский)

This release fixes an Android IME race that could drop direct CJK input in the terminal and improves the project's international presentation and delivery. It includes remote desktop, Agent terminal and files, SSH and SFTP, with 22 interface languages and automatic language selection.

- Fix the PIN keypad zero button: PINs containing zero now unlock correctly.
- English, Simplified Chinese and Russian guides, a new header, live GitHub badges and localized app screenshots.
- Private development in GitLab; only selected release source snapshots are published to GitHub. Private development commits are excluded.
- GitHub reruns Android checks before signing. The APK retains the existing public signing identity; `versionCode` is 13.

Download the APK from the GitHub release below and install it over a previous public build. This is a preview and is not in the official F-Droid catalogue. Earlier private builds use a different package.

Checks and limits: [validation](https://github.com/papajon123/meshcentral-android/blob/v0.5.4/docs/VALIDATION.md), [known limitations](https://github.com/papajon123/meshcentral-android/blob/v0.5.4/docs/KNOWN_LIMITATIONS.md). Physical phones, Windows Agent tools and live restrictive-right/consent flows remain unverified. Translations are machine-produced.

## 简体中文

此预览版本修复了终端中可能丢失直接中文输入的 Android 输入法时序问题，并改进了多语言文档和发布流程。功能包括：远程桌面、Agent 终端和文件、SSH、SFTP，以及自动选择的 22 种界面语言。

- 修复 PIN 键盘的零键，包含零的 PIN 现在可以正常解锁。
- 提供英文、简体中文和俄文指南及对应的应用截图。
- 在私有 GitLab 中开发，仅将选定版本的源代码快照发布到 GitHub，不公开私有开发提交历史。
- 签名前重新运行 Android 检查。保持原有公开版本的签名，`versionCode` 为 13。

从下方 GitHub 发行版下载 APK 并覆盖安装旧的公开版本。此应用是预览版，尚未进入 F-Droid 官方目录。实体手机、Windows Agent 工具和真实受限权限场景尚未完成验收；翻译由机器生成。

## Русский

Этот предварительный выпуск исправляет ошибку Android IME, из-за которой терминал мог терять прямой ввод китайского текста, и улучшает международное оформление и публикацию. Возможности: удалённый рабочий стол, терминал и файлы Agent, SSH и SFTP, 22 языка с автоматическим выбором.

- Исправлена кнопка нуля на экране PIN: коды с нулём теперь вводятся правильно.
- Инструкции и снимки на английском, китайском и русском, новая шапка и реальные показатели GitHub.
- Разработка в закрытом GitLab; в GitHub передаётся снимок исходников выбранной версии без частной истории коммитов.
- Перед подписью повторяются проверки Android. Сохранён прежний ключ публичного приложения; `versionCode` — 13.

Скачайте APK из выпуска GitHub (ссылка ниже) и установите поверх предыдущей публичной версии. Это предварительный выпуск; в официальный каталог F-Droid приложение пока не включено. Прежняя частная сборка имеет другой идентификатор.

Физические телефоны, инструменты Windows Agent и реальные сценарии ограниченных прав/согласия ещё не проверены. Переводы выполнены машинно. Подробные результаты и ограничения доступны по ссылкам выше.
