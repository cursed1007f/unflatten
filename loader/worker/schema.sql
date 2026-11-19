-- Схема D1 для лицензий Java Decompiler.
-- Выполнить в консоли базы (Dashboard -> D1 -> ваша БД -> Console).

CREATE TABLE IF NOT EXISTS keys (
  key      TEXT PRIMARY KEY,   -- XXXXX-XXXX-XXXX-XXXX
  duration TEXT,               -- 1m | 3m | 1y
  created  INTEGER,            -- unix-время выдачи
  expires  INTEGER,            -- unix-время окончания
  hwid     TEXT,               -- привязка к машине (NULL до активации)
  status   TEXT,               -- unused | active | revoked
  owner    TEXT                -- ник/комментарий владельца
);

CREATE INDEX IF NOT EXISTS idx_keys_hwid    ON keys(hwid);
CREATE INDEX IF NOT EXISTS idx_keys_status  ON keys(status);
CREATE INDEX IF NOT EXISTS idx_keys_expires ON keys(expires);

-- Журнал продаж (бот покупателей).
CREATE TABLE IF NOT EXISTS sales (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  key         TEXT,
  duration    TEXT,
  stars       INTEGER,         -- сколько звёзд заплачено
  tg_user     INTEGER,         -- telegram id покупателя
  tg_username TEXT,
  ts          INTEGER,         -- unix-время покупки
  charge_id   TEXT             -- telegram_payment_charge_id
);

CREATE INDEX IF NOT EXISTS idx_sales_ts ON sales(ts);
