# Карта интерфейса MeshCentral Mobile — подготовка к редизайну

Это карта исходной версии `84b666f599f77fcbd200fb59c9d420290064ec7a` (0.5.4), составленная **до изменений**. Номера строк относятся к этой версии. Итог реализации и результаты последующих запусков приведены в [отчёте](README.md).

Источник: только чтение исходников и тестов. Сборка, Gradle, ADB, сеть и эмулятор не запускались; пиксели не инспектировались.
Сокращения путей: `M` = `app/src/main/java/ru/papajon/meshcentralmobile`, `T` = `app/src/androidTest/java/ru/papajon/meshcentralmobile`.

## 1. Рамка приложения (факты)

- Ровно три пункта назначения: `Devices`/«PC», `Session`, `Settings` (`M/model/AppDestination.kt:8-12`); навигация — состояние `currentDestination`, без Navigation-Compose (`M/ui/MeshCentralApp.kt:68`).
- `MainActivity`: edge-to-edge, прозрачные бары, `MeshCentralMobileTheme`, `FLAG_SECURE` по настройке, биометрия (API 28+), скрытие системных баров в fullscreen (`M/MainActivity.kt:28-32,39-50,67-84,86-119`).
- Порядок оверлеев в `MeshCentralApp`: Lock → Onboarding (или невалидный URL) → лист правки сервера → Login (кроме вкладки Settings) → диалоги → `Scaffold` (`M/ui/MeshCentralApp.kt:149-214`); ранние `return` означают, что экран блокировки/онбординга/логина полностью подменяет приложение.
- Нижний бар скрыт в `Session` и при открытом устройстве (`M/ui/MeshCentralApp.kt:218-232`); системный Back на устройстве закрывает инструмент, а с обзора — само устройство (`:88-92`).
- Логин автоматически пробуется по сохранённым кредам при старте/смене сервера (`:110-118`); «продолжить без входа» лишь скрывает экран до следующего успешного входа (`:145-147,193`).
- Корневой `Scaffold` задаёт `safeDrawing` только вне Session (`:216-217`); Session — edge-to-edge, canvas на весь Box (`M/ui/screens/RemoteDesktopScreen.kt:182-204`).
- Владелец сетевой сессии — `MeshSessionViewModel` на всё время Activity (`M/ui/MeshSessionViewModel.kt:25-42`), поэтому поворот не рвёт desktop/terminal/files.

## 2. Экраны (факты)

- **Onboarding** — герой-блок, 3 фичи, форма сервера и единственное действие Save; тот же `ServerSettingsScreen` (`M/ui/screens/OnboardingScreen.kt:36-53,57-111`).
- **Server setup/edit** — поле endpoint, HTTPS-заметка, debug-переключатели HTTP/self-signed, Save, inline-ошибка/успех (`M/ui/screens/ServerSettingsScreen.kt:86-90,131-253`); в приложении открывается как `ModalBottomSheet` (`M/ui/MeshCentralApp.kt:177-186`).
- **Login** — заголовок + editable server, username/password с reveal, карточка 2FA (email/SMS/push), основная кнопка, сохранённые креды, статус-пилюля, «continue without sign-in» (`M/ui/screens/LoginScreen.kt:121-338`).
- **Computers (Devices)** — заголовок с live-счётчиком, Refresh/Settings, чипы All/Online/Favorites, группировка по mesh, карточки устройств, пустые/offline-состояния (`M/ui/screens/DevicesScreen.kt:130-275`).
- **Карточка устройства** — статус-точка, имя, OS/IP или причина недоступности, звезда, Connect/View (`M/ui/screens/DevicesScreen.kt:311-397`).
- **Device details** — имя, Back и `ScrollableTabRow` из 6 вкладок: Overview, Desktop, Terminal, Files, SSH, SFTP (`M/ui/screens/DeviceScreen.kt:52-65`; `M/ui/DeviceTab.kt:7-14`).
- **Device overview** — group/OS/address/agent, карточки доступности каждого инструмента, подсказка и «Open web panel» (`M/ui/screens/DeviceScreen.kt:66-94`).
- **Session (remote desktop)** — чёрный canvas, плавающий top bar, dock, connection overlay со стадиями, gesture guide, debug-строка, скрытие chrome/dock (`M/ui/screens/RemoteDesktopScreen.kt:122,136-149,156-324`).
- **Terminal / SSH** — Back/Disconnect, текстовый статус, xterm-WebView, горизонтальная полоса клавиш, отдельный `AlertDialog` SSH-аутентификации (`M/ui/screens/TerminalScreen.kt:49-113,116-155`).
- **Files / SFTP** — путь, Parent/Refresh/New folder/Upload, прогресс/отмена, список, диалог действия, диалог подтверждения, guard выхода, inline-форма SFTP-аутентификации (`M/ui/screens/FilesScreen.kt:54-193,197-233`).
- **Settings** — статус-полоса и секции Updates, Server, Security, Session, Data, About (`M/ui/screens/SettingsScreen.kt:139-481`).
- **Lock** — иконка, PIN-точки, сообщение, клавиатура 3×4, Unlock, биометрия (`M/ui/screens/LockScreen.kt:125-277`).
- **Debug WebView** — полноэкранный WebView поверх Settings с Close (`M/ui/screens/SettingsScreen.kt:128-135,768-816`).

## 3. Диалоги, листы, меню (факты)

- Bottom sheets: правка сервера (`M/ui/MeshCentralApp.kt:178`), а в сессии — Pointer, Keyboard, Text, Clipboard, Session options (`M/ui/screens/RemoteDesktopScreen.kt:326-393`; компоненты `M/ui/screens/session/SessionSheets.kt:89-133,189-293,348-443,447-558,596-746`).
- Меню: `DropdownMenu` выбора монитора, только при >1 дисплее (`M/ui/screens/RemoteDesktopScreen.kt:438-461`); переключатель дисплея живёт в top bar, не в dock.
- AlertDialog: закрытие устройства при активной файловой операции (`M/ui/MeshCentralApp.kt:199-209`), недоступный браузер (`:210-214`), SSH-аутентификация (`M/ui/screens/TerminalScreen.kt:125-154`), действие над файлом (`M/ui/screens/FilesScreen.kt:117-146`), подтверждение rename/mkdir/upload/delete (`:156-185`), выход при busy (`:186-192`).
- Системные SAF-пикеры: `CreateDocument` и `OpenDocument` (`M/ui/screens/FilesScreen.kt:58-63`).
- Все sheets используют общий `MeshSheet` (форма 28 dp сверху, drag handle) (`M/ui/components/Common.kt:329-356`).

## 4. Сохраняемое состояние и обратные вызовы (факты)

- `rememberSaveable` в хосте: destination, fullscreen, `loginDismissed`, `editingServer` (`M/ui/MeshCentralApp.kt:68-71`); фильтр устройств (`M/ui/screens/DevicesScreen.kt:112`); username/reveal, но не пароль/токен (`M/ui/screens/LoginScreen.kt:112-115`); URL/ошибки формы (`M/ui/screens/ServerSettingsScreen.kt:86-88`); новый PIN и debug-флаг (`M/ui/screens/SettingsScreen.kt:119,125`); PIN/ошибка/shake в локе (`M/ui/screens/LockScreen.kt:80-83`); 12 переменных сессии, включая sheet/mode/zoom/pan (`M/ui/screens/RemoteDesktopScreen.kt:136-147`); модификаторы клавиатуры и черновик текста (`M/ui/screens/session/SessionSheets.kt:194-196,353`).
- Намеренно НЕ сохраняются: пароли/ключи SSH/SFTP (`M/ui/screens/TerminalScreen.kt:117`; `M/ui/screens/FilesScreen.kt:198`); PIN в Settings и Lock, наоборот, попадает в Bundle (`M/ui/screens/SettingsScreen.kt:119`; `M/ui/screens/LockScreen.kt:80`) — это факт с security-последствием.
- ViewModel хранит `selectedDeviceId`, `deviceTab`, `terminalController`, `filesController` (`M/ui/MeshSessionViewModel.kt:35-42`); тест подтверждает переживание recreation (`T/ui/LiveNavigationAcceptanceTest.kt:194-201,229-234`).
- Хост прокидывает: `onScreenshotBlockingChanged`, `onBiometricUnlockRequested`, `onRemoteFullscreenChanged` (`M/MainActivity.kt:45-47`); в Devices — add/remove favorite, open remote/settings/sign-in/device (`M/ui/MeshCentralApp.kt:249-257`); в Device — select/back/web panel + `sessionContent` (`:267-294`); в Session — fullscreen/browse (`:297-304`); в Settings — 9 колбэков, включая очистку credentials/mesh/WebView (`:306-333`).
- Колбэки Onboarding (3), server sheet (3), Login (2), Lock (2) заданы в `M/ui/MeshCentralApp.kt:150-195`.
- Отложенное действие `pendingFileClose` — единственный «сохранённый» колбэк-намерение: он ждёт подтверждения, пока `filesController.state.busy` (`M/ui/MeshCentralApp.kt:78-83,199-209`).

## 5. Критические сценарии (факты)

- **PIN/lock**: PIN ≥4 цифр, ≤12, только цифры (`M/ui/screens/SettingsScreen.kt:348,362`); LockScreen тоже режет на 12 (`M/ui/screens/LockScreen.kt:187,212`); таймауты 15/60/300 с (`M/ui/screens/SettingsScreen.kt:91-95`); блокировка при возврате после таймаута (`M/settings/AppLockController.kt:15-31`); «Lock now» (`:33-37`); биометрия только API 28+ (`M/MainActivity.kt:87-90`).
- **Login/2FA**: пароль очищается после отправки (`M/ui/screens/LoginScreen.kt:265`) и при смене сервера вместе с username (`:112-114`); 2FA-код уходит тем же вызовом (`:262-268`); ошибка показывается `ConnectionNotice` (`:151-153`).
- **Server edit**: валидация — https (http только debug), запрет userinfo/query/fragment, проверка host/port (`M/settings/ServerUrlValidator.kt:17-57`); сохранение тримит URL и не закрывает форму при ошибке (`M/ui/screens/ServerSettingsScreen.kt:213-238`); смена identity закрывает инструменты (`M/ui/MeshCentralApp.kt:84-87`).
- **Settings/theme/language**: темы как настройки нет — схема всегда `MeshDarkScheme`, `isSystemInDarkTheme()` вызывается и отбрасывается (`M/ui/theme/Theme.kt:13-36`); языка в приложении тоже нет — только системный/per-app (`docs/UI.md:86`; `app/src/main/AndroidManifest.xml:14`), 23 locale-варианта (`app/build.gradle.kts:19-23`).
- **Settings destructive**: «Clear session» без диалога подтверждения стирает credentials, mesh-сессию и WebView (`M/ui/screens/SettingsScreen.kt:411-430` → `M/ui/MeshCentralApp.kt:328-332`); удаление PIN тоже без подтверждения (`M/ui/screens/SettingsScreen.kt:380-391`).
- **Device filters/details**: All/Online/Favorites; Online отключён и сбрасывается, пока список неактуален (`M/ui/screens/DevicesScreen.kt:117,294-307`); карточка открывает детали, если передан `onOpenDevice`, иначе коннектит (`:341`); Connect только при `canOpenNativeDesktop` (`:320-321,384-395`).
- **Desktop controls**: 4 режима указателя (`DirectTouch`, `Trackpad`, `PanZoom`, `Scroll`) (`M/model/RemoteControlMode.kt`; `M/ui/screens/RemoteDesktopScreen.kt:646-659`); долгое нажатие/двойной тап/драг/2 пальца обрабатываются в `SessionCanvas` (`:892-1174`); клик вне картинки игнорируется (`:1136-1139`); курсор рисуется клиентом (`:1185-1214`).
- **Session chrome**: скрыть/показать top bar и dock (`M/ui/screens/RemoteDesktopScreen.kt:234-307`); при скрытом chrome остаётся компактный статус+refresh (`:245-259`); стадии подключения и Retry/Close (`:745-839`).
- **Terminal/SSH**: контроллер стартует при открытии вкладки (`M/ui/screens/TerminalScreen.kt:60`); при Closed/Failed показывается reopen-hint (`:88-90`); SSH-креды очищаются при dismiss/успехе (`:123-124,149-153`).
- **Files/transfers**: busy блокирует действия и требует подтверждения выхода (`M/ui/screens/FilesScreen.kt:64,67,186-192`); скачивание сначала спрашивает SAF-имя (`:128-132`); upload/rename/delete идут через `confirmation` (`:148-185`); ошибки/прогресс — live region (`:92-100`).
- **SSH/SFTP**: доступность вкладок зависит от `sessionAvailability` и `current` (`M/ui/screens/DeviceScreen.kt:47-51,76-88`); SFTP-форма даёт password/key/saved key (`M/ui/screens/FilesScreen.kt:197-233`).

## 6. Тема, токены и повторяющиеся компоненты (факты)

- Токены: `Color.kt` (M3 dark + `MeshColors`: online/offline/warning/danger/viewOnly/glass/canvas), `Type.kt` (tight line-height, `MeshMono`), `Shape.kt` (10/14/20/26/34 dp, `PillShape`, `SquircleShape`, `SheetShape`), `Motion.kt` (springs + spacing 4/8/12/16/24/32), `Theme.kt` (провайдеры и `Surface` для корректного `LocalContentColor`) — `M/ui/theme/*`.
- Компоненты: `StatusDot`, `StatusPill`, `MeshCard`, `SectionHeader`, `EmptyState`, `MetricChip`, `MeshLoadingIndicator`, `IconBadge`, `SheetHeader`, `MeshSheet` (`M/ui/components/Common.kt:55-356`); `MeshScreenHeader`, `MeshNavigationBar`, `NavigationPill` (`M/ui/components/Header.kt:52-188`); `ConnectionNotice` (`M/ui/components/ConnectionNotice.kt:23-37`); текстовые лейблы состояний (`M/ui/components/StatusLabels.kt:19-78`).
- Мёртвые/неиспользуемые токены и компоненты: `SquircleShape` импортирован, но не применён (`M/ui/screens/RemoteDesktopScreen.kt:120`); `MetricChip` объявлен и нигде не вызван (`M/ui/components/Common.kt:211`); `latencyGood/Fair/Poor` не используются (`M/ui/theme/Color.kt:82-84`).
- Дублирование: `SettingsSwitchRow` и `ServerSwitchRow` почти идентичны (`M/ui/screens/SettingsScreen.kt:543-591`; `M/ui/screens/ServerSettingsScreen.kt:320-372`); `SettingsActionRow` + `SectionHeader` + `MeshCard` повторяют один и тот же блок в 4 секциях; `TimeoutPill` дублирует паттерн `FilterChip`/`NavigationPill` (`M/ui/screens/SettingsScreen.kt:709-764`).
- Жёстко зашитые значения: 48/60 dp touch-таргеты, отступы 14/16/20/24 и alpha 0.35/0.4/0.5/0.6 разбросаны по экранам (например `M/ui/screens/SessionSheets.kt:100,119,126`; `M/ui/screens/RemoteDesktopScreen.kt:417-419,637-638`).
- Локализация: UI-строки только через ресурсы, runtime-сообщения через `UiText.Resource` (`M/localization/UiText.kt:15-47`); серверный текст — `UiText.Literal`; 23 каталога (`app/build.gradle.kts:19-23`), per-app locale (`app/src/main/AndroidManifest.xml:14`).

## 7. Как тесты снимают реальные пиксели (факты)

- `InternationalGalleryTest` — opt-in (`-e publicGallery true`) на API 35 emulator; 3 экрана (Devices/Terminal/Files) × 3 языка = 9 PNG (`T/localization/InternationalGalleryTest.kt:112-115,123-183`); Setup намеренно исключён (`:90-93`).
- Съёмка галереи: `uiAutomation.takeScreenshot()`, ожидание composited-кадра через `postOnAnimation`, проверка фокуса окна и «разнообразия» пикселей с маской `0x00F8F8F8`; порог 32 px в заголовке и 128 px в терминале, evidence-JSON рядом (`:305-367`).
- `AdaptiveLayoutTest` — синтетические viewport 320×480/320×640/640×320, fontScale 2/1.3, проверка glyph-bounds и кликов; PNG в `finally` через `uiAutomation` (`T/localization/AdaptiveLayoutTest.kt:79-115,168-215,399-417`).
- `LiveNavigationAcceptanceTest` — реальная `MainActivity` и fixture (opt-in `liveFixture`, debug): Compose `captureToImage()` для экранов (`:426-433`) и отдельный `uiAutomation`-снимок окна терминала после `WebView.VisualStateCallback` + двух `postOnAnimation`, с порогами 32/128 (`:436-501`).
- `LocalizedScreensTest` — 23 языка, проверка обрезки через `TextLayoutResult`, скриншоты для 8 языков + все проблемные (`T/localization/LocalizedScreensTest.kt:40-79,106-114`).
- `ConnectionUiTest` и `LockScreenTest`/`SessionControlUiTest`/`SessionCanvasScrollTest`/`LoginServerChangeTest` — семантика и колбэки; PNG только в `ConnectionUiTest` (`T/ui/screens/ConnectionUiTest.kt:105-112`).
- `LocaleFeedbackTest` проверяет, что уже показанная ошибка перерисовывается на другом языке без повторного действия (`T/localization/LocaleFeedbackTest.kt:36-116`).
- Сборка: `compileSdk 35`, `minSdk 26`, `targetSdk 35`, Compose BOM 2024.12.01, без nav/Hilt (`app/build.gradle.kts:9,14,15,73,88-92`); команды — `AGENTS.md:9`, инструментальные — `docs/VALIDATION.md:208-216`. В этой задаче не запускались.

## 8. Расхождения документации и кода (факты)

- `docs/UI.md:106` обещает search field в Computers — в коде только чипы фильтра (`M/ui/screens/DevicesScreen.kt:280-309`).
- `docs/UI.md:78` обещает кнопку дисплея в dock — её нет; переключение в top bar (`M/ui/screens/RemoteDesktopScreen.kt:617-688,438-461`).
- `docs/UI.md:115` обещает Displays/geometry в Session options — в листе только zoom, quality, presets, reconnect/fullscreen/hide (`M/ui/screens/session/SessionSheets.kt:615-744`).
- `docs/UI.md:23,110` говорят о squircle в dock и latency в HUD — dock использует `shapes.small`, latency-токены не используются; `SessionConnectionStatus` показывает только слова состояния (`M/ui/screens/session/SessionConnectionControls.kt:57-88`).
- `docs/UI.md:120` перечисляет `MetricChip` как общий компонент — он не используется.

## 9. Предложения для редизайна (не факты)

- Ввести явную модель поверхностей (sealed-класс: Destination / FullScreen / Sheet / Dialog) вместо флагов `loginDismissed`/`editingServer`/`deviceTab`, чтобы карта экранов была исполняемой.
- Расширить галерею до Settings, Login, Lock, Session, Device details и состояний loading/offline/error: сейчас 9 кадров покрывают только 3 экрана.
- Почистить токены: применить или удалить `SquircleShape`, `MetricChip`, `latency*`; вынести alpha/elevation/touch-target/отступы в `MeshColors`/`MeshSpacing`; определиться с light-theme (сейчас вызов `isSystemInDarkTheme()` — мёртвый код).
- Слить `SettingsSwitchRow`/`ServerSwitchRow` и `SettingsActionRow`/`SectionHeader`+`MeshCard` в переиспользуемые `SettingsRow`/`SettingsCard`; добавить подтверждение для Clear session и удаления PIN.
- Пересобрать dock иерархически (primary: режим/ввод; secondary: Keys/Clipboard/More), а на узких экранах уводить редкое в лист; проверить 6 вкладок Device на 320 dp/font2.
- Убрать PIN из `rememberSaveable` (Settings new PIN, Lock PIN) либо явно ограничить его жизнь; сейчас он попадает в saved state.
- Добавить `testTag` для экранов, dock и sheets и один общий pixel-evidence helper вместо пяти копий `takeScreenshot`.
- Синхронизировать `docs/UI.md` с фактическим кодом до начала визуальной работы.

## 10. Не проверено

- Не запускались сборка, lint и `connectedDebugAndroidTest`; числа тестов взяты из `docs/VALIDATION.md`, не перепроверены.
- Не инспектировались PNG-артефакты и не снимались новые скриншоты.
- Не проверялись TalkBack, физическая клавиатура, RTL-жесты и поведение на реальных размерах экрана.
