## MeshCentral Mobile 0.7.3

English · 简体中文 · Русский

- Fixes sign-in on servers that require a second factor (#10). After the password,
  the verification-code step stays on screen, and **Sign in with code** becomes
  available as soon as a code is entered. Previously the screen kept refreshing with
  "Connection to the server was lost." while the button stayed in "Signing in…".
- A rejected password or code request is shown once and is never retried
  automatically. If a server keeps closing the connection, automatic reconnection
  stops after five attempts.
- Checked against MeshCentral 1.3.1 with an authenticator-app account.

Download the signed APK below. It updates the previous public application with
the same package and signer. Standard desktop, terminal, files, SSH and SFTP do
not require the optional plugin. Jobs and background events require MeshCentral
1.3.0 and the separately installed plugin; the app does not administer the server.

中文：修复了在需要双重验证的服务器上无法登录的问题。输入密码后，验证码步骤会保持显示，
输入验证码后即可点击“使用验证码登录”。此前界面会不断刷新并显示“与服务器的连接已断开。”，
按钮一直停在“正在登录…”。被拒绝的密码不会再自动重试。请下载下方签名 APK。

Русский: исправлен вход на серверах с двухфакторной проверкой. После пароля экран
ввода кода остаётся на месте, а кнопка «Войти с кодом» становится доступна, как
только введён код. Раньше экран постоянно обновлялся с ошибкой «Связь с сервером
прервана», а кнопка оставалась в состоянии «Вход…». Отклонённый пароль больше
не повторяется автоматически. Подписанный APK находится ниже.

[Validation details](https://github.com/papajon123/meshcentral-android/blob/main/docs/VALIDATION.md).
Physical-phone acceptance, e-mail/SMS/push code delivery, Windows/systemd service
actions, TalkBack and battery measurements remain follow-up work. Notifications use
a 15-minute best-effort Android schedule; running jobs cannot be cancelled. A
current Android System WebView is required for the terminal. No WebRTC.
