// gui.h — объявление GUI-лоадера на Win32 (тёмно-синяя тема + плавные анимации).
#pragma once
#include <string>

namespace gui {
// Вызывается GUI-процессом. HWID вычисляется в gui_main и передаётся наружу,
// чтобы не тянуть hwid.cpp в GUI (GUI может быть отдельным exe).
struct AppState {
    std::string hwid;
    std::string licensePath;
    std::string key;
    std::string expiry;      // человекочитаемая дата окончания (или "")
    bool hasLicense = false; // есть действующая локальная подписка
    bool active = false;     // подписка активна на этой машине

    // Доступное обновление декомпилятора (пусто — обновлений нет).
    std::string updateVersion;
    std::string updateSha;
    std::string updateUrl;

    // Обратный вызов для активации по ключу. Возвращает true при успехе.
    // Лоадер передаёт сюда реализацию (через gui_main.cpp): ввод ключа →
    // activate() → writeLicense(). Перед вызовом key уже заполнен.
    bool (*onActivate)(AppState&) = nullptr;
};

// Запуск GUI-цикла (блокирующий). Возвращает код для main.
int run(const AppState& st);

// Сообщить GUI о доступном обновлении (можно вызывать из фонового потока).
void notifyUpdate(const std::string& version, const std::string& sha256, const std::string& url);
} // namespace gui