// updater.cpp — рабочий jar в %LOCALAPPDATA%\Unflatten + автообновление через WinHTTP.
#define WIN32_LEAN_AND_MEAN
#include "updater.h"
#include "sha256.h"

#include <windows.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <string>
#include <vector>
#include <cstring>
#include <cctype>

namespace updater {

namespace {

const char* kHost = "restless-hhwwit-0e4f.markgarev5566.workers.dev";
const char* kJarVersion = "0.1.0";

// Откуда качаются обновления: ассеты latest-релиза на GitHub.
// ВАЖНО: подставить свой owner/repo.
const char* kUpdateHost = "github.com";
const char* kUpdateBase = "/cursed1007f/unflatten/releases/latest/download/";

// Публичный ключ подписи манифеста (RSA-2048, PKCS#1 v1.5 + SHA-256).
const char* kPubModulusB64 =
    "1P0s47L6MGuS/IZc0ZnsdLcSuHhbLneCfkG5aL3hbCEMMWKpv+GU+pYt/Fh4+G+O9h0c3mg0c7isdfxDNi+00K"
    "Qo66CkxFkdzfGGSjNPboYYH9vnk2m2DzbUScBpjAH/RWgvQiTCibEUXFHwL3wdpcy1atNn5A1L0QTpnNB5hrWN"
    "skQ2wFknB0n13KmomEQwXlPpHjTX9UPniyN5tnVmNUBOE8AsJYOlwMMBGF0UOhCxxz2MMAMeA2zQmn2fWExIgr7K"
    "Hx6QDQCo3Dpml6FPEspP/nFc5EJNksucJjAItoT0HvTbHZN12X1ndunFnG7PCFZqH6P44AX81uVqdUcIGQ==";
const char* kPubExponentB64 = "AQAB";

std::wstring widen(const std::string& s) {
    if (s.empty()) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), &w[0], n);
    return w;
}

std::string narrow(const std::wstring& w) {
    if (w.empty()) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(n, 0);
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), &s[0], n, nullptr, nullptr);
    return s;
}

bool fileExists(const std::wstring& p) {
    return GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES;
}

bool readAll(const std::wstring& p, std::string& out) {
    HANDLE h = CreateFileW(p.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER sz{};
    if (!GetFileSizeEx(h, &sz) || sz.QuadPart < 0 || sz.QuadPart > (1LL << 30)) {
        CloseHandle(h);
        return false;
    }
    out.resize((size_t)sz.QuadPart);
    DWORD read = 0;
    bool ok = out.empty() ||
              (ReadFile(h, &out[0], (DWORD)out.size(), &read, nullptr) && read == out.size());
    CloseHandle(h);
    return ok;
}

bool writeAll(const std::wstring& p, const std::string& data) {
    HANDLE h = CreateFileW(p.c_str(), GENERIC_WRITE, 0, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    bool ok = data.empty() ||
              (WriteFile(h, data.data(), (DWORD)data.size(), &written, nullptr)
               && written == data.size());
    CloseHandle(h);
    return ok;
}

bool httpGet(const std::wstring& host, const std::wstring& path, std::string& out) {
    HINTERNET sess = WinHttpOpen(L"UnflattenDecompiler/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                 WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!sess) return false;
    WinHttpSetTimeouts(sess, 5000, 5000, 5000, 20000);

    HINTERNET conn = WinHttpConnect(sess, host.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!conn) { WinHttpCloseHandle(sess); return false; }

    HINTERNET req = WinHttpOpenRequest(conn, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                       WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    if (!req) { WinHttpCloseHandle(conn); WinHttpCloseHandle(sess); return false; }

    // GitHub отдаёт ассеты через редирект на CDN — разрешаем переходы.
    DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
    WinHttpSetOption(req, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof(policy));

    std::wstring headers = L"Accept: application/json\r\n";
    bool ok = WinHttpSendRequest(req, headers.c_str(), (DWORD)headers.size(),
                                 WINHTTP_NO_REQUEST_DATA, 0, 0, 0)
              && WinHttpReceiveResponse(req, nullptr);
    if (ok) {
        DWORD status = 0, len = sizeof(status);
        WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &len, WINHTTP_NO_HEADER_INDEX);
        if (status != 200) ok = false;
    }
    if (ok) {
        out.clear();
        char buf[8192];
        DWORD readn = 0;
        while (WinHttpReadData(req, buf, sizeof(buf), &readn) && readn > 0) {
            out.append(buf, readn);
            readn = 0;
        }
    }
    WinHttpCloseHandle(req);
    WinHttpCloseHandle(conn);
    WinHttpCloseHandle(sess);
    return ok;
}

std::string jsonStr(const std::string& body, const std::string& key) {
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

std::string jsonNum(const std::string& body, const std::string& key) {
    std::string pat = "\"" + key + "\"";
    size_t p = body.find(pat);
    if (p == std::string::npos) return "";
    p = body.find(':', p + pat.size());
    if (p == std::string::npos) return "";
    ++p;
    while (p < body.size() && (body[p] == ' ' || body[p] == '\t')) ++p;
    const size_t start = p;
    while (p < body.size() && ((body[p] >= '0' && body[p] <= '9') || body[p] == '-')) ++p;
    if (p == start) return "";
    return body.substr(start, p - start);
}

bool b64decode(const std::string& in, std::vector<unsigned char>& out) {
    static const char kB64[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    out.clear();
    int val = 0, bits = 0;
    for (char c : in) {
        if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
        if (c == '\0') return false;
        const char* p = std::strchr(kB64, c);
        if (!p) continue;
        val = (val << 6) | (int)(p - kB64);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back((unsigned char)((val >> bits) & 0xFF));
        }
    }
    return !out.empty();
}

bool rsaVerify(const std::string& payload, const std::string& sigB64) {
    std::vector<unsigned char> mod, exp, sig;
    if (!b64decode(kPubModulusB64, mod)) return false;
    if (!b64decode(kPubExponentB64, exp)) return false;
    if (!b64decode(sigB64, sig)) return false;
    if (mod.empty() || exp.empty() || sig.empty()) return false;

    lic::Sha256 h;
    h.update(payload);
    std::vector<uint8_t> digest = h.digest();

    BCRYPT_RSAKEY_BLOB hdr{};
    hdr.Magic = BCRYPT_RSAPUBLIC_MAGIC;
    hdr.BitLength = (ULONG)mod.size() * 8;
    hdr.cbPublicExp = (ULONG)exp.size();
    hdr.cbModulus = (ULONG)mod.size();

    std::vector<unsigned char> blob(sizeof(hdr) + exp.size() + mod.size());
    std::memcpy(blob.data(), &hdr, sizeof(hdr));
    std::memcpy(blob.data() + sizeof(hdr), exp.data(), exp.size());
    std::memcpy(blob.data() + sizeof(hdr) + exp.size(), mod.data(), mod.size());

    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_KEY_HANDLE key = nullptr;
    bool ok = false;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_RSA_ALGORITHM, nullptr, 0) == 0) {
        if (BCryptImportKeyPair(alg, nullptr, BCRYPT_RSAPUBLIC_BLOB, &key,
                                blob.data(), (ULONG)blob.size(), 0) == 0) {
            BCRYPT_PKCS1_PADDING_INFO pad{};
            pad.pszAlgId = BCRYPT_SHA256_ALGORITHM;
            ok = BCryptVerifySignature(key, &pad, digest.data(), (ULONG)digest.size(),
                                       sig.data(), (ULONG)sig.size(), BCRYPT_PAD_PKCS1) == 0;
        }
    }
    if (key) BCryptDestroyKey(key);
    if (alg) BCryptCloseAlgorithmProvider(alg, 0);
    return ok;
}

std::wstring dirW() {
    wchar_t buf[MAX_PATH] = {0};
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", buf, MAX_PATH);
    std::wstring base = (n > 0 && n < MAX_PATH) ? std::wstring(buf, n) : std::wstring(L".");
    std::wstring dir = base + L"\\Unflatten";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir;
}

bool extractEmbedded(const std::wstring& dest) {
    HRSRC hr = FindResourceA(nullptr, "IDR_JAR", (LPCSTR)RT_RCDATA);
    if (!hr) return false;
    HGLOBAL hg = LoadResource(nullptr, hr);
    if (!hg) return false;
    void* data = LockResource(hg);
    DWORD sz = SizeofResource(nullptr, hr);
    if (!data || !sz) return false;
    return writeAll(dest, std::string((const char*)data, sz));
}

} // namespace

const char* embeddedVersion() { return kJarVersion; }

std::string appDir() { return narrow(dirW()); }

std::string jarPath() { return narrow(dirW() + L"\\java-decompiler-all.jar"); }

std::string versionPath() { return narrow(dirW() + L"\\jar.version"); }

std::string localVersion() {
    std::string v;
    if (!readAll(dirW() + L"\\jar.version", v)) return "";
    while (!v.empty() && (v.back() == '\n' || v.back() == '\r' || v.back() == ' ')) v.pop_back();
    return v;
}

int compareVersions(const std::string& a, const std::string& b) {
    size_t i = 0, j = 0;
    while (i < a.size() || j < b.size()) {
        long x = 0, y = 0;
        while (i < a.size() && a[i] >= '0' && a[i] <= '9') x = x * 10 + (a[i++] - '0');
        while (j < b.size() && b[j] >= '0' && b[j] <= '9') y = y * 10 + (b[j++] - '0');
        if (x != y) return x < y ? -1 : 1;
        while (i < a.size() && a[i] != '.') ++i;
        while (j < b.size() && b[j] != '.') ++j;
        if (i < a.size()) ++i;
        if (j < b.size()) ++j;
    }
    return 0;
}

bool prepareJar() {
    const std::wstring dir = dirW();
    const std::wstring jar = dir + L"\\java-decompiler-all.jar";
    const std::wstring ver = dir + L"\\jar.version";

    const std::string local = localVersion();
    if (fileExists(jar) && !local.empty() && compareVersions(local, kJarVersion) >= 0) {
        return true;
    }
    if (!extractEmbedded(jar)) return false;
    writeAll(ver, std::string(kJarVersion));
    return true;
}

bool verifyManifest(const std::string& version, const std::string& sha256,
                    const std::string& size, const std::string& sigB64) {
    if (version.empty() || sha256.empty() || size.empty() || sigB64.empty()) return false;
    return rsaVerify(version + "|" + sha256 + "|" + size, sigB64);
}

Info checkLatest() {
    Info info;
    std::string body;
    const std::string manifestPath = std::string(kUpdateBase) + "manifest.json";
    if (!httpGet(widen(kUpdateHost), widen(manifestPath), body)) return info;

    info.version = jsonStr(body, "jar_version");
    info.sha256 = jsonStr(body, "sha256");
    info.url = jsonStr(body, "url");
    const std::string sig = jsonStr(body, "sig");
    const std::string sizeStr = jsonNum(body, "size");

    if (info.version.empty() || info.sha256.empty() || info.url.empty()
        || sig.empty() || sizeStr.empty()) {
        return info;
    }
    // Подпись покрывает "version|sha256|size" — иначе манифест не принимаем.
    const std::string payload = info.version + "|" + info.sha256 + "|" + sizeStr;
    if (!rsaVerify(payload, sig)) return info;

    info.ok = info.version != "unknown";
    return info;
}

bool applyUpdate(const Info& info, std::string& err) {
    if (!info.ok) { err = "нет данных об обновлении"; return false; }

    const std::string jarUrl = std::string(kUpdateBase) + "latest.jar";
    std::string body;
    if (!httpGet(widen(kUpdateHost), widen(jarUrl), body)) {
        err = "не удалось скачать обновление";
        return false;
    }
    if (body.empty()) { err = "пустой файл обновления"; return false; }

    lic::Sha256 h;
    h.update(body);
    std::string got = h.hex();
    std::string want = info.sha256;
    for (auto& c : want) c = (char)tolower((unsigned char)c);
    if (got != want) { err = "контрольная сумма не совпала"; return false; }

    const std::wstring dir = dirW();
    if (!writeAll(dir + L"\\java-decompiler-all.jar", body)) {
        err = "не удалось записать jar";
        return false;
    }
    writeAll(dir + L"\\jar.version", info.version);
    return true;
}

} // namespace updater
