// test_hwid.cpp — диагностика: печать каждого компонента + итоговый HWID.
#include "hwid.h"
#include <iostream>

int main() {
    auto comps = lic::rawComponents();
    for (const auto& c : comps) std::cout << c << "\n";
    std::cout << "HWID=" << lic::computeHwid() << "\n";
    return 0;
}