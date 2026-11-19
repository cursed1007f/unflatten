// test_extract.cpp — проверка извлечения встроенного jar из ресурса.
#include <windows.h>
#include <cstdio>
#include <string>

#define IDR_JAR 101

// копия логики extractJar из gui.cpp (без приоритета внешнего файла)
std::string extractResourceJar() {
    HRSRC hr = FindResourceA(nullptr, "IDR_JAR", (LPCSTR)RT_RCDATA);
    if (!hr) { printf("FindResource FAIL\n"); return ""; }
    HGLOBAL hg = LoadResource(nullptr, hr);
    if (!hg) { printf("LoadResource FAIL\n"); return ""; }
    void* data = LockResource(hg);
    DWORD sz = SizeofResource(nullptr, hr);
    printf("resource found, size = %u\n", sz);

    char tmp[MAX_PATH] = {0};
    if (!GetTempPathA(MAX_PATH, tmp)) { printf("GetTempPath FAIL\n"); return ""; }
    std::string out = std::string(tmp) + "test-extracted.jar";
    FILE* f = nullptr;
    if (fopen_s(&f, out.c_str(), "wb") != 0 || !f) { printf("fopen FAIL\n"); return ""; }
    fwrite(data, 1, sz, f);
    fclose(f);
    printf("extracted to %s, %u bytes\n", out.c_str(), sz);
    return out;
}

int main() {
    std::string p = extractResourceJar();
    if (p.empty()) return 1;
    // проверка, что это валидный zip/jar
    FILE* f = nullptr;
    if (fopen_s(&f, p.c_str(), "rb") != 0) return 1;
    unsigned char hdr[4];
    fread(hdr, 1, 4, f);
    fclose(f);
    printf("magic: %02X %02X %02X %02X\n", hdr[0], hdr[1], hdr[2], hdr[3]);
    if (hdr[0]==0x50 && hdr[1]==0x4B && hdr[2]==0x03 && hdr[3]==0x04)
        printf("VALID zip/jar\n");
    else
        printf("NOT a zip\n");
    return 0;
}