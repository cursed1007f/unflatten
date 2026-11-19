#pragma once
// updater.h — рабочий jar в %LOCALAPPDATA%\Unflatten и автообновление с сервера.
#include <string>

namespace updater {

// Версия jar, вшитого в exe (обновляется вместе с пересборкой jar).
const char* embeddedVersion();

// Рабочая папка и файлы.
std::string appDir();
std::string jarPath();
std::string versionPath();

// Версия локального jar ("" если файла/версии нет).
std::string localVersion();

// Готовит рабочий jar: распаковывает встроенный, если локального нет
// или встроенный новее. Возвращает false при ошибке записи.
bool prepareJar();

struct Info {
    bool ok = false;
    std::string version;
    std::string sha256;
    std::string url;   // путь, напр. /latest.jar
};

// Запрашивает у сервера сведения о последней версии jar.
Info checkLatest();

// Скачивает jar по info.url, сверяет SHA-256 и ставит на место.
bool applyUpdate(const Info& info, std::string& err);

// Сравнение версий вида "0.1.2": <0, 0, >0.
int compareVersions(const std::string& a, const std::string& b);

// Проверка подписи манифеста (RSA-2048/SHA-256) по строке "version|sha256|size".
bool verifyManifest(const std::string& version, const std::string& sha256,
                    const std::string& size, const std::string& sigB64);

} // namespace updater
