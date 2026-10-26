## MeshCentral Mobile 0.8.0

English · 简体中文 · Русский

- **Phones:** view the screen after Android consent, see battery and device details,
  send confirmed vibration, flashlight, message, link and dial-pad requests, and
  transfer files with size/SHA-256 verification. Unsupported folder creation and
  rename stay hidden; ambiguous media names cannot be acted on.
- **Phone control is conditional:** touch, hold, swipe, Android navigation and text
  entry require a modified agent with input support, enabled accessibility and
  sufficient rights. This agent support is **not yet in the upstream project**;
  the official agent remains view-only. Command submission does not prove execution.
- **Sign-in and trust:** approve and pin a server public key per profile, including
  self-signed/private-CA servers, after independently checking its fingerprint.
  Optional **Remember this device** stores the server's 2FA cookie with Android
  Keystore encryption and binds it to the account, endpoint and issuing TLS trust.
- **Computers and tools:** Windows security/antivirus status, hardware/software
  inventory, CPU/RAM samples, device events and seven-day connectivity history;
  saved keyboard shortcuts and terminal favorites; reviewed user messages and shell
  commands. Power and removal requests retain permission checks and confirmations.
- **Windows fixes:** treat JSON null as missing, format BIOS dates in the local
  language, keep favorite-command insertion on the terminal prompt, and offer an
  explicit PowerShell 5+ UTF-8 mode. Default commands stay unchanged; programs with
  their own encoding and agent-side conversion still have limits.
- **Recovery:** sessions come back by themselves after a network outage: the app waits for the network and
  retries with a bounded backoff, while server refusals still stop after five attempts. Clearing the saved
  sign-in no longer freezes the app, and a failed download no longer leaves a partial file behind.
- **Reliability:** phone file transfers, MediaStore verification, screen framing,
  document-picker/session cleanup and forms above the keyboard. Optional GitHub
  update checks open downloads in the browser; the app never installs an APK.

The package and signing identity are unchanged. Standard desktop, terminal, files,
SSH and SFTP do not require the optional plugin. Jobs, service/process tools and
background notifications require the separately installed Mobile Companion plugin;
the app does not administer the server.

中文：新增手机屏幕查看、电池和设备信息、经确认后发送的操作请求，
以及带大小和 SHA-256 校验的文件传输。触摸、滑动、导航和文字输入需要支持输入的修改版代理、
已启用的无障碍服务及足够权限；这项代理支持尚未合入上游项目，官方代理仍只能查看屏幕。
新增按服务器配置固定公钥，以及使用 Android Keystore 加密保存的“记住此设备”双重验证凭据。
电脑端增加安全状态、软硬件信息、CPU/内存、事件和连接历史、快捷键及常用命令。
修复手机文件传输、屏幕数据和键盘遮挡问题。Windows 修复包括 JSON null、BIOS 日期显示、
常用命令插入位置，并提供显式选择的 PowerShell 5+ UTF-8 模式；默认命令不变，
代理或程序自身的编码仍可能影响结果。操作已发送不代表设备已执行。
断网后会话可自动恢复，清除已保存登录不再卡住界面，下载失败不会留下不完整文件。
可选择检查 GitHub 更新，下载在浏览器中打开，不会自动安装。

Русский: Для телефонов доступны
просмотр экрана после согласия Android, батарея и сведения об устройстве, запросы
действий с подтверждением и передача файлов с проверкой размера и SHA-256.
Касания, свайпы, навигация и ввод текста требуют доработанного агента с поддержкой
ввода, включённой службы специальных возможностей и нужных прав. Эти изменения
агента ещё не вошли в основной проект; официальный агент позволяет только просмотр.
Добавлены закрепление ключа сервера и «Запомнить устройство» для 2FA с шифрованием
через Android Keystore. Для компьютеров расширены сведения о безопасности,
оборудовании, программах, CPU/RAM, событиях и истории связи; добавлены сохраняемые
сочетания клавиш и избранные команды. Исправлены передача файлов телефона, обработка
экрана и перекрытие форм клавиатурой. Отправка команды не означает её выполнение.
В Windows исправлены обработка JSON null, дата BIOS и вставка избранных команд;
добавлен явный выбор PowerShell 5+ с UTF-8. Обычные команды не меняются; кодировка
самой программы или преобразование в агенте всё ещё могут влиять на результат.
После обрыва сети сеанс восстанавливается сам; очистка сохранённого входа больше не подвешивает
приложение, а неудачное скачивание не оставляет неполный файл.
Проверку обновлений GitHub можно включить по желанию; скачивание открывается в браузере.

[Validation and remaining acceptance](https://github.com/papajon123/meshcentral-android/blob/main/docs/RELEASE_0_8_0_VALIDATION.md).
Tested on emulators and a local MeshCentral 1.3.1 bench with Linux, Windows and Android agents;
acceptance on physical phones continues. Power/removal
execution, phone hardware effects and broad Windows/macOS coverage are not promised.
Notifications use Android's best-effort schedule; running jobs cannot be cancelled.
A current Android System WebView is required for the terminal. No WebRTC.
