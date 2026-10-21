## MeshCentral Mobile 0.5.3 — preview

English · [简体中文](#简体中文) · [Русский](#русский)

This release fixes an Android IME race that could drop direct CJK input in the terminal and improves the project's international presentation and delivery. It includes remote desktop, Agent terminal and files, SSH and SFTP, with 22 interface languages and automatic language selection.

- English, Simplified Chinese and Russian guides, a new header, live GitHub badges and localized app screenshots.
- Private development in GitLab; only selected release source snapshots are published to GitHub. Private development commits are excluded.
- GitHub reruns Android checks before signing. The APK retains the existing public signing identity; `versionCode` is 12.

Download the APK below and install it over a previous public build, or use the [personal F-Droid repository](https://distribution.example.invalid/repo/?fingerprint=REDACTED_REPOSITORY_FINGERPRINT). This is a preview and is not in the official F-Droid catalogue. Earlier private builds use a different package.

Checks and limits: [validation](https://github.com/papajon123/meshcentral-android/blob/v0.5.3/docs/VALIDATION.md), [known limitations](https://github.com/papajon123/meshcentral-android/blob/v0.5.3/docs/KNOWN_LIMITATIONS.md). Physical phones, Windows Agent tools and live restrictive-right/consent flows remain unverified. Translations are machine-produced.

## 简体中文

此预览版本修复了终端中可能丢失直接中文输入的 Android 输入法时序问题，并改进了多语言文档和发布流程。功能包括：远程桌面、Agent 终端和文件、SSH、SFTP，以及自动选择的 22 种界面语言。

- 提供英文、简体中文和俄文指南及对应的应用截图。
- 在私有 GitLab 中开发，仅将选定版本的源代码快照发布到 GitHub，不公开私有开发提交历史。
- 签名前重新运行 Android 检查。保持原有公开版本的签名，`versionCode` 为 12。

下载下方 APK 覆盖安装旧的公开版本，或添加上述个人 F-Droid 仓库。此应用尚未进入 F-Droid 官方目录。实体手机、Windows Agent 工具和真实受限权限场景尚未完成验收；翻译由机器生成。

## Русский

Этот предварительный выпуск исправляет ошибку Android IME, из-за которой терминал мог терять прямой ввод китайского текста, и улучшает международное оформление и публикацию. Возможности: удалённый рабочий стол, терминал и файлы Agent, SSH и SFTP, 22 языка с автоматическим выбором.

- Инструкции и снимки на английском, китайском и русском, новая шапка и реальные показатели GitHub.
- Разработка в закрытом GitLab; в GitHub передаётся снимок исходников выбранной версии без частной истории коммитов.
- Перед подписью повторяются проверки Android. Сохранён прежний ключ публичного приложения; `versionCode` — 12.

Скачайте APK ниже и установите поверх предыдущей публичной версии либо добавьте личный репозиторий F-Droid по ссылке выше. В официальный каталог F-Droid приложение пока не включено. Прежняя частная сборка имеет другой идентификатор.

Физические телефоны, инструменты Windows Agent и реальные сценарии ограниченных прав/согласия ещё не проверены. Переводы выполнены машинно. Подробные результаты и ограничения доступны по ссылкам выше.
