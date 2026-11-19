// activation.cpp — HTTP-клиент активации на WinHTTP (без внешних библиотек).
#define WIN32_LEAN_AND_MEAN
#include "activation.h"
#include "sha256.h"

#include <windows.h>
#include <winhttp.h>
#include <string>
#include <sstream>

#pragma comment(lib, "winhttp.lib")

namespace lic {

namespace {

// Простейший URL-encode для параметров запроса.
std::string urlEncode(const std::string& s) {
    std::ostringstream o;
    for (unsigned char c : s) {
        if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') o << c;
        else {
            static const char* hexc = "0123456789ABCDEF";
            o << '%' << hexc[(c >> 4) & 0xf] << hexc[c & 0xf];
        }
    }
    return o.str();
}

// Извлекает JSON-значение по ключу (упрощённый парсер, без зависимостей).
bool jsonString(const std::string& body, const std::string& key, std::string& out) {
    std::string pat = "\"" + key + "\"";
    size_t p = body.find(pat);
    if (p == std::string::npos) return false;
    p = body.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    p = body.find('"', p);
    if (p == std::string::npos) return false;
    size_t e = body.find('"', p + 1);
    if (e == std::string::npos) return false;
    out = body.substr(p + 1, e - p - 1);
    return true;
}

bool jsonLong(const std::string& body, const std::string& key, long long& out) {
    std::string s;
    if (!jsonString(body, key, s)) return false;
    try { out = std::stoll(s); return true; } catch (...) { return false; }
}

// Читает числовое значение (в т.ч. без кавычек): "not_after":1797676802
// либо в кавычках: "not_after":"1797676802".
bool jsonNumber(const std::string& body, const std::string& key, long long& out) {
    std::string pat = "\"" + key + "\"";
    size_t p = body.find(pat);
    if (p == std::string::npos) return false;
    p = body.find(':', p + pat.size());
    if (p == std::string::npos) return false;
    ++p;
    while (p < body.size() && (body[p] == ' ' || body[p] == '\t' ||
                               body[p] == '\r' || body[p] == '\n')) ++p;
    if (p < body.size() && body[p] == '"') ++p;
    const size_t start = p;
    if (p < body.size() && (body[p] == '-' || body[p] == '+')) ++p;
    while (p < body.size() && body[p] >= '0' && body[p] <= '9') ++p;
    if (p == start) return false;
    try { out = std::stoll(body.substr(start, p - start)); return true; }
    catch (...) { return false; }
}

// Выполняет GET-запрос к URL вида https://host/path?query и возвращает тело.
// Считаем, что сервер не требует TLS-проверки в бете; в проде включи сертификаты.
bool httpGet(const std::wstring& host, const std::wstring& path, std::string& outBody) {
    HINTERNET sess = WinHttpOpen(L"JavaDecompiler/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                 WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!sess) return false;
    HINTERNET conn = WinHttpConnect(sess, host.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!conn) { WinHttpCloseHandle(sess); return false; }

    HINTERNET req = WinHttpOpenRequest(conn, L"GET", path.c_str(), nullptr,
        WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    if (!req) { WinHttpCloseHandle(conn); WinHttpCloseHandle(sess); return false; }

    // TLS-сертификат сервера проверяется штатно (Cloudflare отдаёт валидный сертификат).

    std::wstring headers = L"Accept: application/json\r\n";
    bool sent = WinHttpSendRequest(req, headers.c_str(), (DWORD)headers.size(),
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0);
    if (!sent) {
        WinHttpCloseHandle(req); WinHttpCloseHandle(conn); WinHttpCloseHandle(sess);
        return false;
    }
    if (!WinHttpReceiveResponse(req, nullptr)) {
        WinHttpCloseHandle(req); WinHttpCloseHandle(conn); WinHttpCloseHandle(sess);
        return false;
    }

    std::string body;
    BYTE buf[8192];
    DWORD readn = 0;
    while (WinHttpReadData(req, buf, sizeof(buf), &readn) && readn > 0) {
        body.append((char*)buf, readn);
        readn = 0;
    }
    WinHttpCloseHandle(req); WinHttpCloseHandle(conn); WinHttpCloseHandle(sess);
    outBody = body;
    return true;
}

} // namespace

const std::string& apiEndpoint() {
    static const std::string url = "https://restless-hhwwit-0e4f.markgarev5566.workers.dev/activate";
    return url;
}

ActResult activate(const std::string& hwid, const std::string& key, LicenseInfo& out) {
    // В реальной схеме: GET https://host/activate?hwid=..&key=..&sig=..
    //   sig = sha256(hwid + ':' + key + ':' + secret) — защита от подделки запроса.
    //   СЕРВЕР возвращает { ok, not_before, not_after, status }.
    // Здесь — мини-демо уменьшенного риска: без ключа-серверного секрета JSON-ответ
    // легко подделать локально, поэтому в проде НЕ используй этот клиент дословно
    // без HMAC-подписи и убери игнор сертификатов.

    const std::string eh = urlEncode(hwid);
    const std::string ek = urlEncode(key);
    std::wstring host = L"restless-hhwwit-0e4f.markgarev5566.workers.dev";
    std::wstring path = L"/activate?hwid=" + std::wstring(eh.begin(), eh.end())
                      + L"&key=" + std::wstring(ek.begin(), ek.end());

    std::string body;
    if (!httpGet(host, path, body)) return ActResult::NetworkError;

    std::string status;
    if (!jsonString(body, "status", status)) return ActResult::ServerError;

    if (status == "ok") {
        long long nb = 0, na = 0;
        if (!jsonNumber(body, "not_before", nb) || !jsonNumber(body, "not_after", na))
            return ActResult::ServerError;
        out.hwid = hwid;
        out.key = key;
        out.notBefore = nb;
        out.notAfter = na;
        out.valid = true;
        return ActResult::Ok;
    } else if (status == "invalid_key") {
        return ActResult::InvalidKey;
    } else if (status == "expired") {
        return ActResult::Expired;
    } else if (status == "wrong_hwid") {
        return ActResult::WrongHwid;
    }
    return ActResult::ServerError;
}

} // namespace lic