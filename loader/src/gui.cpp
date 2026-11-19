// gui.cpp — тёмно-синий GUI лоадера с плавными анимациями.
// Технологии: нативная Win32 + GDI (без внешних библиотек).
// Элементы: градиентный фон, заголовок, надпись состояния, анимированный
// индикатор-«пульс», поле ввода ключа, кнопки. Переключение вкладок отсутствует.
#define WIN32_LEAN_AND_MEAN
#include "gui.h"
#include "updater.h"

#include <windows.h>
#include <windowsx.h>
#include <shellapi.h>
#include <string>
#include <cmath>
#include <algorithm>
#include <vector>
#include <atomic>
#include <thread>
#include <chrono>
#include <mutex>
#include <cstring>
#include <objidl.h>
#include <gdiplus.h>

#pragma comment(lib, "comctl32.lib")

// Идентификатор встроенного jar-ресурса (см. compiler.rc)
#define IDR_JAR 101
#define IDR_LOGO 102
#define WM_APP_UPDATE (WM_APP + 7)

namespace gui {

namespace {

// Палитра (тёмно-синяя)
constexpr COLORREF COL_BG_TOP    = RGB(0x0a, 0x12, 0x2e);
constexpr COLORREF COL_BG_BOT    = RGB(0x10, 0x1f, 0x48);
constexpr COLORREF COL_ACCENT    = RGB(0x2f, 0x6f, 0xff); // ярко-синий
constexpr COLORREF COL_ACCENT2   = RGB(0x7c, 0xb9, 0xff);
constexpr COLORREF COL_TEXT      = RGB(0xe8, 0xef, 0xff);
constexpr COLORREF COL_MUTED     = RGB(0x8a, 0x9b, 0xc8);
constexpr COLORREF COL_BORDER    = RGB(0x2a, 0x3a, 0x66);
constexpr COLORREF COL_BTN       = RGB(0x1b, 0x2a, 0x5c);
constexpr COLORREF COL_BTN_HOV   = RGB(0x24, 0x38, 0x78);
constexpr COLORREF COL_INPUT     = RGB(0x0d, 0x18, 0x38);
constexpr COLORREF COL_OK        = RGB(0x3c, 0xe8, 0x9a);
constexpr COLORREF COL_ERR       = RGB(0xff, 0x6b, 0x6b);
constexpr COLORREF COL_PROG      = RGB(0x2f, 0x6f, 0xff);

// Размеры окна
constexpr int W = 520;
constexpr int H = 620;
constexpr int R = 18;   // радиус скругления карточек

HWND g_hwnd = nullptr;
HWND g_keyEdit = nullptr;
HWND g_btnAction = nullptr;
AppState g_st;

// Шрифты
HFONT fTitle = nullptr, fBody = nullptr, fSmall = nullptr, fInput = nullptr, fBtn = nullptr;

// Анимация
volatile float g_pulse = 0.f;      // 0..1 фаза пульса
std::atomic<bool> g_animRun{true};
bool g_readyUi = false;
Gdiplus::Bitmap* g_logo = nullptr;
ULONG_PTR g_gpToken = 0;
HDC g_bgDc = nullptr;
HBITMAP g_bgBmp = nullptr;
bool g_dragging = false;

std::mutex g_updMx;
struct PendingUpdate {
    bool has = false;
    std::string version, sha, url;
} g_pending;

struct Widget { RECT rc; bool hover = false; };

// Кнопка «Активировать / Запустить»
Widget g_actionBtn;
Widget g_closeBtn;
RECT g_card;

// ---------------- Хелперы отрисовки ----------------
void fillRectB(HDC dc, const RECT& r, COLORREF c) {
    HBRUSH b = CreateSolidBrush(c);
    RECT rr = r;
    FillRect(dc, &rr, b);
    DeleteObject(b);
}

void roundRectB(HDC dc, const RECT& r, int rad, COLORREF fill) {
    HBRUSH b = CreateSolidBrush(fill);
    HPEN pe = CreatePen(PS_SOLID, 1, COL_BORDER);
    HGDIOBJ ob = SelectObject(dc, b);
    HGDIOBJ op = SelectObject(dc, pe);
    RoundRect(dc, r.left, r.top, r.right, r.bottom, rad, rad);
    SelectObject(dc, ob); SelectObject(dc, op);
    DeleteObject(b); DeleteObject(pe);
}

void textAt(HDC dc, const std::string& s, int x, int y, HFONT f, COLORREF c, bool center = false) {
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, c);
    SelectObject(dc, f);

    // Строки в коде — UTF-8; GDI ждёт UTF-16, иначе кириллица превращается в кракозябры.
    std::wstring w;
    if (!s.empty()) {
        int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
        w.resize(n);
        MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), &w[0], n);
    }

    if (center) {
        RECT rc{0, y, W, 2000};
        DrawTextW(dc, w.c_str(), (int)w.size(), &rc, DT_CENTER | DT_TOP | DT_NOPREFIX);
    } else {
        TextOutW(dc, x, y, w.c_str(), (int)w.size());
    }
}

// Градиент по вертикали
void vGrad(HDC dc, const RECT& r, COLORREF top, COLORREF bot) {
    int hh = (r.bottom - r.top > 1) ? (r.bottom - r.top) : 1;
    for (int y = r.top; y < r.bottom; ++y) {
        float t = (float)(y - r.top) / (float)hh;
        int Rv = GetRValue(top) + (int)((GetRValue(bot) - GetRValue(top)) * t);
        int Gv = GetGValue(top) + (int)((GetGValue(bot) - GetGValue(top)) * t);
        int Bv = GetBValue(top) + (int)((GetBValue(bot) - GetBValue(top)) * t);
        HPEN p = CreatePen(PS_SOLID, 1, RGB(Rv, Gv, Bv));
        HGDIOBJ o = SelectObject(dc, p);
        MoveToEx(dc, r.left, y, nullptr); LineTo(dc, r.right, y);
        SelectObject(dc, o); DeleteObject(p);
    }
}

std::string ws(const std::wstring& w) {
    if (w.empty()) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string s(n, 0);
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, &s[0], n, nullptr, nullptr);
    if (!s.empty() && s.back() == 0) s.pop_back();
    return s;
}

std::wstring u8w(const std::string& s) {
    if (s.empty()) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), &w[0], n);
    return w;
}

void msgBox(HWND h, const std::string& text, UINT flags) {
    MessageBoxW(h, u8w(text).c_str(), L"Unflatten Decompiler", flags);
}

// ---------------- Логотип (GDI+) ----------------

void loadLogo() {
    HRSRC hr = FindResourceA(nullptr, "IDR_LOGO", (LPCSTR)RT_RCDATA);
    if (!hr) return;
    HGLOBAL hg = LoadResource(nullptr, hr);
    if (!hg) return;
    void* data = LockResource(hg);
    DWORD sz = SizeofResource(nullptr, hr);
    if (!data || !sz) return;

    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, sz);
    if (!mem) return;
    void* dst = GlobalLock(mem);
    memcpy(dst, data, sz);
    GlobalUnlock(mem);

    IStream* st = nullptr;
    if (SUCCEEDED(CreateStreamOnHGlobal(mem, TRUE, &st)) && st) {
        Gdiplus::Bitmap* bmp = new Gdiplus::Bitmap(st, FALSE);
        if (bmp->GetLastStatus() == Gdiplus::Ok) g_logo = bmp;
        else delete bmp;
        st->Release();
    } else {
        GlobalFree(mem);
    }
}

void drawLogo(HDC dc, int x, int y, int size, float alpha) {
    if (!g_logo || size <= 0) return;
    Gdiplus::Graphics g(dc);
    g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
    g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
    Gdiplus::ImageAttributes attr;
    Gdiplus::ColorMatrix cm = {};
    cm.m[0][0] = 1.f; cm.m[1][1] = 1.f; cm.m[2][2] = 1.f;
    cm.m[3][3] = alpha; cm.m[4][4] = 1.f;
    attr.SetColorMatrix(&cm, Gdiplus::ColorMatrixFlagsDefault, Gdiplus::ColorAdjustTypeBitmap);
    g.DrawImage(g_logo, Gdiplus::Rect(x, y, size, size), 0, 0,
                (int)g_logo->GetWidth(), (int)g_logo->GetHeight(),
                Gdiplus::UnitPixel, &attr);
}

void fillPoly(HDC dc, const POINT* p, int n, COLORREF c) {
    HBRUSH b = CreateSolidBrush(c);
    HPEN pen = CreatePen(PS_SOLID, 1, c);
    HGDIOBJ ob = SelectObject(dc, b);
    HGDIOBJ op = SelectObject(dc, pen);
    Polygon(dc, p, n);
    SelectObject(dc, ob);
    SelectObject(dc, op);
    DeleteObject(b);
    DeleteObject(pen);
}

void drawPlay(HDC dc, int x, int y, int h) {
    POINT p[3] = {{x, y}, {x, y + h}, {x + (int)(h * 0.78), y + h / 2}};
    fillPoly(dc, p, 3, COL_ACCENT2);
}

// ---------------- Статичный слой (кэшируется) ----------------
void drawStatic(HDC dc) {
    RECT full{0, 0, W, H};
    vGrad(dc, full, COL_BG_TOP, COL_BG_BOT);

    drawLogo(dc, 235, -70, 340, 0.33f);

    const int ls = 88;
    drawLogo(dc, (W - ls) / 2, 8, ls, 1.0f);

    const int cardTop = 190, cardH = 150;
    g_card = {28, cardTop, W-28, cardTop+cardH};
    roundRectB(dc, g_card, R, RGB(0x0f,0x1b,0x3f));

    RECT inputR{28, cardTop+cardH+30, W-28, cardTop+cardH+30+46};
    roundRectB(dc, inputR, R, COL_INPUT);
}

// ---------------- Динамический слой ----------------
void draw(HDC dc) {
    if (g_bgDc) BitBlt(dc, 0, 0, W, H, g_bgDc, 0, 0, SRCCOPY);
    else drawStatic(dc);

    const int cardTop = 190;

    textAt(dc, "Unflatten Decompiler", W/2, 108, fTitle, COL_TEXT, true);
    textAt(dc, "Декомпилятор Jar-файлов", W/2, 148, fSmall, COL_MUTED, true);

    // Строка состояния
    std::string status, detail;
    if (g_st.active) {
        status = "Статус: АКТИВНА";
        detail = g_st.expiry.empty()
            ? "Действующая подписка на этом устройстве."
            : "Подписка действует до " + g_st.expiry + ".";
    } else if (g_st.hasLicense) {
        status = "Статус: ПРОСРОЧЕНА / ЧУЖОЙ HWID";
        detail = "Ваш HWID не совпал с привязанным или срок истёк.";
    } else {
        status = "Статус: нет лицензии";
        detail = "Введите ключ активации для продолжения.";
    }
    textAt(dc, status, 48, cardTop + 22, fBody,
           g_st.active ? COL_OK : COL_ERR);
    textAt(dc, "HWID: " + g_st.hwid, 48, cardTop + 58, fSmall, COL_MUTED);
    textAt(dc, detail, 48, cardTop + 86, fSmall, COL_MUTED);

    // Анимированный индикатор «пульс» (если нет активной лицензии)
    if (!g_st.active) {
        int cx = W-52, cy = cardTop + 30;
        float ph = g_pulse;
        // кольцо
        HPEN pe = CreatePen(PS_SOLID, 2, COL_ACCENT2);
        HGDIOBJ op = SelectObject(dc, pe);
        HBRUSH ob = (HBRUSH)SelectObject(dc, GetStockObject(NULL_BRUSH));
        int rad = 6 + (int)(ph * 12.f);
        Ellipse(dc, cx-rad, cy-rad, cx+rad, cy+rad);
        SelectObject(dc, op); SelectObject(dc, ob);
        DeleteObject(pe);
        // внутренний пульс
        int r2 = 2 + (int)(ph * 5.f);
        HBRUSH b = CreateSolidBrush(COL_ACCENT2);
        HGDIOBJ obb = SelectObject(dc, b);
        Ellipse(dc, cx-r2, cy-r2, cx+r2, cy+r2);
        SelectObject(dc, obb); DeleteObject(b);
    } else {
        // галочка-OK
        HPEN pe = CreatePen(PS_SOLID, 3, COL_OK);
        HGDIOBJ op = SelectObject(dc, pe);
        int cx = W-52, cy = cardTop + 30;
        MoveToEx(dc, cx-10, cy, nullptr); LineTo(dc, cx-2, cy+8);
        LineTo(dc, cx+12, cy-10);
        SelectObject(dc, op); DeleteObject(pe);
    }

    // Кнопка действия
    RECT btn{28, H-96, W-28, H-50};
    bool hover = g_actionBtn.hover;
    // плавное изменение цвета кнопки по hover — просто берём hover-цвет
    COLORREF bf = hover ? COL_BTN_HOV : COL_BTN;
    roundRectB(dc, btn, R, bf);
    std::string bt = (g_st.active && !g_st.updateVersion.empty())
        ? "  ОБНОВИТЬ ДО " + g_st.updateVersion
        : (g_st.active ? "  ЗАПУСТИТЬ ДЕКОМПИЛЯТОР" : "  АКТИВИРОВАТЬ");
    textAt(dc, bt, W/2, btn.top + 11, fBtn, COL_TEXT, true);
    // треугольник/стрелку
    if (g_st.active) drawPlay(dc, btn.left + 18, btn.top + 14, 18);

    const int cs = 15;
    const int ccx = (g_closeBtn.rc.left + g_closeBtn.rc.right) / 2;
    const int ccy = (g_closeBtn.rc.top + g_closeBtn.rc.bottom) / 2;
    if (g_closeBtn.hover) {
        HBRUSH hb = CreateSolidBrush(RGB(0x22, 0x2e, 0x55));
        HPEN hp = CreatePen(PS_SOLID, 1, RGB(0x22, 0x2e, 0x55));
        HGDIOBJ ob = SelectObject(dc, hb);
        HGDIOBJ op = SelectObject(dc, hp);
        Ellipse(dc, ccx - cs, ccy - cs, ccx + cs, ccy + cs);
        SelectObject(dc, ob);
        SelectObject(dc, op);
        DeleteObject(hb);
        DeleteObject(hp);
    }
    HPEN px = CreatePen(PS_SOLID, 2, g_closeBtn.hover ? COL_TEXT : COL_MUTED);
    HGDIOBJ opx = SelectObject(dc, px);
    MoveToEx(dc, ccx - 5, ccy - 5, nullptr);
    LineTo(dc, ccx + 5, ccy + 5);
    MoveToEx(dc, ccx + 5, ccy - 5, nullptr);
    LineTo(dc, ccx - 5, ccy + 5);
    SelectObject(dc, opx);
    DeleteObject(px);
}

// ---------------- Анимационный поток ----------------
void animLoop() {
    auto t0 = std::chrono::steady_clock::now();
    while (g_animRun.load()) {
        auto now = std::chrono::steady_clock::now();
        float secs = std::chrono::duration<float>(now - t0).count();
        g_pulse = 0.5f + 0.5f * (float)sin(secs * 2.4); // медленное дыхание
        if (g_hwnd && !g_dragging) InvalidateRect(g_hwnd, nullptr, FALSE);
        std::this_thread::sleep_for(std::chrono::milliseconds(33)); // ~30fps
    }
}

// ---------------- Извлечение встроенного jar ----------------
// Извлекает ресурс IDR_JAR (decompiler) во временную папку и возвращает путь.
// Если jar лежит рядом с exe (режим отладки) — используем его напрямую.
std::string extractJar() {
    // 1) jar рядом с exe (разработка/отладка) — приоритет
    char exe[MAX_PATH] = {0};
    GetModuleFileNameA(nullptr, exe, MAX_PATH);
    std::string exePath(exe);
    size_t slash = exePath.find_last_of("\\/");
    std::string dir = slash != std::string::npos ? exePath.substr(0, slash + 1) : "";
    std::string local = dir + "java-decompiler-all.jar";
    if (GetFileAttributesA(local.c_str()) != INVALID_FILE_ATTRIBUTES)
        return local;

    // 2) извлечь встроенный ресурс
    // ресурс лежит под именем-строкой "IDR_JAR" (см. compiler.rc), а не под номером 101
    HRSRC hr = FindResourceA(nullptr, "IDR_JAR", (LPCSTR)RT_RCDATA);
    if (!hr) return "";
    HGLOBAL hg = LoadResource(nullptr, hr);
    if (!hg) return "";
    void* data = LockResource(hg);
    DWORD sz = SizeofResource(nullptr, hr);

    // временная папка для jar
    char tmp[MAX_PATH] = {0};
    if (!GetTempPathA(MAX_PATH, tmp)) return "";
    std::string outPath = std::string(tmp) + "java-decompiler-all.jar";

    FILE* f = nullptr;
    if (fopen_s(&f, outPath.c_str(), "wb") != 0 || !f) return "";
    fwrite(data, 1, sz, f);
    fclose(f);
    return outPath;
}

// ---------------- Сообщения окна ----------------
LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CTLCOLOREDIT: {
            // Тёмная тема для поля ввода (кисть создаётся один раз)
            static HBRUSH inputBrush = CreateSolidBrush(COL_INPUT);
            HDC dc = (HDC)wp;
            SetTextColor(dc, COL_TEXT);
            SetBkColor(dc, COL_INPUT);
            return (LRESULT)inputBrush;
        }
        case WM_CREATE:
        break;
        case WM_ERASEBKGND:
            return 1;
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC dc = BeginPaint(hwnd, &ps);
            RECT rc{};
            GetClientRect(hwnd, &rc);
            const int cw = rc.right - rc.left, ch = rc.bottom - rc.top;
            HDC mem = CreateCompatibleDC(dc);
            HBITMAP bmp = CreateCompatibleBitmap(dc, cw, ch);
            HGDIOBJ old = SelectObject(mem, bmp);
            draw(mem);
            BitBlt(dc, 0, 0, cw, ch, mem, 0, 0, SRCCOPY);
            SelectObject(mem, old);
            DeleteObject(bmp);
            DeleteDC(mem);
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_MOUSEMOVE: {
            POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            bool nh = PtInRect(&g_actionBtn.rc, pt);
            bool nhc = PtInRect(&g_closeBtn.rc, pt);
            if (nh != g_actionBtn.hover || nhc != g_closeBtn.hover) {
                g_actionBtn.hover = nh;
                g_closeBtn.hover = nhc;
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            SetCursor(LoadCursor(nullptr, (nh || nhc) ? IDC_HAND : IDC_ARROW));
            return 0;
        }
        case WM_ENTERSIZEMOVE:
            g_dragging = true;
            return 0;
        case WM_EXITSIZEMOVE:
            g_dragging = false;
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_LBUTTONDOWN: {
            POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            if (!PtInRect(&g_actionBtn.rc, pt) && !PtInRect(&g_closeBtn.rc, pt)) {
                POINT sc = pt;
                ClientToScreen(hwnd, &sc);
                ReleaseCapture();
                SendMessageW(hwnd, WM_NCLBUTTONDOWN, HTCAPTION, MAKELPARAM(sc.x, sc.y));
            }
            return 0;
        }
        case WM_LBUTTONUP: {
            POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            if (PtInRect(&g_closeBtn.rc, pt)) {
                PostMessage(hwnd, WM_CLOSE, 0, 0);
                return 0;
            }
            if (PtInRect(&g_actionBtn.rc, pt)) {
                PostMessage(hwnd, WM_COMMAND, MAKEWPARAM(1, BN_CLICKED), 0);
            }
            return 0;
        }
        case WM_TIMER:
            if (wp == 1) { InvalidateRect(hwnd, nullptr, FALSE); }
            return 0;
        case WM_APP_UPDATE: {
            std::lock_guard<std::mutex> lk(g_updMx);
            if (g_pending.has) {
                g_st.updateVersion = g_pending.version;
                g_st.updateSha = g_pending.sha;
                g_st.updateUrl = g_pending.url;
            }
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case WM_COMMAND: {
            if (LOWORD(wp) == 1) {
                // Кнопка действия
                if (g_st.active && !g_st.updateVersion.empty()) {
                    updater::Info info;
                    info.ok = true;
                    info.version = g_st.updateVersion;
                    info.sha256 = g_st.updateSha;
                    info.url = g_st.updateUrl;
                    std::string err;
                    if (updater::applyUpdate(info, err)) {
                        g_st.updateVersion.clear();
                        g_st.updateSha.clear();
                        g_st.updateUrl.clear();
                        msgBox(hwnd, "Декомпилятор обновлён до версии " + info.version + ".",
                               MB_OK | MB_ICONINFORMATION);
                    } else {
                        msgBox(hwnd, "Не удалось обновить: " + err, MB_OK | MB_ICONWARNING);
                    }
                    InvalidateRect(hwnd, nullptr, FALSE);
                    break;
                }
                if (g_st.active) {
                    if (!updater::prepareJar()) {
                        msgBox(hwnd, "Не удалось подготовить декомпилятор (jar).", MB_OK | MB_ICONERROR);
                        break;
                    }
                    std::string jarPath = updater::jarPath();
                    // запускаем в отдельном процессе: java -jar <jar>
                    std::string cmd = "\"java\" -jar \"" + jarPath + "\"";
                    STARTUPINFOA si{}; si.cb = sizeof(si);
                    PROCESS_INFORMATION pi{};
                    std::vector<char> cmdBuf(cmd.begin(), cmd.end());
                    cmdBuf.push_back('\0');
                    if (!CreateProcessA(nullptr, cmdBuf.data(), nullptr, nullptr, FALSE,
                                        CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
                        msgBox(hwnd, "Не удалось запустить Java (установлен ли JDK/JRE?).", MB_OK | MB_ICONWARNING);
                        break;
                    }
                    CloseHandle(pi.hThread);
                    CloseHandle(pi.hProcess);
                } else {
                    // активация по ключу
                    char buf[128]={0};
                    GetWindowTextA(g_keyEdit, buf, sizeof(buf));
                    g_st.key = buf;
                    if (g_st.onActivate) {
                        bool ok = g_st.onActivate(g_st);
                        g_st.active = ok;
                        if (ok) {
                            std::string lb = "  ЗАПУСТИТЬ ДЕКОМПИЛЯТОР";
                            // уведомим пользователя сообщением
                            msgBox(hwnd, "Активация успешна! Подписка до " + g_st.expiry, MB_OK | MB_ICONINFORMATION);
                        } else {
                            msgBox(hwnd, "Не удалось активировать ключ. Проверьте ключ, сеть и HWID.", MB_OK | MB_ICONWARNING);
                        }
                        InvalidateRect(hwnd, nullptr, FALSE);
                    }
                }
            }
            return 0;
        }
        case WM_DESTROY:
            g_animRun.store(false);
            if (g_bgDc) { DeleteDC(g_bgDc); g_bgDc = nullptr; }
            if (g_bgBmp) { DeleteObject(g_bgBmp); g_bgBmp = nullptr; }
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProc(hwnd, msg, wp, lp);
}

} // namespace

void notifyUpdate(const std::string& version, const std::string& sha256, const std::string& url) {
    {
        std::lock_guard<std::mutex> lk(g_updMx);
        g_pending.has = true;
        g_pending.version = version;
        g_pending.sha = sha256;
        g_pending.url = url;
    }
    for (int i = 0; i < 600 && !g_hwnd; ++i) Sleep(50);
    if (g_hwnd) PostMessageW(g_hwnd, WM_APP_UPDATE, 0, 0);
}

int run(const AppState& st) {
    g_st = st;
    g_readyUi = false;

    Gdiplus::GdiplusStartupInput gpsi;
    Gdiplus::GdiplusStartup(&g_gpToken, &gpsi, nullptr);
    loadLogo();

    HINSTANCE hi = GetModuleHandle(nullptr);
    WNDCLASSW wc{};
    wc.lpfnWndProc = wndProc;
    wc.hInstance = hi;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hIcon = LoadIcon(nullptr, IDI_APPLICATION);
    wc.lpszClassName = L"JDecompLoaderGUI";
    wc.hbrBackground = CreateSolidBrush(COL_BG_TOP);
    RegisterClassW(&wc);

    // Создаём центр экрана
    int sw = GetSystemMetrics(SM_CXSCREEN), sh = GetSystemMetrics(SM_CYSCREEN);
    int x = (sw - W)/2, y = (sh - H)/2;
    DWORD style = WS_POPUP | WS_VISIBLE; // без рамки — окно-«сюр», своя рамка
    RECT wr{0,0,W,H};
    AdjustWindowRectEx(&wr, style, FALSE, 0);
    g_hwnd = CreateWindowExW(
        WS_EX_TOOLWINDOW, wc.lpszClassName, L"Loader",
        style, x, y, wr.right-wr.left, wr.bottom-wr.top, nullptr, nullptr, hi, nullptr);

    if (!g_hwnd) return -1;

    {
        HDC wdc = GetDC(g_hwnd);
        g_bgDc = CreateCompatibleDC(wdc);
        g_bgBmp = CreateCompatibleBitmap(wdc, W, H);
        SelectObject(g_bgDc, g_bgBmp);
        ReleaseDC(g_hwnd, wdc);
        drawStatic(g_bgDc);
    }

    // Шрифты
    fTitle = CreateFontW(30, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Segoe UI");
    fBody  = CreateFontW(20, 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Segoe UI");
    fSmall = CreateFontW(15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Segoe UI");
    fInput = CreateFontW(17, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Consolas");
    fBtn   = CreateFontW(17, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Segoe UI");

    // Позиции контролов
    g_card = {28, 190, W-28, 190+150};
    g_actionBtn.rc = {28, H-96, W-28, H-50};
    g_closeBtn.rc = {W-48, 12, W-16, 44};
    RECT inputR{28, 190+150+30, W-28, 190+150+30+46};

    // Поле ввода — простая нативная edit с прозрачным фоном не выйдет на GDI;
    // используем полупрозрачный Windows edit поверх вручную отрисованной области.
    g_keyEdit = CreateWindowExW(0, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_AUTOHSCROLL,
        inputR.left+8, inputR.top+9, inputR.right-inputR.left-16, 28,
        g_hwnd, nullptr, hi, nullptr);
    HFONT of = (HFONT)SendMessageW(g_keyEdit, WM_SETFONT, (WPARAM)fInput, TRUE);
    // placeholder через cue banner (редакт-контрол): EM_SETCUEBANNER = WM_USER+1
    SendMessageW(g_keyEdit, 0x1501, TRUE, (LPARAM)L"XXXX-XXXX-XXXX-XXXX");
    (void)of; (void)g_btnAction;

    SetFocus(g_keyEdit);

    // Запуск анимации
    g_animRun.store(true);
    std::thread t(animLoop);
    t.detach();

    ShowWindow(g_hwnd, SW_SHOW);
    UpdateWindow(g_hwnd);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    delete g_logo;
    g_logo = nullptr;
    Gdiplus::GdiplusShutdown(g_gpToken);
    return (int)msg.wParam;
}

} // namespace gui