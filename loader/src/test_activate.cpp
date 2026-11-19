// test_activate.cpp — живой запрос к /activate через WinHTTP (как в лоадере).
// Использование: test_activate.exe [hwid] [key]
#include "activation.h"
#include "license.h"
#include <cstdio>
#include <string>

int main(int argc, char** argv) {
    std::string hwid = argc > 1 ? argv[1] : "TESTHWID1234567890";
    std::string key = argc > 2 ? argv[2] : "TEST-AAAA-BBBB-CCCC";

    printf("endpoint: %s\n", lic::apiEndpoint().c_str());
    printf("hwid=%s key=%s\n", hwid.c_str(), key.c_str());

    lic::LicenseInfo li{};
    lic::ActResult r = lic::activate(hwid, key, li);

    const char* name = "?";
    switch (r) {
        case lic::ActResult::Ok: name = "Ok"; break;
        case lic::ActResult::InvalidKey: name = "InvalidKey"; break;
        case lic::ActResult::Expired: name = "Expired"; break;
        case lic::ActResult::WrongHwid: name = "WrongHwid"; break;
        case lic::ActResult::NetworkError: name = "NetworkError"; break;
        case lic::ActResult::ServerError: name = "ServerError"; break;
    }
    printf("result=%s (%d)\n", name, (int)r);
    if (r == lic::ActResult::Ok) {
        printf("not_before=%lld not_after=%lld\n",
               (long long)li.notBefore, (long long)li.notAfter);
    }
    return (int)r;
}