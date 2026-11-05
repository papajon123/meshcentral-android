# MeshPilot — черновик Data safety для Google Play

**Статус:** локальный черновик; форма Data safety ещё не отправлена. Перед отправкой сверить исходники, итоговый манифест и подписанный AAB. После доставки через Google Play проверить установленное приложение.

**Предмет:** публичный пакет `io.github.papajon123.meshcentral`, имя `MeshPilot` (`app/src/main/res/values/strings.xml:16`; `README.md:175`). Это независимый клиент чужого сервера MeshCentral, не агент и не продукт команды MeshCentral (`README.md:5-8`, `README.md:24`).

Короткие пути в таблицах 3 и 4 указаны относительно `app/src/main/java/ru/papajon/meshcentralmobile/`; в таблице 2 и в тексте — полный путь от корня репозитория.

## 1. Что подтверждено, а что ещё нет

| Утверждение | Доказательство | Ограничение |
|---|---|---|
| В приложении нет собственного аккаунта, рекламы, аналитики, телеметрии и crash-SDK; нет backend проекта | `PRIVACY.md:11-12`, `PRIVACY.md:86`, `README.md:139`; в `app/src/main/AndroidManifest.xml:2-12` нет `AD_ID` | Это чтение исходников, а не скан финального AAB и не гарантия для merged manifest |
| Приложение бесплатное, без рекламы и платных функций | `README.md:187-190` | Цена и монетизация дополнительно проверяются в Console |
| Сервер и аккаунт не преднастроены; нужен HTTPS-сервер пользователя | `README.md:51-53` | Play-сборка должна сохранить это поведение |
| Основные инструменты не требуют серверного плагина | `README.md:73` | Дополнительные функции плагина в листинге не обещаются |
| Android backup и device transfer отключены | `app/src/main/AndroidManifest.xml:15-18`; `app/src/main/res/xml/backup_rules.xml:2-5`; `app/src/main/res/xml/data_extraction_rules.xml:3-12` | Проверить merged manifest финального артефакта |
| В текущем общем исходнике есть GitHub-обновления и ссылка поддержки | `app/src/main/java/ru/papajon/meshcentralmobile/ui/MeshCentralApp.kt:848`; `app/src/main/java/ru/papajon/meshcentralmobile/ui/screens/SettingsScreen.kt:398-400` | Планируемая Play-сборка должна их исключить; отдельная Play-сборка в прочитанном `app/src/main` не подтверждена |

## 2. Сетевые потоки

| Поток | Что передаётся | Получатель | Когда | Путь:строка |
|---|---|---|---|---|
| Управляющий канал | Адрес сервера, имя и пароль MeshCentral, login key, состояние сессии и список устройств | Сервер пользователя, `/control.ashx` | При входе и работе | `app/src/main/java/ru/papajon/meshcentralmobile/mesh/MeshControlEndpoint.kt:5-15`; `PRIVACY.md:66-72` |
| Рабочий стол, терминал агента, файлы | Кадры экрана и ввод; ввод/вывод терминала; содержимое файлов | Сервер пользователя, `/meshrelay.ashx`, затем управляемый компьютер | При открытии соответствующего инструмента | `app/src/main/java/ru/papajon/meshcentralmobile/mesh/MeshControlMessages.kt:110-133`; `PRIVACY.md:28-31`, `PRIVACY.md:119-130` |
| SSH/SFTP | SSH-пароль, ключ и passphrase в памяти; ввод/вывод терминала и файлы | Сервер пользователя, `/sshterminalrelay.ashx` и `/sshfilesrelay.ashx` | При SSH/SFTP-сессии | `app/src/main/java/ru/papajon/meshcentralmobile/mesh/MeshControlMessages.kt:141-150`; `PRIVACY.md:44`, `PRIVACY.md:126-130` |
| Фоновые уведомления (опция) | Read-only bearer token и same-origin GET; без FCM | Сервер пользователя, `/mobilecompanion/events` | После явного включения, опрос WorkManager примерно раз в 15 минут | `app/src/main/java/ru/papajon/meshcentralmobile/notifications/NotificationModels.kt:83-89`; `NotificationPoller.kt:45-47`, `NotificationPoller.kt:90-111`; `NotificationWorkScheduler.kt:20-60` |
| GitHub-обновления (текущий общий исходник, не Play) | Публичные release-метаданные и ассеты; без cookies, токена и данных сессии | `api.github.com` и GitHub CDN | При согласии, на переднем плане, не чаще раза в 24 часа | `app/src/main/java/ru/papajon/meshcentralmobile/update/GitHubReleaseSource.kt:124-133`; `UpdateComponents.kt:43-57`; `PRIVACY.md:88-112` |
| Ссылка поддержки (текущий общий исходник, не Play) | Приложение только открывает внешний браузер; само данных не отправляет | Внешний сайт | По нажатию | `app/src/main/java/ru/papajon/meshcentralmobile/ui/components/SupportCard.kt:32-38`; `SettingsScreen.kt:400` |

## 3. Локальное хранение

| Данные | Где | Защита и срок | Путь:строка |
|---|---|---|---|
| Профили серверов, URL, избранное, настройки, shortcuts и favorite commands | App-private `SharedPreferences` | Не шифруются; URL может раскрывать внутреннее имя хоста | `SettingsRepository.kt:24-36`; `model/ServerProfile.kt:9-29`; `PRIVACY.md:39` |
| Имя и пароль MeshCentral | App-private `SharedPreferences` + Android Keystore | AES-GCM; ключ non-exportable; запись после успешного входа | `security/SecureVault.kt:20-22`, `security/SecureVault.kt:52-60`, `security/SecureVault.kt:148-188`; `PRIVACY.md:41` |
| PIN приложения | App-private storage | PBKDF2-HMAC-SHA256 (120 000), затем AES-GCM с Keystore | `security/SecureVault.kt:107-117`, `security/SecureVault.kt:190-193`; `PRIVACY.md:43` |
| Cookie «запомнить устройство» для 2FA | `SecureVault`, отдельно для профиля | AES-GCM; удаляется при истечении, отказе сервера, выходе, смене профиля/сервера/аккаунта | `security/SecureVault.kt:29-33`; `PRIVACY.md:42` |
| Токен фоновых уведомлений | `SecureVault` | AES-GCM; read-only, TTL 30 дней; id хранится для отзыва | `security/SecureVault.kt:82-96`; `PRIVACY.md:46` |
| Настройки уведомлений, cursor и ограниченная лента событий | App-private `SharedPreferences` | Не секрет; без токена, пароля, hostname и вывода команд | `notifications/NotificationStore.kt:6-18`, `notifications/NotificationStore.kt:35-60`; `PRIVACY.md:47` |
| Недавние устройства | App-private `SharedPreferences` | Только node id и время; scope хешируется; не более 20 на профиль/аккаунт | `discovery/RecentDevicesStore.kt:7-20`, `discovery/RecentDevicesStore.kt:30-42`; `PRIVACY.md:40` |
| Состояние проверки обновлений (текущий общий исходник) | App-private `SharedPreferences` `app_update_preferences` | Публичные метаданные и настройки; без сессии | `update/UpdatePreferencesStore.kt:5-27`; `PRIVACY.md:48` |
| Экспорт настроек, инициированный пользователем | Файл, выбранный через SAF; до 4 МиБ | Может быть **незашифрованным**, если не задан пароль и не включены login keys; содержит URL профилей, избранное, настройки, при opt-in — login key; пароли, cookies и PIN исключены | `backup/BackupDocuments.kt:7-27`; `backup/BackupCodec.kt:43-54`; `backup/BackupScreen.kt:70-108`, `backup/BackupScreen.kt:200-216` |
| Выбранные пользователем файлы и очередь передачи | Память процесса; источник/приёмник — документ, выбранный через SAF | После смерти процесса выбор нужно повторить; неудачное скачивание может оставить частичный файл | `PRIVACY.md:49`, `PRIVACY.md:53` |
| Вывод терминала, буфер обмена, скриншоты | Память / системный буфер / система | Терминал не пишется в файлы; копирование только по действию; `FLAG_SECURE` — опция, по умолчанию выключена | `PRIVACY.md:51`, `PRIVACY.md:55-56` |
| WebView cookies и storage | Android WebView | Только при использовании raw Debug WebView; очищаются действием в настройках; исключены из backup | `PRIVACY.md:54` |

## 4. Разрешения

| Разрешение | Назначение по коду | Строка |
|---|---|---|
| `INTERNET` | Соединение с сервером пользователя | `app/src/main/AndroidManifest.xml:4` |
| `ACCESS_NETWORK_STATE` | Проверка состояния сети | `AndroidManifest.xml:5` |
| `USE_BIOMETRIC` | Необязательная разблокировка через `BiometricPrompt` | `AndroidManifest.xml:6`; `MainActivity.kt:174-193` |
| `POST_NOTIFICATIONS` | Необязательные уведомления; запрашивается в рантайме | `AndroidManifest.xml:7`; `NotificationNotifier.kt:41`; `MeshCentralApp.kt:746-747` |
| `FOREGROUND_SERVICE` и типы `dataSync`, `remoteMessaging`, `connectedDevice` | Передача файлов, чат, port forwarding | `AndroidManifest.xml:8-11` |
| `CHANGE_NETWORK_STATE` | Работа с сетевыми соединениями port forwarding | `AndroidManifest.xml:12` |

По исходному манифесту нет `RECORD_AUDIO`, `CAMERA`, доступа к геолокации, контактам, календарю, `READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES` и `AD_ID` (`AndroidManifest.xml:2-12`; для микрофона — `PRIVACY.md:164-170`). Доступ к выбранным файлам идёт через SAF, без broad-storage permission (`backup/BackupDocuments.kt:7-8`).

## 5. Как это соотносится с формой Google

### 5.1. Отсутствие аналитики

По прочитанному коду и манифесту приложение не содержит SDK рекламы, аналитики, телеметрии или crash-reporting и не отправляет данные в backend проекта. Это наблюдение по исходникам, а не подтверждённая декларация: финальный AAB и merged manifest нужно проверить отдельно (см. §7). Наличие ссылки на GitHub issues само по себе не создаёт поток, пока пользователь не откроет её.

### 5.2. Обработка на сервере пользователя

Основные данные идут на MeshCentral-сервер, который настраивает пользователь или его организация; разработчик приложения не получает к нему доступ по умолчанию. Оператор сервера может видеть IP, аккаунт, список устройств, время сессий, выбранный SSH-хост, метаданные заданий и трафик; логирование, запись терминала, сроки хранения и права определяет оператор, а не приложение (`PRIVACY.md:82-86`, `PRIVACY.md:140-142`).

Это **не** означает автоматически, что Google считает такие передачи «processing on behalf of the user» и не требует раскрытия. Трактовку правил нужно подтвердить по официальной форме и, при необходимости, у поддержки Play Console: <https://support.google.com/googleplay/android-developer/answer/10787469>.

### 5.3. Окончательная декларация Google

Ни одна строка ниже не является отправленной декларацией. Предварительная карта для обсуждения:

| Данные | Куда уходят | Что остаётся локально | Предварительная категория (уточнить) |
|---|---|---|---|
| Имя/пароль MeshCentral, login key | На сервер пользователя | Учётные данные и login key — только в Keystore-хранилище | Personal info / credentials; маппинг в Console не подтверждён |
| Имя устройства, node id, ОС, адрес, список устройств | На сервер пользователя | Недавние: node id и время | Device or other IDs / app activity; уточнить |
| Экран, ввод, вывод терминала, файлы | На сервер пользователя и управляемый компьютер | Кадры и вывод — в памяти; файлы — по выбору через SAF | Files and docs; messages; уточнить |
| SSH-пароль, ключ, passphrase | На сервер пользователя в текущей сессии | Только память процесса | Категория не определена; уточнить |
| Токен и события уведомлений | На сервер пользователя | Токен — в Keystore; лента — в SharedPreferences | Device or other IDs / app activity; уточнить |
| Настройки, профили, backup | Локально; backup — в выбранный файл | SharedPreferences / SAF | Не отправляется разработчику; уточнить, считается ли экспорт передачей |
| Quick Help report | Только по явному действию «поделиться» | Формируется в памяти, редактируется | Diagnostics; уточнить |

## 6. Открытые вопросы

1. **Play-вариант не подтверждён.** В текущем общем исходнике блок обновлений и `SupportCard` включены (`MeshCentralApp.kt:848`, `SettingsScreen.kt:398-400`). Нужна отдельная сборка/флаг, в которой нет GitHub-запросов, внешних обновлений и ссылки поддержки, плюс проверка отсутствия таких экранов и сетевых вызовов.
2. **Self-hosted сервер и «collection».** Нужно письменно зафиксировать, считает ли Google передачу на сервер, который контролирует пользователь, сбором данных разработчиком, и какие категории указывать.
3. **Маппинг credentials, files, messages и diagnostics** на категории Data safety не решён; нельзя выдавать предположение за ответ Console.
4. **Account deletion.** Собственного аккаунта у проекта нет; аккаунты и данные на сервере контролирует оператор. Нужно проверить, требуется ли Play-URL удаления аккаунта и как описать удаление серверных данных.
5. **Support email.** Владелец указал публичный адрес поддержки `papajon1234@gmail.com`; он записан в `app-details.json` и политике конфиденциальности.
6. **Backup.** `PRIVACY.md` не описывает экспорт настроек; файл может быть незашифрованным. Нужно решить, включать ли backup в политику и Data safety и требовать ли шифрование по умолчанию.
7. **Merged manifest и SDK.** Библиотеки (WorkManager, OkHttp, Compose) могут добавлять разрешения/компоненты; проверять только merged manifest финального артефакта.
8. **Плагин и уведомления.** Если для Play-сборки не предоставляется optional plugin, не заявлять его функции; если предоставляется — проверить, какие события и идентификаторы уходят на сервер.
9. **Сроки хранения.** Приложение не управляет логами сервера; политика должна прямо говорить, что удаление серверных данных — задача оператора.

## 7. Проверки перед заполнением формы

- [ ] Зафиксировать артефакт: package `io.github.papajon123.meshcentral`, versionCode/versionName, SHA-256 AAB/APK и дату проверки.
- [ ] Проверить подпись: `apksigner verify --print-certs` для APK; в Play Console — App integrity. Различать upload key и Play App Signing; fingerprint проверять у Play-подписанного артефакта.
- [ ] Проверить permissions: `aapt2 dump permissions` или `apkanalyzer manifest permissions`; сверить с `AndroidManifest.xml:2-12`; убедиться, что нет `AD_ID`, storage, location, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`.
- [ ] Проверить merged manifest: `allowBackup=false`, data extraction rules, отсутствие лишних exported components и добавленных библиотеками разрешений.
- [ ] Проверить SDK: dependency tree и строки APK/AAB на рекламные, аналитические и crash-SDK; отсутствие FCM.
- [ ] Перехватить трафик на тестовом устройстве: только origin сервера проверяющего; в Play-сборке не должно быть `api.github.com`, GitHub CDN, `boosty.to` и внешних update-intent.
- [ ] Пройти сценарии: вход, список устройств, desktop, terminal, files, SSH/SFTP, профили, app lock; проверить поведение при отказе в правах и недоступном сервере.
- [ ] Сверить каждый ответ формы с наблюдаемым поведением и сохранить evidence; не отправлять форму до готовности стенда и Play-сборки.

**Ссылка на официальную форму:** <https://support.google.com/googleplay/android-developer/answer/10787469>. Ничего в Google не отправлялось.
