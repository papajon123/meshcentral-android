## MeshCentral Mobile 0.7.2

English · 简体中文 · Русский

- Services and Processes explain known failures: permission denied, offline agent,
  busy device, timeout, interrupted job or invalid result.
- Refresh reloads the selected tab. Error recovery offers Refresh and Help;
  it never automatically repeats a service start, stop or restart.
- Long messages scroll with the list, including Russian at 200% font size.
- Shared error translations across 23 locale variants.
- Includes optional Mobile Companion 1.1.2 source with Windows command completion
  and UTF-8 output fixes. Updating the APK does not deploy this server plugin.

Download the signed APK below. It updates the previous public application with
the same package and signer. Standard desktop, terminal, files, SSH and SFTP do
not require the optional plugin. Jobs and background events require MeshCentral
1.3.0 and the separately installed plugin; the app does not administer the server.

中文：服务和进程页面现在显示具体的错误原因。刷新仅更新当前标签页，错误提示提供刷新和帮助操作，
不会自动重复启动、停止或重启服务。长文本随列表滚动。请下载下方签名 APK。

Русский: в разделах служб и процессов теперь понятные причины ошибок. Обновление
работает для открытой вкладки. Из сообщения можно обновить данные или открыть
помощь; команды управления службами автоматически не повторяются. Длинный текст
прокручивается вместе со списком. Подписанный APK находится ниже.

[UI verification and screenshots](https://github.com/papajon123/meshcentral-android/blob/main/docs/INSPECTOR_VERIFICATION.md).
Physical-phone acceptance, Windows/systemd service actions, TalkBack and battery
measurements remain follow-up work. Notifications use a 15-minute best-effort
Android schedule; running jobs cannot be cancelled. A current Android System
WebView is required for the terminal. No WebRTC.
