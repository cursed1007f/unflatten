#include "sha256.h"
#include <iostream>
#include <vector>
#include <string>

int main() {
    // Детерминизм SHA-256
    std::cout << "a=" << lic::Sha256::hexOf("abc") << "\n";
    std::cout << "b=" << lic::Sha256::hexOf("abc") << "\n";
    // Та же строка с разделителями, как computeHwid
    std::vector<std::string> comps = {"cpu=X","mac=Y","disk=","board=","guid=Z"};
    lic::Sha256 s;
    for (const auto& c : comps) { s.update(c); s.update('\n'); }
    std::cout << "hash1=" << s.hex() << "\n";
    lic::Sha256 s2;
    for (const auto& c : comps) { s2.update(c); s2.update('\n'); }
    std::cout << "hash2=" << s2.hex() << "\n";
    return 0;
}