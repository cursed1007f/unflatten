# language: python
# users_bot.py — Telegram-бот для отображения всех юзеров, их HWID и сроков.
# Общая БД с key_bot.py и activate.py (license.db).
#
# Команды:
#   /users          — все активированные ключи: ник / hwid / срок действия / статус
#   /find <hwid>    — найти юзера по HWID (подстрока)
#   /expire <days>  — показать ключи, истекающие в ближайшие N дней
#   /status <key>   — детали по ключу
#
# "Ник" — это Telegram username (или tg://user?id) того, ЧЕМУ выдан ключ.
# Привязка: бот запоминает, кому какой ключ выдан командой key_bot (см. grant).

import os
import sqlite3
import time
from datetime import datetime

import requests

TOKEN = os.environ.get("USERSBOT_TOKEN", "ВАШ_ТОКЕН_БОТА")
DB = os.path.join(os.path.dirname(__file__), "license.db")
API = f"https://api.telegram.org/bot{TOKEN}"


def _conn():
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    return con


def send(chat_id, text):
    requests.post(f"{API}/sendMessage",
                  json={"chat_id": chat_id, "text": text, "parse_mode": "HTML"})


def fmt(now, expires):
    if expires < now:
        return f"<s>{datetime.fromtimestamp(expires):%d.%m.%Y}</s> (истёк)"
    return f"{datetime.fromtimestamp(expires):%d.%m.%Y}"


def handle(chat_id, cmd, args):
    con = _conn()
    now = int(time.time())

    if cmd == "/users":
        rows = con.execute(
            "SELECT key,hwid,status,expires,owner FROM keys "
            "WHERE status='active' ORDER BY expires").fetchall()
        if not rows:
            return send(chat_id, "Активных пользователей нет.")
        lines = []
        for r in rows:
            owner = r["owner"] or "—"
            hw = (r["hwid"] or "не привязан")[:16] + "…" if r["hwid"] else "—"
            lines.append(f"<b>{owner}</b>\n  hwid: {hw}\n  ключ: {r['key']}\n  до: {fmt(now, r['expires'])}")
        # Печатаем порциями (лимит 4096 chars)
        text = ""
        for ln in lines:
            if len(text) + len(ln) + 1 > 3800:
                send(chat_id, text)
                text = ""
            text += ln + "\n\n"
        if text.strip():
            send(chat_id, text)
        return

    if cmd == "/find":
        q = args[0] if args else ""
        rows = con.execute(
            "SELECT key,hwid,status,expires,owner FROM keys WHERE hwid LIKE ?", (f"%{q}%",)).fetchall()
        if not rows:
            return send(chat_id, "Ничего не найдено.")
        lines = []
        for r in rows:
            lines.append(f"<b>{r['owner'] or '—'}</b> [{r['status']}] до {fmt(now, r['expires'])}\n  hwid: {r['hwid']}")
        return send(chat_id, "\n\n".join(lines))

    if cmd == "/expire":
        try:
            days = int(args[0])
        except (IndexError, ValueError):
            return send(chat_id, "Использование: /expire <дней>")
        cutoff = now + days * 86400
        rows = con.execute(
            "SELECT key,hwid,status,expires,owner FROM keys "
            "WHERE status='active' AND expires<=? ORDER BY expires", (cutoff,)).fetchall()
        if not rows:
            return send(chat_id, f"Нет ключей, истекающих в ближайшие {days} дн.")
        return send(chat_id, "\n\n".join(
            f"<b>{r['owner'] or '—'}</b> до {fmt(now, r['expires'])}\n  {r['key']}" for r in rows))

    if cmd == "/status":
        k = args[0].strip().upper() if args else ""
        row = con.execute("SELECT * FROM keys WHERE key=?", (k,)).fetchone()
        if not row:
            return send(chat_id, "Ключ не найден.")
        return send(chat_id,
                    f"Ключ: <b>{row['key']}</b>\n"
                    f"владелец: {row['owner'] or '—'}\n"
                    f"hwid: {row['hwid'] or '—'}\n"
                    f"статус: {row['status']}\n"
                    f"до: {fmt(now, row['expires'])}\n"
                    f"выдан: {datetime.fromtimestamp(row['created']):%d.%m.%Y}")

    return send(chat_id, "Неизвестная команда.")


def main():
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
            handle(msg["chat"]["id"], cmd, args)


if __name__ == "__main__":
    main()