// gui_main.cpp — точка входа GUI-лоадера (loader.exe без консоли).
// Связывает GUI (gui.cpp) с HWID/лицензией/активацией.
#define WIN32_LEAN_AND_MEAN
#include "gui.h"
#include "hwid.h"
#include "license.h"
#include "activation.h"
#include "updater.h"

#include <windows.h>
#include <string>
#include <ctime>
#include <thread>

#pragma comment(linker, "/SUBSYSTEM:WINDOWS")
#pragma comment(lib, "shell32.lib")

namespace {

std::string dateStr(long long ts) {
    if (ts <= 0) return "";
    time_t t = (time_t)ts;
    struct tm tm{};
    localtime_s(&tm, &t);
    char buf[64];
    strftime(buf, sizeof(buf), "%d.%m.%Y", &tm);
    return buf;
}

// Колбэк активации: вызывает сервер по HWID+ключу, пишет лицензию, обновляет состояние.
bool doActivate(gui::AppState& st) {
    if (st.hwid.empty() || st.key.empty()) return false;

    lic::LicenseInfo newLi{};
    lic::ActResult r = lic::activate(st.hwid, st.key, newLi);
    if (r != lic::ActResult::Ok) return false;

    if (!lic::writeLicense(newLi)) return false;

    st.hasLicense = true;
    st.active = true;
    st.expiry = dateStr(newLi.notAfter);
    return true;
}

} // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, LPWSTR, int) {
    gui::AppState st;
    st.hwid = lic::computeHwid();

    lic::LicenseInfo li{};
    if (lic::readLicense(li)) {
        st.hasLicense = true;
        st.key = li.key;
        if (lic::isSubscriptionActive(li, st.hwid)) {
            st.active = true;
            st.expiry = dateStr(li.notAfter);
        }
    }

    st.onActivate = doActivate;

    std::thread([] {
        updater::Info ui = updater::checkLatest();
        if (!ui.ok) return;
        std::string base = updater::localVersion();
        if (base.empty()) base = updater::embeddedVersion();
        if (updater::compareVersions(ui.version, base) > 0) {
            gui::notifyUpdate(ui.version, ui.sha256, ui.url);
        }
    }).detach();

    return gui::run(st);
}