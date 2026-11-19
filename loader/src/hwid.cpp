// hwid.cpp — сбор аппаратных идентификаторов на Windows.
// Компоненты: CPU ID, MAC-адрес, серийник диска, серийник материнской платы,
// MachineGuid из реестра. Всё хешируется в SHA-256 -> 32-символьный hex HWID.
#define WIN32_LEAN_AND_MEAN
#include "hwid.h"
#include "sha256.h"

#include <windows.h>
#include <winsock2.h>
#include <iphlpapi.h>
#include <winreg.h>
#include <tchar.h>
#include <intrin.h>
#include <string>
#include <vector>
#include <sstream>
#include <iomanip>
#include <memory>

#pragma comment(lib, "iphlpapi.lib")
#pragma comment(lib, "ws2_32.lib")

namespace lic {

namespace {

// Трим пробелов/нулевых байтов/мусора из конца строк реестра/WMI.
std::string trimZ(const std::string& in) {
    std::string s = in;
    // обрезаем по первому '\0' (WIDE-строки в реестре)
    size_t z = s.find('\0');
    if (z != std::string::npos) s.resize(z);
    while (!s.empty() && (s.back() == ' ' || s.back() == '\r' || s.back() == '\n' || s.back() == 0))
        s.pop_back();
    return s;
}

std::string cpuId() {
    // CPUID должен давать СТАБИЛЬНЫЙ результат между запусками.
    // Лист 0 даёт строку-вендора; лист 1: EAX — подпись процессора (стабилен),
    // EBX — APIC ID и число логических процессоров (МЕНЯЕТСЯ от запуска к запуску
    // в VM), EDX — биты фич (стабилен). Берём только стабильные части.
    int leaf0[4] = {0}, leaf1[4] = {0};
#if defined(_WIN32)
    __cpuid(leaf0, 0);
    __cpuid(leaf1, 1);
#endif
    // vendor-строка из leaf0 EBX:EDX:ECX
    char vendor[13];
    std::memcpy(vendor, &leaf0[1], 4);
    std::memcpy(vendor + 4, &leaf0[3], 4);
    std::memcpy(vendor + 8, &leaf0[2], 4);
    vendor[12] = '\0';
    // EAX листа 1: семейство/модель/степпинг + EDX: биты фич
    std::ostringstream o;
    o << vendor << "-" << std::hex
      << (unsigned int)leaf1[0] << "-" << (unsigned int)leaf1[3];
    return o.str();
}

std::string macAddress() {
    ULONG size = 0;
    if (GetAdaptersAddresses(AF_UNSPEC, 0, nullptr, nullptr, &size) != ERROR_BUFFER_OVERFLOW)
        return "";
    std::vector<BYTE> buf(size);
    auto* adapters = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
    if (GetAdaptersAddresses(AF_UNSPEC, 0, nullptr, adapters, &size) != NO_ERROR)
        return "";
    // Только действующие, не-loopback адаптеры; берём первый с корректным MAC.
    for (auto* a = adapters; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        if (a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        if (a->PhysicalAddressLength != 6) continue;
        std::ostringstream o;
        for (DWORD i = 0; i < a->PhysicalAddressLength; i++) {
            if (i) o << ":";
            o << std::hex << std::setw(2) << std::setfill('0') << (int)a->PhysicalAddress[i];
        }
        return o.str();
    }
    return "";
}

std::string readRegistryValue(HKEY root, const char* subKey, const char* value) {
    HKEY hk = nullptr;
    if (RegOpenKeyExA(root, subKey, 0, KEY_READ | KEY_WOW64_64KEY, &hk) != ERROR_SUCCESS)
        return "";
    char buf[256] = {0};
    DWORD type = 0, sz = sizeof(buf);
    LONG r = RegQueryValueExA(hk, value, nullptr, &type, (LPBYTE)buf, &sz);
    RegCloseKey(hk);
    if (r != ERROR_SUCCESS) return "";
    if (type == REG_SZ || type == REG_EXPAND_SZ)
        return trimZ(std::string(buf, sz));
    if (type == REG_DWORD)
        return std::to_string(*reinterpret_cast<DWORD*>(buf));
    return trimZ(std::string(buf, sz));
}

std::string machineGuid() {
    return readRegistryValue(HKEY_LOCAL_MACHINE,
        "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid");
}

// Вызов команды (WMI disk / baseboard) через cmd и возврат stdout.
std::string runShell(const std::string& cmdline) {
    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    HANDLE rd = nullptr, wr = nullptr;
    if (!CreatePipe(&rd, &wr, &sa, 0)) return "";
    SetHandleInformation(wr, HANDLE_FLAG_INHERIT, 0); // важно: wr не наследуется

    PROCESS_INFORMATION pi{};
    STARTUPINFOA si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.hStdOutput = wr;
    si.hStdError = nullptr;
    si.hStdInput = nullptr;
    si.wShowWindow = SW_HIDE;

    std::string cmd = "cmd.exe /C " + cmdline;
    std::vector<char> cmdBuf(cmd.begin(), cmd.end());
    cmdBuf.push_back('\0');

    BOOL ok = CreateProcessA(nullptr, cmdBuf.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
    CloseHandle(wr); // родитель закрывает свою копию писателя
    if (!ok) { CloseHandle(rd); return ""; }
    WaitForSingleObject(pi.hProcess, 4000);

    std::string out;
    char buf[4096];
    DWORD readn = 0;
    while (ReadFile(rd, buf, sizeof(buf), &readn, nullptr) && readn > 0)
        out.append(buf, readn);

    CloseHandle(rd);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return trimZ(out);
}

std::string diskSerial() {
    // "wmic diskdrive get serialnumber" — версия WMIC; на новых системах wmic может
    // отсутствовать, тогда падаем на PowerShell, затем на пустую строку.
    std::string s = runShell("wmic diskdrive where index=0 get serialnumber /value");
    if (s.find("SerialNumber=") != std::string::npos) s = s.substr(s.find("SerialNumber=") + 13);
    return s;
}

std::string baseboardSerial() {
    std::string s = runShell("wmic baseboard get serialnumber /value");
    if (s.find("SerialNumber=") != std::string::npos) s = s.substr(s.find("SerialNumber=") + 13);
    return s;
}

} // namespace

std::vector<std::string> rawComponents() {
    // ВАЖНО: HWID должен быть детерминирован и стабилен между запусками.
    // Компоненты через wmic (disk/board) нестабильны (медленные, меняют ответ,
    // требуют прав) — поэтому НЕ используем их в HWID. Берём только надёжные:
    // CPUID + MAC + MachineGuid. Этого достаточно для привязки к машине.
    std::vector<std::string> v;
    v.push_back("cpu=" + cpuId());
    v.push_back("mac=" + macAddress());
    v.push_back("guid=" + machineGuid());
    return v;
}

std::string computeHwid() {
    const auto comps = rawComponents();
    Sha256 s;
    for (const auto& c : comps) {
        s.update(c);
        s.update('\n'); // неявный разделитель
    }
    return s.hex();
}

} // namespace lic