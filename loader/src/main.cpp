// main.cpp — C++-лоадер Java-декомпилятора.
// Поток:
//   1. Вычислить HWID текущей машины.
//   2. Если есть локальная активация — проверить, что HWID совпадает и подписка
//      ещё действует. Ок -> запуск декомпилятора.
//   3. Если активации нет/истекла — активировать по ключу через сервер,
//      сохранить локально и запустить декомпилятор.
#define WIN32_LEAN_AND_MEAN
#include "hwid.h"
#include "license.h"
#include "activation.h"
#include "sha256.h"

#include <windows.h>
#include <shellapi.h>
#include <iostream>
#include <string>
#include <ctime>
#include <vector>

#pragma comment(lib, "shell32.lib")

namespace {

void banner() {
    std::cout
        << "==========================================\n"
        << "  Java Decompiler v0.1.0 — коммерческий\n"
        << "  Защита: привязка к HWID + подписка\n"
        << "==========================================\n";
}

// Читает CLI-аргументы; возвращает true, если надо запускать GUI.
bool wantGui() {
    int n;
    LPWSTR* a = CommandLineToArgvW(GetCommandLineW(), &n);
    bool gui = false;
    if (a) {
        for (int i = 1; i < n; ++i) {
            if (lstrcmpiW(a[i], L"gui") == 0 || lstrcmpiW(a[i], L"--gui") == 0) gui = true;
        }
        LocalFree(a);
    }
    return gui;
}

// Удобный вывод даты из unix-секунд.
std::string dateStr(long long ts) {
    time_t t = (time_t)ts;
    struct tm tm{};
    localtime_s(&tm, &t);
    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M", &tm);
    return buf;
}

// Ввод ключа с консоли (звёздочки не обязательны для беты).
std::string promptKey() {
    std::cout << "Введите ключ активации (выдаётся в Telegram-боте): ";
    std::string k;
    std::getline(std::cin, k);
    // трим пробелов
    size_t s = k.find_first_not_of(" \t\r\n");
    if (s == std::string::npos) return "";
    std::string t = k.substr(s);
    size_t e = t.find_last_not_of(" \t\r\n");
    return t.substr(0, e + 1);
}

void printLicense(const lic::LicenseInfo& li) {
    std::cout << "  HWID         : " << li.hwid.substr(0, 16) << "…\n";
    std::cout << "  Ключ         : " << li.key << "\n";
    std::cout << "  Действует до : " << dateStr(li.notAfter) << "\n";
    std::cout << "  (локальная активация)\n";
}

// Запускает декомпилятор. В проде подтягивай путь к jar/скрипту рядом с exe.
bool launchDecompiler(bool gui) {
    // Располагаемся относительно exe-лоадера.
    wchar_t exe[MAX_PATH] = {0};
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    std::wstring dir(exe);
    size_t slash = dir.find_last_of(L"\\/");
    if (slash != std::string::npos) dir = dir.substr(0, slash + 1);
    std::wstring jar = dir + L"java-decompiler-all.jar";
    // Проверка наличия
    if (GetFileAttributesW(jar.c_str()) == INVALID_FILE_ATTRIBUTES) {
        std::cerr << "Не найден jar декомпилятора рядом с лоадером: "
                  << std::string(jar.begin(), jar.end()) << "\n";
        return false;
    }
    std::wstring java = L"java";
    std::wstring cmd = L"\"" + java + L"\" -jar \"" + jar + L"\"";
    if (gui) cmd += L" --gui";

    // Запуск в отдельном процессе.
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    std::vector<wchar_t> cmdBuf(cmd.begin(), cmd.end());
    cmdBuf.push_back(0);
    if (!CreateProcessW(nullptr, cmdBuf.data(), nullptr, nullptr, FALSE,
                        CREATE_NO_WINDOW, nullptr, dir.c_str(), &si, &pi)) {
        std::cerr << "Не удалось запустить декомпилятор (java в PATH?).\n";
        return false;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return true;
}

} // namespace

int wmain() {
    banner();
    const bool gui = wantGui();

    // 1. HWID текущей машины
    const std::string hwid = lic::computeHwid();

    // 2. Локальная проверка
    lic::LicenseInfo li{};
    if (lic::readLicense(li) && lic::isSubscriptionActive(li, hwid)) {
        std::cout << "Лицензия активна.\n";
        printLicense(li);
        if (!launchDecompiler(gui)) return 1;
        return 0;
    }

    // 3. Нет действующей подписки.
    if (!li.valid) {
        std::cout << "Лицензия не найдена.\n";
    } else if (li.hwid != hwid) {
        std::cout << "Эта подписка привязана к другому устройству (HWID не совпал).\n";
    } else {
        std::cout << "Подписка истекла " << dateStr(li.notAfter) << ".\n";
    }
    std::cout << "Ваш HWID (отправьте его в бот поддержки при проблемах): " << hwid << "\n";

    // 4. Активация по ключу
    std::string key = promptKey();
    if (key.empty()) {
        std::cerr << "Ключ не введён. Выход.\n";
        return 2;
    }

    lic::LicenseInfo newLi{};
    lic::ActResult r = lic::activate(hwid, key, newLi);
    switch (r) {
        case lic::ActResult::Ok:
            if (!lic::writeLicense(newLi)) {
                std::cerr << "Не удалось сохранить активацию.\n";
                return 3;
            }
            std::cout << "Активация успешна!\n";
            printLicense(newLi);
            if (!launchDecompiler(gui)) return 1;
            return 0;
        case lic::ActResult::InvalidKey:
            std::cerr << "Ошибка: ключ не существует или уже использован.\n";
            break;
        case lic::ActResult::Expired:
            std::cerr << "Ошибка: ключ просрочен.\n";
            break;
        case lic::ActResult::WrongHwid:
            std::cerr << "Ошибка: ключ привязан к другому устройству.\n";
            break;
        case lic::ActResult::NetworkError:
            std::cerr << "Ошибка сети: не удалось связаться с сервером активации.\n";
            break;
        case lic::ActResult::ServerError:
        default:
            std::cerr << "Ошибка сервера активации.\n";
            break;
    }
    return 4;
}