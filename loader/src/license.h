#pragma once
// license.h — хранение лицензии и проверка срока годности активной подписки.
// Схема (офлайн-ядро, серверная роль — через Telegram-боты):
//   * Ключ (например "XXXX-XXXX-XXXX-XXXX") выдаёт бот, привязан к HWID.
//   * Локатор хранит активацию: hwid, ключ, срок начала/конца подписки.
//   * Лоадер при запуске сверяет HWID текущей машины с записанным в активации
//     и проверяет, что сейчас внутри [start, end]. Иначе — отказ.
#include <string>

namespace lic {

// Срок подписки, задаётся ботом при активации.
struct LicenseInfo {
    std::string hwid;    // 32-символьный hex, на какой машине активировано
    std::string key;     // сам ключ
    long long notBefore; // unix-секунды, начало действия
    long long notAfter;  // unix-секунды, конец действия
    bool valid = false;  // удалось ли прочитать и провалидировать активацию
};

// Место хранения лицензии (файл рядом с exe либо %APPDATA%).
std::string licensePath();

// Читает лицензионный файл. Возвращает true и заполняет info, если файл есть
// и синтаксически корректен.
bool readLicense(LicenseInfo& info);

// Пишет активацию (вызывается после успешного ввода ключа + проверки на боте).
bool writeLicense(const LicenseInfo& info);

// Проверяет подписку на текущий момент:
//   true  — активна на этой машине и в сроке,
//   false — ключ не совпал с HWID, лицензия просрочена или её нет.
bool isSubscriptionActive(const LicenseInfo& info, const std::string& currentHwid);

} // namespace lic