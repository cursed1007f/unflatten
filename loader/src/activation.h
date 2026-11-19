#pragma once
// activation.h — клиент активации: лоадер шлёт HWID+ключ на сервер,
// сервер отвечает JSON с сроком действия, результат возвращается лоадеру.
#include <string>
#include "license.h"

namespace lic {

enum class ActResult {
    Ok,          // активация принята, в info передан срок
    InvalidKey,  // ключ не существует или уже занят
    Expired,     // срок отозван/закончился
    NetworkError,// сервер недоступен
    WrongHwid,   // ключ привязан к другому HWID
    ServerError, // внутренняя ошибка сервера
};

// Отправляет HWID+ключ на эндпоинт и пытается получить срок подписки.
ActResult activate(const std::string& hwid, const std::string& key, LicenseInfo& out);

// URL активации. Меняется под ваш реальный бэкенд.
const std::string& apiEndpoint();

} // namespace lic