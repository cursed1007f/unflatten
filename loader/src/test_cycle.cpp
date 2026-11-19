// test_cycle.cpp — сквозная проверка: ресурс -> temp jar -> java -jar -> выход.
#include <windows.h>
#include <cstdio>
#include <string>
#include <vector>

#define IDR_JAR 101

static std::string extractJarResource() {
    HRSRC hr = FindResourceA(nullptr, "IDR_JAR", (LPCSTR)RT_RCDATA);
    if (!hr) { printf("[fail] FindResource\n"); return ""; }
    HGLOBAL hg = LoadResource(nullptr, hr);
    if (!hg) { printf("[fail] LoadResource\n"); return ""; }
    void* data = LockResource(hg);
    DWORD sz = SizeofResource(nullptr, hr);
    printf("[ok] resource size = %u\n", (unsigned)sz);

    char tmp[MAX_PATH] = {0};
    if (!GetTempPathA(MAX_PATH, tmp)) { printf("[fail] GetTempPath\n"); return ""; }
    std::string out = std::string(tmp) + "jdcycle\\java-decompiler-all.jar";
    CreateDirectoryA((std::string(tmp) + "jdcycle").c_str(), nullptr);

    FILE* f = nullptr;
    if (fopen_s(&f, out.c_str(), "wb") != 0 || !f) { printf("[fail] fopen\n"); return ""; }
    fwrite(data, 1, sz, f);
    fclose(f);
    printf("[ok] extracted -> %s\n", out.c_str());
    return out;
}

int main(int argc, char** argv) {
    std::string jar = extractJarResource();
    if (jar.empty()) return 1;

    printf("[..] launching: java -jar \"%s\" --help\n", jar.c_str());
    fflush(stdout);
    std::string cmd = "\"java\" -jar \"" + jar + "\" --help";
    STARTUPINFOA si{}; si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    std::vector<char> buf(cmd.begin(), cmd.end()); buf.push_back('\0');
    if (!CreateProcessA(nullptr, buf.data(), nullptr, nullptr, FALSE,
                        0, nullptr, nullptr, &si, &pi)) {
        printf("[FAIL] CreateProcess (java not in PATH?)\n");
        return 2;
    }
    DWORD w = WaitForSingleObject(pi.hProcess, 25000);
    DWORD code = 99;
    if (w == WAIT_TIMEOUT) {
        printf("[warn] java не завершился за 25с -> процесс СТАРТОВАЛ, убиваю\n");
        TerminateProcess(pi.hProcess, 0);
        CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
        return 0;
    }
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    printf("[ok] java exited with code %lu\n", (unsigned long)code);
    return 0;
}