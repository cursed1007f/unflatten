// license.cpp — чтение/запись/проверка активации.
#include "license.h"
#include "sha256.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlobj.h>
#include <fstream>
#include <sstream>
#include <ctime>
#include <cstdio>

namespace lic {

namespace {

std::string nowUnixStr() {
    return std::to_string((long long)std::time(nullptr));
}

bool parseLong(const std::string& s, long long& out) {
    if (s.empty()) return false;
    try {
        size_t p = 0;
        long long v = std::stoll(s, &p);
        if (p != s.size()) return false;
        out = v;
        return true;
    } catch (...) { return false; }
}

} // namespace

std::string licensePath() {
    // Пытаемся писать рядом с exe (портативно). Если не получается (нет прав),
    // файл кладётся в %APPDATA%.
    std::string dir;
    char exe[MAX_PATH] = {0};
    GetModuleFileNameA(nullptr, exe, MAX_PATH);
    std::string exePath(exe);
    size_t slash = exePath.find_last_of("\\/");
    if (slash != std::string::npos) dir = exePath.substr(0, slash + 1);

    std::string candidate = dir + "decompiler.lic";
    // Проверяем доступность: если файл сущ. или каталог доступен для записи.
    {
        std::ofstream probe(candidate, std::ios::app);
        if (probe.good()) return candidate;
    }

    // fallback: %APPDATA%/JavaDecompiler/
    char app[MAX_PATH] = {0};
    if (SUCCEEDED(SHGetFolderPathA(nullptr, CSIDL_APPDATA, nullptr, SHGFP_TYPE_CURRENT, app))) {
        std::string d = std::string(app) + "\\JavaDecompiler";
        CreateDirectoryA(d.c_str(), nullptr);
        return d + "\\decompiler.lic";
    }
    return candidate;
}

bool readLicense(LicenseInfo& info) {
    std::ifstream f(licensePath());
    if (!f) return false;
    std::string hwid, key, nbf, naf;
    std::string line;
    while (std::getline(f, line)) {
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string k = line.substr(0, eq), v = line.substr(eq + 1);
        if (k == "hwid") hwid = v;
        else if (k == "key") key = v;
        else if (k == "not_before") nbf = v;
        else if (k == "not_after") naf = v;
    }
    long long nb, na;
    if (hwid.empty() || key.empty() || !parseLong(nbf, nb) || !parseLong(naf, na))
        return false;
    info.hwid = hwid;
    info.key = key;
    info.notBefore = nb;
    info.notAfter = na;
    info.valid = true;
    return true;
}

bool writeLicense(const LicenseInfo& info) {
    std::ofstream f(licensePath(), std::ios::trunc);
    if (!f) return false;
    f << "hwid=" << info.hwid << "\n";
    f << "key=" << info.key << "\n";
    f << "not_before=" << info.notBefore << "\n";
    f << "not_after=" << info.notAfter << "\n";
    f << "saved_at=" << nowUnixStr() << "\n";
    return f.good();
}

bool isSubscriptionActive(const LicenseInfo& info, const std::string& currentHwid) {
    if (!info.valid) return false;
    if (info.hwid != currentHwid) return false; // ключ привязан к другой машине
    long long now = (long long)std::time(nullptr);
    return now >= info.notBefore && now <= info.notAfter;
}

} // namespace lic