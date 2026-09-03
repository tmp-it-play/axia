#!/usr/bin/env python3
"""Fetch hash-pinned official updater SDKs and build the architecture-specific bridge.

Only generated build directories receive binaries. No private signing key is read here.
Sources: https://github.com/sparkle-project/Sparkle/releases/tag/2.9.6
         https://github.com/vslavik/winsparkle/releases/tag/v0.9.4
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "app/desktopApp/build/updaters"
RESOURCES = ROOT / "app/desktopApp/build/generated/updater-resources"
SPECS = {
    "macos-arm64": (
        "Sparkle-2.9.6.tar.xz",
        "https://github.com/sparkle-project/Sparkle/releases/download/2.9.6/Sparkle-2.9.6.tar.xz",
        "52bf9e88cdd972fc0c81501377a880e90d47031bd8ca5462488f843e2609e192",
    ),
    "windows-x64": (
        "WinSparkle-0.9.4.zip",
        "https://github.com/vslavik/winsparkle/releases/download/v0.9.4/WinSparkle-0.9.4.zip",
        "6037df37fc263bd1650a1c4949681a9d40ffe991d01f35892a406cb5d103c976",
    ),
}


def fail(message: str) -> None:
    raise SystemExit(message)


def run(arguments: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(arguments, check=True, **kwargs)


def detected_platform() -> str:
    machine = platform.machine().lower()
    if platform.system() == "Darwin" and machine in ("arm64", "aarch64"):
        return "macos-arm64"
    if platform.system() == "Windows" and machine in ("amd64", "x86_64"):
        return "windows-x64"
    fail("Only native macOS arm64 and Windows x64 release builds are supported.")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_version(value: str) -> str:
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        fail("AXIA_RELEASE_VERSION must be a canonical MAJOR.MINOR.PATCH release version.")
    major, minor, patch = map(int, value.split("."))
    if major > 255 or minor > 255 or patch > 65535 or (major, minor, patch) == (0, 0, 0):
        fail("Release version is outside the MSI-compatible version range.")
    return value


def public_key(value: str) -> str:
    try:
        decoded = base64.b64decode(value, validate=True)
    except ValueError:
        fail("A canonical base64 Ed25519 public key is required.")
    if len(decoded) != 32 or base64.b64encode(decoded).decode() != value:
        fail("A canonical 32-byte Ed25519 public key is required.")
    return value


def fetch_sdk(target: str) -> Path:
    filename, url, digest = SPECS[target]
    cache = BUILD / "downloads"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / filename
    if not archive.exists() or sha256(archive) != digest:
        request = urllib.request.Request(url, headers={"User-Agent": "Axia-release-builder"})
        partial = None
        try:
            with urllib.request.urlopen(request, timeout=60) as response, tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
                partial = Path(output.name)
                shutil.copyfileobj(response, output)
        except BaseException:
            if partial is not None:
                partial.unlink(missing_ok=True)
            raise
        if sha256(partial) != digest:
            partial.unlink(missing_ok=True)
            fail("Official updater SDK digest verification failed.")
        partial.replace(archive)
    if sha256(archive) != digest:
        fail("Official updater SDK digest verification failed.")
    sdk = BUILD / "sdk" / target
    sdk.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=target + "-", dir=sdk.parent))
    try:
        if filename.endswith(".zip"):
            with zipfile.ZipFile(archive) as source:
                for member in source.infolist():
                    destination = (staging / member.filename).resolve()
                    if not destination.is_relative_to(staging.resolve()) or (member.external_attr >> 16) & 0o170000 == 0o120000:
                        fail("Unsafe updater SDK archive entry.")
                source.extractall(staging)
        else:
            with tarfile.open(archive) as source:
                source.extractall(staging, filter="data")
        if sdk.exists():
            shutil.rmtree(sdk)
        staging.replace(sdk)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return sdk


def unique_path(paths, description: str) -> Path:
    values = list(paths)
    if len(values) != 1:
        fail(f"Expected one {description} in the verified SDK.")
    return values[0]


def build_bridge(target: str, sdk: Path, configured: bool) -> Path:
    if detected_platform() != target:
        fail("Cross-compiling updater bridges is not supported.")
    destination = RESOURCES / target / "updates"
    destination.mkdir(parents=True, exist_ok=True)
    if target == "macos-arm64":
        framework = sdk / "Sparkle.framework"
        if not (framework / "Headers/Sparkle.h").is_file():
            fail("The verified SDK is missing its root Sparkle framework.")
        run([
            "xcrun", "clang", "-dynamiclib", "-fobjc-arc", "-arch", "arm64", "-mmacosx-version-min=11.0",
            "-framework", "Cocoa", "-framework", "Security", "-F", str(framework.parent), "-framework", "Sparkle",
            "-Wl,-rpath,@loader_path/../../../Frameworks", "-install_name", "@rpath/libaxia_sparkle.dylib",
            str(ROOT / "app/desktopApp/src/native/macos/AxiaSparkleBridge.m"), "-o", str(destination / "libaxia_sparkle.dylib"),
        ])
        architecture = run(["lipo", "-archs", str(destination / "libaxia_sparkle.dylib")], capture_output=True, text=True).stdout.strip()
        if architecture != "arm64":
            fail("Sparkle bridge must contain only arm64 machine code.")
        shutil.copy2(sdk / "LICENSE", destination / "Sparkle-LICENSE.txt")
    else:
        library = unique_path((path for path in sdk.glob("**/WinSparkle.dll") if "x64" in path.parts), "x64 WinSparkle DLL")
        shutil.copy2(library, destination / "WinSparkle.dll")
        shutil.copy2(library.parents[2] / "COPYING", destination / "WinSparkle-LICENSE.txt")
        run([
            "cl", "/nologo", "/std:c++17", "/EHsc", "/MT", "/utf-8", "/DUNICODE", "/D_UNICODE",
            str(ROOT / "app/desktopApp/src/native/windows/AxiaUpdateRunner.cpp"),
            "/Fe:" + str(destination / "AxiaUpdateRunner.exe"), "/Fo:" + str(BUILD / "AxiaUpdateRunner.obj"),
            "/link", "/MACHINE:X64", "/SUBSYSTEM:WINDOWS", "shell32.lib", "user32.lib", "bcrypt.lib", "ole32.lib",
        ])
        for binary in (destination / "WinSparkle.dll", destination / "AxiaUpdateRunner.exe"):
            with binary.open("rb") as source:
                if source.read(2) != b"MZ":
                    fail("Invalid Windows native binary.")
                source.seek(0x3C)
                offset = int.from_bytes(source.read(4), "little")
                source.seek(offset)
                if source.read(6) != b"PE\0\0\x64\x86":
                    fail("Windows native binaries must target x64.")
    if configured:
        version = validate_version(os.environ.get("AXIA_RELEASE_VERSION", ""))
        env_key = "AXIA_MACOS_ED25519_PUBLIC_KEY" if target == "macos-arm64" else "AXIA_WINDOWS_ED25519_PUBLIC_KEY"
        key = public_key(os.environ.get(env_key, ""))
        family, architecture = target.split("-")
        values = {
            "platform": target,
            "version": version,
            "publicKey": key,
            "feedUrl": f"https://kimtaeeun.site/axia/updates/{family}/{architecture}/appcast.xml",
        }
        if target == "macos-arm64":
            team_id = os.environ.get("AXIA_APPLE_TEAM_ID", "")
            if not re.fullmatch(r"[A-Z0-9]{10}", team_id):
                fail("AXIA_APPLE_TEAM_ID is required for a signed macOS release.")
            values["appleTeamId"] = team_id
        (destination / "updater.properties").write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())), encoding="utf-8")
    else:
        (destination / "updater.properties").unlink(missing_ok=True)
    return destination


def main() -> None:
    if sys.version_info < (3, 12):
        fail("Python 3.12 or newer is required for safe archive extraction.")
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=SPECS)
    parser.add_argument("--fetch-only", action="store_true")
    parser.add_argument("--release", action="store_true")
    arguments = parser.parse_args()
    target = arguments.platform or detected_platform()
    sdk = fetch_sdk(target)
    if not arguments.fetch_only:
        build_bridge(target, sdk, arguments.release)
    print(f"Verified updater SDK prepared for {target}.")


if __name__ == "__main__":
    main()
