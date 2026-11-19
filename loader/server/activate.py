# language: python
# activate.py — активационный endpoint (FastAPI), с которым общается C++-лоадер.
# Логика:
#   1. GET /activate?hwid=..&key=..
#   2. Ищет ключ в БД.
#      - нет            -> {status:"invalid_key"}
#      - revoked        -> {status:"invalid_key"}
#      - срок истёк     -> {status:"expired"}
#      - уже привязан к другому HWID -> {status:"wrong_hwid"}
#      - иначе привязывает HWID и возвращает срок -> {status:"ok", not_before, not_after}
#   3. На повторных запусках того же юзера просто возвращает активный срок.
#
# ОДИНАКОВАЯ БД с key_bot.py (license.db), чтобы бот видел HWID юзеров.
#
# Уведомления: при активации (новой или повторной) вызывается notifier.notify_new_hwid,
# который шлёт сообщение в Telegram-бот-уведомитель (см. notifier.py). Если токен/чат
# не настроены — активация НЕ ломается, просто ничего не шлётся.

import os
import sqlite3
import time

from fastapi import FastAPI, Query           # pip install fastapi uvicorn
from pydantic import BaseModel

import notifier

DB = os.path.join(os.path.dirname(__file__), "license.db")
app = FastAPI()


def _conn():
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    return con


@app.get("/activate")
def activate(hwid: str = Query(...), key: str = Query(..., alias="key")):
    key = key.strip().upper()
    now = int(time.time())
    con = _conn()
    row = con.execute("SELECT * FROM keys WHERE key=?", (key,)).fetchone()

    if row is None:
        return {"status": "invalid_key"}
    if row["status"] == "revoked":
        return {"status": "invalid_key"}
    if row["expires"] < now:
        return {"status": "expired"}

    # Сверить HWID
    owner = row["owner"]
    if row["hwid"] is None:
        con.execute("UPDATE keys SET hwid=?, status='active' WHERE key=?", (hwid, key))
        con.commit()
        notifier.notify_new_hwid(hwid, key, owner, row["created"], row["expires"])
        return {"status": "ok", "not_before": row["created"], "not_after": row["expires"]}
    if row["hwid"] != hwid:
        notifier.notify_error(f"Попытка привязать ключ {key} к чужому HWID {hwid[:16]}…")
        return {"status": "wrong_hwid"}

    # Повторная активация того же юзера
    return {"status": "ok", "not_before": row["created"], "not_after": row["expires"]}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("activate:app", host="0.0.0.0", port=8000)