# language: python
# keybot.py — Telegram-бот для генерации и управления ключами.
# Второй бот (usersbot) — см. users_bot.py. Оба делят одну БД (ключи + юзеры).
#
# Команды бота (для вас, как администратора продаж):
#   /gen 1m      — ключ на 1 месяц
#   /gen 3m      — ключ на 3 месяца
#   /gen 1y      — ключ на 1 год
#   /keys        — список всех ключей и их статус
#   /revoke <key>— отозвать ключ
#   /hwid <key>  — к какому HWID привязан ключ (если активирован)
#
# Эмитируется: status может быть unused | active | expired | revoked.
# Ключ при активации привязывается к HWID в users_bot / активационном endpoint.

import os
import secrets
import sqlite3
import time
from datetime import datetime, timedelta

import requests  # pip install requests

TOKEN = os.environ.get("KEYBOT_TOKEN", "ВАШ_ТОКЕН_БОТА")
DB = os.path.join(os.path.dirname(__file__), "license.db")
API = f"https://api.telegram.org/bot{TOKEN}"

DURATIONS = {
    "1m": timedelta(days=30),
    "3m": timedelta(days=90),
    "1y": timedelta(days=365),
}


def init_db():
    con = sqlite3.connect(DB)
    con.execute("""
        CREATE TABLE IF NOT EXISTS keys (
            key TEXT PRIMARY KEY,
            duration TEXT,
            created INTEGER,
            expires INTEGER,
            hwid TEXT,
            status TEXT,
            owner TEXT
        )
    """)
    # совместимость со старыми базами без owner
    cols = [c[1] for c in con.execute("PRAGMA table_info(keys)").fetchall()]
    if "owner" not in cols:
        con.execute("ALTER TABLE keys ADD COLUMN owner TEXT")
    con.commit()
    return con


def gen_key():
    # Формат XXXX-XXXX-XXXX-XXXX
    groups = []
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # без похожих O/0/I/1
    for _ in range(4):
        groups.append("".join(secrets.choice(alphabet) for _ in range(4)))
    return "-".join(groups)


def send(chat_id, text):
    requests.post(f"{API}/sendMessage", json={"chat_id": chat_id, "text": text})


def handle(bot, chat_id, cmd, args):
    con = init_db()
    if cmd == "/gen":
        if not args:
            return send(chat_id, "Использование: /gen 1m | 3m | 1y")
        dur = args[0].strip().lower()
        if dur not in DURATIONS:
            return send(chat_id, "Допустимо: 1m, 3m, 1y")
        k = gen_key()
        now = int(time.time())
        exp = int((datetime.now() + DURATIONS[dur]).timestamp())
        con.execute("INSERT INTO keys(key,duration,created,expires,status) VALUES(?,?,?,?,?)",
                    (k, dur, now, exp, "unused"))
        con.commit()
        return send(chat_id, f"Ключ сгенерирован:\n`{k}`\nСрок: {dur} (до {datetime.fromtimestamp(exp):%d.%m.%Y %H:%M})")
    if cmd == "/keys":
        rows = con.execute("SELECT key,duration,status FROM keys ORDER BY created DESC LIMIT 30").fetchall()
        if not rows:
            return send(chat_id, "Ключей пока нет.")
        text = "\n".join(f"{r[0]}  [{r[1]}]  {r[2]}" for r in rows)
        return send(chat_id, text)
    if cmd == "/revoke":
        k = args[0].strip().upper() if args else ""
        cur = con.execute("UPDATE keys SET status='revoked' WHERE key=?", (k,))
        con.commit()
        return send(chat_id, "Отозвано." if cur.rowcount else "Ключ не найден.")
    if cmd == "/hwid":
        k = args[0].strip().upper() if args else ""
        row = con.execute("SELECT hwid,status,expires,owner FROM keys WHERE key=?", (k,)).fetchone()
        if not row:
            return send(chat_id, "Ключ не найден.")
        return send(chat_id, f"owner={row[3]} hwid={row[0]} status={row[1]} exp={row[2]}")
    if cmd == "/grant":
        # /grant <ключ> <ник> — запомнить владельца (ник в Telegram)
        if len(args) < 2:
            return send(chat_id, "Использование: /grant <ключ> <ник>")
        k = args[0].strip().upper()
        owner = " ".join(args[1:])
        cur = con.execute("UPDATE keys SET owner=? WHERE key=?", (owner, k))
        con.commit()
        return send(chat_id, "Владелец записан." if cur.rowcount else "Ключ не найден.")
    if cmd == "/total":
        rows = con.execute(
            "SELECT status, COUNT(*) FROM keys GROUP BY status").fetchall()
        text = "\n".join(f"{s}: {c}" for s, c in rows) if rows else "Ключей нет."
        return send(chat_id, text)
    return send(chat_id, "Неизвестная команда.")


def main():
    bot = int(TOKEN.split(":")[0])
    offset = 0
    while True:
        r = requests.get(f"{API}/getUpdates",
                         params={"offset": offset + 1, "timeout": 30}).json()
        for upd in r.get("result", []):
            offset = upd["update_id"]
            msg = upd.get("message", {})
            text = (msg.get("text") or "").strip()
            if not text:
                continue
            parts = text.split()
            cmd, args = parts[0].split("@")[0], parts[1:]
            handle(bot, msg["chat"]["id"], cmd, args)


if __name__ == "__main__":
    main()