## MeshPilot 0.9.2

English · 简体中文 · Русский

MeshPilot is an independent client for MeshCentral servers. It is not affiliated with or endorsed by the
MeshCentral project, so please report problems with this app here, not to the MeshCentral team.
The package and signing key are unchanged, so 0.9.2 installs as a normal update over 0.8.4.
0.9.2 is the first published build of the 0.9 line: the 0.9.0 and 0.9.1 tags did not pass device checks and were not released.

**New in 0.9:**

- **Chat with the person at a computer.** Open the standard MeshCentral messenger window on an online computer and
  exchange plain text. Conversations survive short network loss; history stays in memory and is never written to disk.
- **Port forwarding.** Reach RDP, VNC, SSH, web or custom TCP services on remote machines through a connected Mesh
  Agent on a local loopback port of the phone. Routes are started by you, shown in a notification with Stop all and
  never restored automatically.
- **Large servers.** Devices are shown in pages using the server's `maxdeviceview`, with search across all devices
  on MeshCentral 1.3.1. The page and filter survive returning from details and reconnecting.
- **Processes and services.** Search and sort processes, filter services, end a process or start/stop/restart a
  service after confirmation. The app re-reads the agent state before reporting success. No plugin is required.
- **Terminal and files.** Reconnect a terminal after a disconnect while keeping scrollback. Select several files or
  folders with a long press, download whole folders with size and SHA-256 verification, and retry or cancel queued
  transfers.
- **Remote desktop.** Passive picture-in-picture, three-finger scrolling, a two-finger scroll/pan choice and a
  pinch-to-zoom switch; physical mouse, keyboard and Samsung DeX input; per-device image quality, scale and capture
  rate, with the incoming data rate shown in the session bar.
- **Devices.** Invite a device into a group with a time-limited link or QR code. Rename devices, edit descriptions,
  tags and notes, and move them between groups with permission checks.
- **Launcher and backups.** Up to four recent devices on the app icon, a Favorites home-screen widget, and
  password-encrypted settings backups that never overwrite existing profiles.
- **Android 16.** The app targets Android 16 (API 36); Android 8.0 remains the minimum.
- **Support MeshPilot.** An optional card in Settings opens the Boosty page. No features depend on support.

中文：MeshPilot 是 MeshCentral 服务器的独立客户端，与 MeshCentral 项目没有关联，也未获其认可；请在本仓库报告本应用的问题。
软件包和签名密钥不变，0.9.2 可作为 0.8.4 的普通更新安装。0.9.2 是 0.9 系列首个正式发布的版本（0.9.0 和 0.9.1 未通过设备检查，未发布）。0.9 新增：与电脑前的用户进行文字聊天（历史仅保存在内存中）；
通过已连接的 Mesh Agent 将 RDP、VNC、SSH、网页或自定义 TCP 服务转发到手机本地回环端口，由用户手动启动并在通知中显示；
大型服务器分页浏览，并可在 MeshCentral 1.3.1 上搜索全部设备；进程和服务管理，操作前需确认，并在报告成功前重新读取代理状态；
终端断线后重新连接并保留滚动记录；长按多选文件和文件夹，下载整个文件夹并校验大小和 SHA-256；远程桌面画中画、三指滚动、
双指滚动/平移选择、捏合缩放开关，支持实体鼠标、键盘和 Samsung DeX，并按设备保存画质、缩放和采集频率；通过限时链接或二维码邀请设备入组，
重命名设备、编辑描述、标签和备注，并在权限检查后移动设备；应用图标快捷方式、“收藏”桌面小组件和密码加密的设置备份；
目标平台为 Android 16，最低仍为 Android 8.0；设置中新增可选的 Boosty 支持卡片。

Русский: MeshPilot — независимый клиент для серверов MeshCentral, он не связан с проектом MeshCentral и не одобрен им;
о проблемах с приложением сообщайте здесь. Пакет и ключ подписи прежние, 0.9.2 ставится обычным обновлением поверх 0.8.4.
0.9.2 — первый опубликованный выпуск линии 0.9: сборки 0.9.0 и 0.9.1 не прошли проверки на устройствах и не выпускались.
Новое в 0.9: текстовый чат с человеком за компьютером (история хранится только в памяти); проброс RDP, VNC, SSH,
веб- и других TCP-служб удалённых машин через подключённый Mesh Agent на локальный порт телефона — маршруты запускаете вы,
они видны в уведомлении и не восстанавливаются сами; постраничный просмотр больших серверов и поиск по всем устройствам
на MeshCentral 1.3.1; диспетчер процессов и служб с подтверждением действий и повторной проверкой состояния агента;
переподключение терминала с сохранением прокрутки; выбор нескольких файлов и папок долгим нажатием, загрузка целых папок
с проверкой размера и SHA-256; рабочий стол в окне «картинка в картинке», прокрутка тремя пальцами, выбор прокрутки или
сдвига двумя пальцами, переключатель масштабирования щипком, мышь, клавиатура и Samsung DeX, сохранение качества,
масштаба и частоты кадров для каждого устройства; приглашение устройства в группу ссылкой или QR-кодом, переименование,
описание, метки, заметки и перенос между группами с проверкой прав; ярлыки на значке приложения, виджет «Избранное»
и резервная копия настроек с шифрованием паролем; целевая версия Android 16, минимальная — по-прежнему Android 8.0;
в настройках появилась необязательная карточка поддержки на Boosty.

See the [changelog](https://github.com/papajon123/meshcentral-android/blob/main/CHANGELOG.md) and
[0.9 validation](https://github.com/papajon123/meshcentral-android/blob/main/docs/RELEASE_0_9_1_VALIDATION.md) for details.
On Android 8-10 in landscape with the keyboard open, the chat header can move out of view; system Back still works.
Tested on emulators and a local MeshCentral 1.3.1 bench with Linux, Windows and Android agents;
acceptance on physical phones continues. Power/removal
execution, phone hardware effects and broad Windows/macOS coverage are not promised.
Notifications use Android's best-effort schedule; running jobs cannot be cancelled.
A current Android System WebView is required for the terminal. No WebRTC.
