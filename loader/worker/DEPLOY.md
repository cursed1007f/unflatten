# Деплой на Cloudflare (4 бота, без VPS)

Адрес воркера: `https://restless-hhwwit-0e4f.markgarev5566.workers.dev/`

---

## 0. Боты в @BotFather

Нужно 4 бота (можно создавать по одному, токены пригодятся в шаге 4):

| № | Роль | Что делает | Секрет |
|---|---|---|---|
| 1 | HWID-уведомления | шлёт тебе активации (уже готов) | `HWIDBOT_TOKEN` |
| 2 | Админ | `/gen`, `/keys`, `/revoke`, `/users` … | `KEYBOT_TOKEN` |
| 3 | Покупатели | тарифы, оплата ⭐, выдача ключа, сброс HWID | `SHOPBOT_TOKEN` |
| 4 | Безопасность | алерты: чужой HWID, события | `SECURITYBOT_TOKEN` |

У ботов 1 и 4 вебхук **не нужен** — они только отправляют сообщения.
Вебхуки ставятся только ботам 2 и 3 (шаг 5).

---

## 1. База D1

1. **Storage & Databases → D1** → база `jdec` (уже создана).
2. Открой её → **Console**.
3. Вставь целиком `schema.sql` и **Execute**.
   Если таблица `keys` уже была — скрипт безопасен, создаст только `sales`.

Проверка:

```sql
SELECT name FROM sqlite_master WHERE type='table';
```

Должны быть `keys` и `sales`.

---

## 2. Привязка базы

Воркер → **Settings → Bindings → Add → D1 database**:
* Variable name: `DB` (ровно так)
* Database: `jdec`
→ Save.

---

## 3. Код воркера

1. Воркер → **`</> Edit code`**.
2. Открой `D:\java-decompiler\loader\worker\src\index.js`, `Ctrl+A`, `Ctrl+C`.
3. В редакторе `Ctrl+A` → `Delete` → `Ctrl+V`.
4. **Deploy**.

Проверка: `https://<адрес>/` →
`{"ok":true,"service":"java-decompiler-license","version":"1.1",...}`

---

## 4. Секреты

Settings → **Variables and Secrets** → Add (тип **Secret**):

| Имя | Значение |
|---|---|
| `ADMIN_CHAT_ID` | `1900123071` |
| `HWIDBOT_TOKEN` | токен бота 1 |
| `HWIDBOT_ADMIN` | `1900123071` |
| `KEYBOT_TOKEN` | токен бота 2 |
| `SHOPBOT_TOKEN` | токен бота 3 |
| `SECURITYBOT_TOKEN` | токен бота 4 |
| `SECURITY_ADMIN` | `1900123071` |
| `PRICE_1M` | цена в звёздах, напр. `150` |
| `PRICE_3M` | напр. `400` |
| `PRICE_1Y` | напр. `1200` |
| `PRICE_RESET` | сброс HWID в звёздах, по умолчанию `259` |
| `TZ` | `Europe/Moscow` |

Необязательно (защита вебхуков):
`KEYBOT_WEBHOOK_SECRET`, `SHOPBOT_WEBHOOK_SECRET` — любая случайная строка.

После добавления секретов нажми **Deploy** ещё раз.

---

## 5. Вебхуки (только боты 2 и 3)

Бот 2 (админ):

```
https://api.telegram.org/bot<KEYBOT_TOKEN>/setWebhook?url=https://restless-hhwwit-0e4f.markgarev5566.workers.dev/tg/admin
```

Бот 3 (магазин):

```
https://api.telegram.org/bot<SHOPBOT_TOKEN>/setWebhook?url=https://restless-hhwwit-0e4f.markgarev5566.workers.dev/tg/shop
```

Если задал `*_WEBHOOK_SECRET`, добавь `&secret_token=<значение>`.

Проверка: `https://api.telegram.org/bot<TOKEN>/getWebhookInfo`
→ в `url` должен быть нужный путь, `last_error_message` отсутствовать.

---

## 6. Проверка

**Админ-бот:** напиши `/gen 1m` → должен прийти ключ.
Затем `/keys`, `/total`, `/users`.

**Бот покупателей:** напиши `/start` → тарифы с кнопками.
Нажми «1 месяц» → счёт в ⭐ → оплата → ключ придёт в чат,
тебе и в бот 4 упадёт уведомление о продаже.

**Сброс HWID:** `/start` → «🔄 Сброс HWID» → `/reset ВАШ-КЛЮЧ` → счёт на 259 ⭐.
После оплаты ключ отвязывается от компьютера (`hwid=NULL`, статус `unused`),
срок подписки не меняется — ключ можно активировать на другом ПК.

**Активация:** ключ из бота →
```
https://restless-hhwwit-0e4f.markgarev5566.workers.dev/activate?hwid=TEST123&key=КЛЮЧ
```
Ожидаемо: `{"status":"ok","not_before":...,"not_after":...}`

---

## 7. Как это работает

* **Цена** — в звёздах Telegram (`XTR`), оплата встроенная, без платёжного провайдера.
  Вывод звёзд — через Fragment (TON).
* **Ключ** при покупке создаётся сразу со статусом `unused`,
  а `owner` записывается как `telegram id` покупателя — поэтому `/mykeys` в магазине работает.
* **Сброс HWID** — отдельный товар: после оплаты у ключа очищается `hwid`
  и статус возвращается в `unused`, в `sales` пишется строка с `duration='reset'`.
  Старый ПК после этого активацию не проходит (HWID не совпадает), новый — привязывается.
* **Бот 4** получает алерты из двух мест:
  1) попытка активации ключа на чужом HWID;
  2) `GET /report?hwid=..&event=..&detail=..` — эндпоинт для будущей телеметрии клиента.
  Сейчас лоадер его ещё не вызывает: чтобы ловить «подозрительные действия»
  (дампы и т.п.), нужно добавить отправку событий в сам декомпилятор.

## Автообновление через GitHub Releases

Лоадер при запуске качает `manifest.json` из latest-релиза, проверяет подпись
и, если версия выше локальной, показывает «ОБНОВИТЬ ДО x.y.z». По нажатию
скачивает `latest.jar`, сверяет SHA-256 и кладёт в
`%LOCALAPPDATA%\Unflatten\java-decompiler-all.jar`. Exe не перекачивается.

Подпись — единственный источник доверия: даже если файл отдаст не GitHub,
без приватного ключа подделать манифест нельзя.

### 1. Репозиторий
Нужен **публичный** репозиторий (ассеты приватного релиза без токена не скачиваются).

### 2. Адрес в лоадере
`updater.cpp`:

```cpp
const char* kUpdateHost = "github.com";
const char* kUpdateBase = "/cursed1007f/unflatten/releases/latest/download/";
```

`OWNER/REPO` заменить на свой и пересобрать exe.

### 3. Подпись манифеста
Приватный ключ — `%USERPROFILE%\.unflatten-update-key.xml` (в проект не попадает),
публичный вшит в лоадер (`kPubModulusB64`).

```powershell
cd D:\java-decompiler\loader\worker
powershell -NoProfile -ExecutionPolicy Bypass -File .\sign-manifest.ps1 `
  -Jar D:\java-decompiler\build\libs\java-decompiler-0.1.0-all.jar `
  -Version 0.1.2 -OutDir .\dist-update
```

На выходе: `dist-update\manifest.json` (с полем `sig`), `version.txt`, `manifest.pubkey.txt`.

### 4. Релиз
Releases → **Draft a new release** → тег `v0.1.2` → галочка
**«Set as the latest release»** → прикрепить два ассета с точными именами:

| Ассет | Файл |
|---|---|
| `latest.jar` | fat-jar декомпилятора |
| `manifest.json` | подписанный манифест |

→ **Publish release**.

Проверка ссылок (должны открываться без авторизации):

```
https://github.com/OWNER/REPO/releases/latest/download/manifest.json
https://github.com/OWNER/REPO/releases/latest/download/latest.jar
```

### 5. Выпуск обновления
1. Собрать новый jar (`gradlew fatJar`).
2. Подписать манифест с новой версией (шаг 3).
3. Новый релиз с `latest.jar` + `manifest.json`, отметить как latest.
4. Лоадеры покажут «ОБНОВИТЬ» при следующем запуске.

Встроенная версия в лоадере — `kJarVersion` в `updater.cpp` (сейчас `0.1.0`).
Локальный jar не перезаписывается встроенным, если его версия не ниже встроенной.

Если GitHub недоступен — обновления просто нет, текущий jar продолжает работать.

Маршруты воркера `/version` и `/latest.jar` для обновлений больше не используются
(оставлены для совместимости); бакет R2 не нужен.

## Ограничения
* Workers: 100 000 запросов/сутки (бесплатно).
* D1: 5 млн чтений / 100 тыс. записей в сутки.
* Оплата ⭐ не тестировалась вживую — проверь первой покупкой малого тарифа.
