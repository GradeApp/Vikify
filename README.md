# Vikify

Install and update with [VictoriaV's F-Droid repository](https://94043.net/fdroid/).
The page provides the repository link, signing fingerprint and QR code. This is
an independent publisher repository, separate from the F-Droid main catalog.
Установка и обновления: [репозиторий F-Droid и QR-код](https://94043.net/fdroid/).

> Free and open source Android VLESS VPN client. Supply your own server or subscription.
> Свободный VLESS VPN-клиент для Android. Подключите свой сервер или подписку.

Vikify is a functional Android VPN client using Android `VpnService`, Xray-core
and a tun2socks bridge. Both native components are built from pinned public
source included in this repository.
It imports HTTPS subscriptions from VLESS providers, plain or Base64 lists and individual VLESS links, and stores connection
profiles locally. There is no account registration or analytics in the app.

---

## Table of contents

- [English](#english)
  - [What it is](#what-it-is)
  - [Quick facts](#quick-facts)
  - [Disclaimer — TL;DR](#disclaimer--tldr)
  - [Build](#build)
  - [Documents](#documents)
- [Русский](#русский)
  - [Что это](#что-это)
  - [Ключевые факты](#ключевые-факты)
  - [Отказ от ответственности — TL;DR](#отказ-от-ответственности--tldr)
  - [Сборка](#сборка)
  - [Документы](#документы)

---

## English

### What it is

An Android application that establishes a local VPN tunnel and routes traffic
through a locally-running Xray-core instance. A main Proxy subscription supplies
REALITY, XHTTP and WebSocket + TLS profiles, with optional automatic routing and a
separate backup server (seven profiles in the current subscription). Users import, refresh, replace
or remove one subscription and choose a profile from the home screen. Previously
saved UUID access remains available until migration or removal. The import form
accepts clipboard text, live QR scans and QR images. Mixed lists require confirmation before compatible profiles are saved. HTTPS redirects are limited to three hops; certificate verification remains enabled. Specialized sing-box
subscription variants and automatic client-side profile selection are planned.
The provider's Automatic profile routes new connections on the server side.

The application uses:

- Android `VpnService` TUN setup and routing;
- Native library packaging (`arm64-v8a` only);
- VLESS / REALITY / XTLS-Vision / WebSocket-TLS protocol wiring;
- Encrypted local storage via Jetpack Security.

### Quick facts

| Property | Value |
|---|---|
| Platform | Android 8.0+ (API 26+) |
| Primary ABI | `arm64-v8a` only |
| Language / UI | Kotlin + Jetpack Compose + Material 3 |
| Version | 1.6.1 (versionCode 24; F-Droid preparation) |
| Publisher | VictoriaV · [Privacy policy](https://94043.net/vikify/privacy.html) |
| Min SDK / Target SDK | 26 / 36 |
| Third-party cores | Xray-core (`libxray.so`), tun2socks (source-built Go/JNI AAR) |
| Analytics / telemetry | None |
| Configuration source | User-provided HTTPS VLESS subscription, plain/Base64 list or individual VLESS link |
| Account / subscription | No sign-up; one imported subscription |
| Local storage | EncryptedSharedPreferences (URL, profiles, selection, refresh state; legacy UUID) |
| Licence (this project's own code) | MIT — see `LICENSE` |
| Bundled third-party licences | See `docs/BUNDLED_THIRD_PARTY_NOTICES.md` |

### Disclaimer — TL;DR

- The app is provided **as-is, with no warranty**, under the MIT license for
  its own code. There is no educational-only or non-commercial restriction.
- The authors disclaim **all liability** for any consequence, direct or
  indirect, of using or being unable to use the app.
- **You (the user) are solely responsible** for complying with the laws,
  regulations, and acceptable-use policies that apply in your jurisdiction
  and on any network you connect through.
- The app does not provide a managed circumvention service; it is a local
  networking client that you configure yourself.
- Full text: `docs/legal/EDUCATIONAL_USE_AND_DISCLAIMER.en.md`.

### Build

Linux source build and reproducibility: [F-Droid build guide](docs/FDROID_BUILD.md).
Pinned native sources and licenses: [native/README.md](native/README.md).
The previous prebuilt-native Windows workflow has been superseded by this guide.


Debug:

```
python3 scripts/build-native.py --prepare
./gradlew -PunsignedRelease=true assembleDebug
```

Unsigned release (sign separately; keystore lives **outside** the repo):

```
python3 scripts/build-native.py --prepare
./gradlew -PunsignedRelease=true assembleRelease
# Sign the verified APK separately with the existing publisher key; see docs/FDROID_BUILD.md.
```

See `docs/user-guide/USER_GUIDE.en.md` and `docs/RELEASE_SIGNING.md` for
details. A template lives at `keystore.properties.example`.

### Documents

| Topic | File |
|---|---|
| License and disclaimer (EN) | [`docs/legal/EDUCATIONAL_USE_AND_DISCLAIMER.en.md`](docs/legal/EDUCATIONAL_USE_AND_DISCLAIMER.en.md) |
| User guide (EN) | [`docs/user-guide/USER_GUIDE.en.md`](docs/user-guide/USER_GUIDE.en.md) |
| Short disclaimer | [`DISCLAIMER.md`](DISCLAIMER.md) |
| Privacy policy | [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md) |
| Data inventory | [`docs/DATA_INVENTORY.md`](docs/DATA_INVENTORY.md) |
| Security review | [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md) |
| Third-party notices | [`docs/BUNDLED_THIRD_PARTY_NOTICES.md`](docs/BUNDLED_THIRD_PARTY_NOTICES.md) |
| Release signing | [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md) |
| Supported devices / ABI | [`docs/SUPPORTED_DEVICES_AND_ABI.md`](docs/SUPPORTED_DEVICES_AND_ABI.md) |
| Build & validate | [`BUILD_AND_VALIDATE.md`](BUILD_AND_VALIDATE.md) |
| Licence (own code) | [`LICENSE`](LICENSE) |

---

## Русский

### Что это

Android-приложение, поднимающее локальный VPN-туннель и маршрутизирующее
трафик через локально работающий Xray-core. Основная подписка Proxy содержит
профили REALITY, XHTTP и WebSocket + TLS, а при настройке провайдером — «Авто»
и отдельный резервный сервер (семь профилей в текущей подписке). Пользователь импортирует одну
подписку, выбирает профиль, обновляет, заменяет или удаляет доступ с главного
экрана. Ранее сохранённый UUID работает до перехода на подписку или удаления.
Подписку можно добавить из буфера, отсканировать камерой или выбрать QR из
изображения. Специализированные sing-box-подписки и автоматический перебор
профилей на клиенте остаются следующим этапом. Профиль «Авто» направляет новые
соединения на стороне сервера.

В приложении используются:

- настройки TUN и маршрутизации через `VpnService`;
- упаковки нативных библиотек (только `arm64-v8a`);
- обвязки протоколов VLESS / REALITY / XTLS-Vision / WebSocket + TLS;
- зашифрованного локального хранилища через Jetpack Security.

### Ключевые факты

| Параметр | Значение |
|---|---|
| Платформа | Android 8.0+ (API 26+) |
| Основная ABI | Только `arm64-v8a` |
| Язык / UI | Kotlin + Jetpack Compose + Material 3 |
| Версия | 1.6.1 (versionCode 24; F-Droid preparation) |
| Издатель | VictoriaV · [Политика конфиденциальности](https://94043.net/vikify/privacy.html) |
| min SDK / target SDK | 26 / 36 |
| Сторонние ядра | Xray-core (`libxray.so`), tun2socks (Go/JNI AAR из исходников) |
| Аналитика / телеметрия | Нет |
| Источник конфигурации | HTTPS-подписка VLESS, текстовый/Base64 список или отдельная ссылка VLESS |
| Аккаунты / подписки | Без регистрации; одна импортированная подписка |
| Локальное хранилище | EncryptedSharedPreferences (URL, профили, выбор, обновление; прежний UUID) |
| Лицензия (код самого проекта) | MIT — см. `LICENSE` |
| Лицензии сторонних компонентов | см. `docs/BUNDLED_THIRD_PARTY_NOTICES.md` |

### Отказ от ответственности — TL;DR

- Приложение предоставляется **«как есть», без каких-либо гарантий**.
  Собственный код распространяется по MIT без ограничений на коммерческое
  использование или использование только в учебных целях.
- Авторы снимают **любую ответственность** за прямые и косвенные последствия
  использования или невозможности использования приложения.
- **Пользователь несёт полную ответственность** за соблюдение законов,
  нормативных актов и политик допустимого использования, применимых в его
  юрисдикции и в любой сети, через которую он подключается.
- Приложение **не является управляемым сервисом обхода блокировок** — это
  локальный сетевой клиент, который пользователь настраивает сам.
- Полный текст: `docs/legal/EDUCATIONAL_USE_AND_DISCLAIMER.ru.md`.

### Сборка

Сборка нативных компонентов теперь выполняется из исходников в Linux.
Инструменты и проверка воспроизводимости: [руководство](docs/FDROID_BUILD.md).

Отладочная:

```
python3 scripts/build-native.py --prepare
./gradlew -PunsignedRelease=true assembleDebug
```

Релизная без подписи (готовый APK подписывается прежним ключом отдельно;
keystore хранится **вне** репозитория):

```
python3 scripts/build-native.py --prepare
./gradlew -PunsignedRelease=true assembleRelease
```

Подробнее — `docs/user-guide/USER_GUIDE.ru.md` и `docs/RELEASE_SIGNING.md`.
Образец — `keystore.properties.example`.

### Документы

| Тема | Файл |
|---|---|
| Лицензия и дисклеймер (RU) | [`docs/legal/EDUCATIONAL_USE_AND_DISCLAIMER.ru.md`](docs/legal/EDUCATIONAL_USE_AND_DISCLAIMER.ru.md) |
| Руководство пользователя (RU) | [`docs/user-guide/USER_GUIDE.ru.md`](docs/user-guide/USER_GUIDE.ru.md) |
| Короткий дисклеймер | [`DISCLAIMER.md`](DISCLAIMER.md) |
| Политика приватности | [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md) |
| Инвентарь данных | [`docs/DATA_INVENTORY.md`](docs/DATA_INVENTORY.md) |
| Обзор безопасности | [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md) |
| Сторонние компоненты | [`docs/BUNDLED_THIRD_PARTY_NOTICES.md`](docs/BUNDLED_THIRD_PARTY_NOTICES.md) |
| Подпись релиза | [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md) |
| Поддерживаемые устройства / ABI | [`docs/SUPPORTED_DEVICES_AND_ABI.md`](docs/SUPPORTED_DEVICES_AND_ABI.md) |
| Сборка и валидация | [`BUILD_AND_VALIDATE.md`](BUILD_AND_VALIDATE.md) |
| Лицензия (собственный код) | [`LICENSE`](LICENSE) |
