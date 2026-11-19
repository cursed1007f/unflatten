#include "license.h"
#include <iostream>
#include <ctime>

int main() {
    lic::LicenseInfo li{};
    std::cout << "licensePath=" << lic::licensePath() << "\n";
    bool r = lic::readLicense(li);
    std::cout << "read=" << r << " valid=" << li.valid << "\n";
    std::cout << "hwid=[" << li.hwid << "]\n";
    std::cout << "key=[" << li.key << "]\n";
    std::cout << "notBefore=" << li.notBefore << " notAfter=" << li.notAfter << "\n";
    std::cout << "now=" << (long long)std::time(nullptr) << "\n";
    // isSubscriptionActive с текущим HWID — передадим то, что в файле
    std::string curHwid = li.hwid;
    std::cout << "active(same-hwid)=" << lic::isSubscriptionActive(li, curHwid) << "\n";
    // проверим: вдруг пробел/CR в конце hwid
    std::cout << "hwid len=" << li.hwid.size() << " lastbyte=" << (int)(unsigned char)li.hwid.back() << "\n";
    return 0;
}