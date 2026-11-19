// test_verify.cpp — проверка подписи манифеста: положительная и отрицательная.
#include "updater.h"

#include <cstdio>
#include <string>
#include <fstream>
#include <sstream>

static std::string jsonStr(const std::string& body, const std::string& key) {
    std::string pat = "\"" + key + "\"";
    size_t p = body.find(pat);
    if (p == std::string::npos) return "";
    p = body.find(':', p + pat.size());
    if (p == std::string::npos) return "";
    p = body.find('"', p);
    if (p == std::string::npos) return "";
    size_t e = body.find('"', p + 1);
    if (e == std::string::npos) return "";
    return body.substr(p + 1, e - p - 1);
}

static std::string jsonNum(const std::string& body, const std::string& key) {
    std::string pat = "\"" + key + "\"";
    size_t p = body.find(pat);
    if (p == std::string::npos) return "";
    p = body.find(':', p + pat.size());
    if (p == std::string::npos) return "";
    ++p;
    while (p < body.size() && (body[p] == ' ' || body[p] == '\t')) ++p;
    size_t start = p;
    while (p < body.size() && body[p] >= '0' && body[p] <= '9') ++p;
    return p == start ? "" : body.substr(start, p - start);
}

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: test_verify <manifest.json>\n"); return 2; }

    std::ifstream f(argv[1], std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    const std::string body = ss.str();
    if (body.empty()) { std::printf("пустой манифест\n"); return 2; }

    const std::string v = jsonStr(body, "jar_version");
    const std::string sha = jsonStr(body, "sha256");
    const std::string size = jsonNum(body, "size");
    const std::string sig = jsonStr(body, "sig");

    std::printf("version=%s size=%s\n", v.c_str(), size.c_str());

    const bool ok = updater::verifyManifest(v, sha, size, sig);
    std::printf("валидная подпись: %s\n", ok ? "OK" : "FAIL");

    std::string bad = sha;
    if (!bad.empty()) bad[0] = (bad[0] == 'a') ? 'b' : 'a';
    const bool badOk = updater::verifyManifest(v, bad, size, sig);
    std::printf("подделанный sha256 принят: %s\n", badOk ? "ДА (плохо)" : "нет (верно)");

    const std::string badSize = size.empty() ? "1" : size + "1";
    const bool badSizeOk = updater::verifyManifest(v, sha, badSize, sig);
    std::printf("подделанный size принят: %s\n", badSizeOk ? "ДА (плохо)" : "нет (верно)");

    return (ok && !badOk && !badSizeOk) ? 0 : 1;
}
