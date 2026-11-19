# notifier.py — модуль отправки Telegram-уведомлений о HWID.
# Используется как из activate.py (при активации), так и из notifier_bot.py.
# Токен бота-уведомителя и admin chat_id берутся из переменных окружения.
import os
import logging

import requests

log = logging.getLogger("notifier")

TOKEN = os.environ.get("HWIDBOT_TOKEN", "").strip()
ADMIN_CHAT_ID = os.environ.get("HWIDBOT_ADMIN", "").strip()


def enabled() -> bool:
    return bool(TOKEN and ADMIN_CHAT_ID)


def send_message(text: str, chat_id: str | None = None) -> bool:
    """Отправляет сообщение в чат (по умолчанию — админу). Тихая отправка."""
    if not enabled():
        log.warning("notifier не настроен: HWIDBOT_TOKEN/HWIDBOT_ADMIN не заданы")
        return False
    cid = chat_id or ADMIN_CHAT_ID
    try:
        r = requests.post(
            f"https://api.telegram.org/bot{TOKEN}/sendMessage",
            json={"chat_id": cid, "text": text, "parse_mode": "HTML"},
            timeout=10,
        )
        if r.status_code != 200:
            log.error("telegram send failed: %s %s", r.status_code, r.text)
            return False
        return True
    except Exception as e:  # сеть/таймауты не должны валить activate.py
        log.error("telegram send exception: %s", e)
        return False


def notify_new_hwid(hwid: str, key: str, owner: str | None,
                    not_before: int, not_after: int) -> bool:
    """Многострочное уведомление о (повторной) активации по HWID."""
    from datetime import datetime
    exp = datetime.fromtimestamp(not_after).strftime("%d.%m.%Y %H:%M")
    onw = owner or "не указан"
    text = (
        "<b>Новая/обновлённая активация</b>\n"
        f"HWID: <code>{hwid}</code>\n"
        f"Ключ: <code>{key}</code>\n"
        f"Владелец: {onw}\n"
        f"Действует до: {exp}"
    )
    return send_message(text)


def notify_error(message: str) -> bool:
    """Короткое уведомление об ошибке/подозрительной операции."""
    return send_message(f"<b>⚠️</b> {message}")