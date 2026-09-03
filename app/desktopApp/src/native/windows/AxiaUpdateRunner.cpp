#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <shellapi.h>
#include <objbase.h>
#include <bcrypt.h>
#include <array>
#include <cwctype>
#include <filesystem>
#include <string>
#include <vector>

#if !defined(_M_X64)
#error Axia supports only x64 Windows updater runners.
#endif

struct Handle {
    HANDLE value = nullptr;
    ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
};

struct ComApartment {
    HRESULT result = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    ~ComApartment() { if (SUCCEEDED(result)) CoUninitialize(); }
};

static void Failure() {
    MessageBoxW(nullptr, L"업데이트를 설치하지 못했습니다. Axia 설정에서 다시 확인해 주세요.", L"Axia 업데이트", MB_OK | MB_ICONERROR);
}

static bool HashMatches(HANDLE file, const std::wstring& expected) {
    if (expected.size() != 64) return false;
    for (wchar_t value : expected) if (!((value >= L'0' && value <= L'9') || (value >= L'a' && value <= L'f'))) return false;
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    bool valid = false;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) return false;
    if (BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0) >= 0) {
        std::array<UCHAR, 65536> buffer;
        DWORD read = 0;
        bool success = true;
        while (true) {
            if (!ReadFile(file, buffer.data(), static_cast<DWORD>(buffer.size()), &read, nullptr)) { success = false; break; }
            if (read == 0) break;
            if (BCryptHashData(hash, buffer.data(), read, 0) < 0) { success = false; break; }
        }
        std::array<UCHAR, 32> result;
        if (success && BCryptFinishHash(hash, result.data(), static_cast<ULONG>(result.size()), 0) >= 0) {
            static const wchar_t* hex = L"0123456789abcdef";
            std::wstring actual;
            for (UCHAR value : result) { actual += hex[value >> 4]; actual += hex[value & 15]; }
            valid = actual == expected;
        }
        BCryptDestroyHash(hash);
    }
    BCryptCloseAlgorithmProvider(algorithm, 0);
    return valid;
}

static bool MarkInternetDownload(const std::wstring& installer) {
    // A filesystem copy does not reliably preserve NTFS alternate streams. Restore the internet
    // marker rather than dropping the downloaded MSI's publisher/SmartScreen warning context.
    Handle zone{CreateFileW((installer + L":Zone.Identifier").c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr)};
    if (zone.value == INVALID_HANDLE_VALUE) return false;
    const char marker[] = "[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=https://kimtaeeun.site/axia/updates/\r\n";
    DWORD written = 0;
    return WriteFile(zone.value, marker, sizeof(marker) - 1, &written, nullptr) && written == sizeof(marker) - 1;
}

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    ComApartment apartment;
    if (FAILED(apartment.result)) return 1;
    int count = 0;
    LPWSTR* values = CommandLineToArgvW(GetCommandLineW(), &count);
    if (values == nullptr || count != 5) { if (values) LocalFree(values); return 1; }
    std::wstring installer = values[1], expected = values[2], processId = values[3], launcher = values[4];
    LocalFree(values);
    try {
        std::filesystem::path payload(installer), application(launcher);
        if (!payload.is_absolute() || payload.extension() != L".msi" || !application.is_absolute() || application.filename() != L"Axia.exe") return 1;
        if (processId.empty() || processId.size() > 10) return 1;
        for (wchar_t digit : processId) if (digit < L'0' || digit > L'9') return 1;
        unsigned long long parentId = std::stoull(processId);
        if (parentId == 0 || parentId > MAXDWORD) return 1;
        Handle parent{OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(parentId))};
        if (!parent.value) return 1;
        if (!MarkInternetDownload(installer)) return 1;
        // Deny writes/deletes for the complete hand-off and MSI run; never relax signature checks.
        Handle lockedPayload{CreateFileW(installer.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr)};
        if (lockedPayload.value == INVALID_HANDLE_VALUE || !HashMatches(lockedPayload.value, expected)) return 1;
        DWORD written = 0;
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), "READY\n", 6, &written, nullptr) || written != 6) return 1;
        if (WaitForSingleObject(parent.value, 600000) != WAIT_OBJECT_0) return 1;

        // Normal Windows shell handling intentionally keeps installer UI, publisher warnings and SmartScreen.
        SHELLEXECUTEINFOW execute{};
        execute.cbSize = sizeof(execute);
        execute.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
        execute.lpVerb = L"open";
        execute.lpFile = installer.c_str();
        execute.nShow = SW_SHOWNORMAL;
        bool installed = ShellExecuteExW(&execute) && execute.hProcess;
        if (installed) {
            Handle installerProcess{execute.hProcess};
            installed = WaitForSingleObject(installerProcess.value, INFINITE) == WAIT_OBJECT_0;
            DWORD result = ERROR_INSTALL_FAILURE;
            installed = installed && GetExitCodeProcess(installerProcess.value, &result);
            // Windows is already restarting; do not report failure or launch Axia into shutdown.
            if (installed && result == ERROR_SUCCESS_REBOOT_INITIATED) return 0;
            installed = installed && (result == ERROR_SUCCESS || result == ERROR_SUCCESS_REBOOT_REQUIRED);
        }
        if (!installed) Failure();
        // Relaunch even after a user-cancelled MSI, so the previous application remains usable.
        if (reinterpret_cast<INT_PTR>(ShellExecuteW(nullptr, L"open", launcher.c_str(), nullptr, nullptr, SW_SHOWNORMAL)) <= 32) Failure();
        return installed ? 0 : 1;
    } catch (...) {
        Failure();
        return 1;
    }
}
