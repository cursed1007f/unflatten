# notifier_bot.py — Telegram-бот, который ТОЛЬКО шлёт уведомления о HWID.
# Он не принимает ключи и не управляет юзерами — только доставляет сообщения
# из notifier.py (который вызывается из activate.py при активации).
#
# Настройка:
#   export HWIDBOT_TOKEN="токен_от_BotFather"   # бот-уведомитель
#   export HWIDBOT_ADMIN="123456789"            # ваш chat_id (число)
#
# Чтобы узнать свой chat_id: запустите бота, нажмите /start, затем второй раз
# запустите сюда /start — бот напишет "Ваш chat_id: <число>". После этого
# пропишите это число в HWIDBOT_ADMIN и перезапустите.
import os
import threading

import requests
import notifier

TOKEN = os.environ.get("HWIDBOT_TOKEN", "").strip()
API = f"https://api.telegram.org/bot{TOKEN}"


def send(chat_id, text):
    requests.post(f"{API}/sendMessage", json={"chat_id": chat_id, "text": text})


def handle(chat_id, text):
    cmd = (text or "").split()[0] if text else ""
    if cmd == "/start":
        send(chat_id,
             "Я бот-уведомитель о HWID активациях. Сообщения будут приходить "
             "сюда автоматически.\n\n"
             f"Ваш chat_id: <code>{chat_id}</code>\n"
             "Впишите его в HWIDBOT_ADMIN и перезапустите.")


def main():
    if not TOKEN:
        print("ОШИБКА: задайте HWIDBOT_TOKEN (токен от @BotFather).")
        return
    print("notifier_bot: токен задан, жду /start чтобы узнать chat_id...")
    print("hwid-уведомления включены:", notifier.enabled())
    offset = 0
    while True:
        try:
            r = requests.get(f"{API}/getUpdates",
                             params={"offset": offset + 1, "timeout": 30}).json()
        except Exception as e:
            print("getUpdates error:", e)
            continue
        for upd in r.get("result", []):
            offset = upd["update_id"]
            msg = upd.get("message", {})
            text = (msg.get("text") or "").strip()
            if text:
                handle(msg["chat"]["id"], text)


if __name__ == "__main__":
    main()