#!/usr/bin/env python3
"""Credential-gated, native release packaging. Never publishes or silently signs a dev build."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import email.utils
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from prepare_native import BUILD, ROOT, build_bridge, detected_platform, fail, fetch_sdk, public_key, sha256, unique_path, validate_version

SPARKLE = "http://www.andymatuschak.org/xml-namespaces/sparkle"
ALL_CREDENTIALS = (
    "AXIA_MACOS_CERTIFICATE_P12_BASE64", "AXIA_MACOS_CERTIFICATE_PASSWORD", "AXIA_MACOS_SIGNING_IDENTITY",
    "AXIA_APPLE_TEAM_ID", "AXIA_NOTARY_KEY_P8", "AXIA_NOTARY_KEY_ID", "AXIA_NOTARY_ISSUER_ID",
    "AXIA_MACOS_ED25519_PRIVATE_KEY", "AXIA_MACOS_ED25519_PUBLIC_KEY",
    "AXIA_WINDOWS_ED25519_PRIVATE_KEY", "AXIA_WINDOWS_ED25519_PUBLIC_KEY",
    "AXIA_FEED_SSH_HOST", "AXIA_FEED_SSH_USER", "AXIA_FEED_SSH_PRIVATE_KEY", "AXIA_FEED_SSH_KNOWN_HOSTS",
)


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value.strip():
        fail(f"Required release credential/configuration is absent: {name}")
    return value


def checked(arguments: list[str], *, input: str | None = None, capture: bool = False) -> str:
    # Never render CalledProcessError: signing programs may include sensitive stdin in diagnostics.
    result = subprocess.run(arguments, cwd=ROOT, input=input, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        fail(f"Release command failed: {Path(arguments[0]).name}. No artifact is approved for publishing.")
    if not capture and result.stdout and Path(arguments[0]).name in ("gradlew", "gradlew.bat"):
        print("Gradle packaging completed.")
    return result.stdout


def release_context() -> tuple[str, str, str]:
    version = validate_version(required("AXIA_RELEASE_VERSION"))
    tag = required("AXIA_RELEASE_TAG")
    if tag != "v" + version or required("AXIA_CONFIRM_RELEASE") != "RELEASE":
        fail("Explicit RELEASE confirmation and matching vMAJOR.MINOR.PATCH tag are required.")
    if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or os.environ.get("GITHUB_REPOSITORY") != "it-play/axia":
        fail("Release side effects are permitted only by the repository's manual release workflow.")
    head = checked(["git", "rev-parse", "HEAD"], capture=True).strip()
    tagged = checked(["git", "rev-parse", "refs/tags/" + tag + "^{commit}"], capture=True).strip()
    if head != tagged:
        fail("Checkout must exactly match the selected existing release tag.")
    return version, tag, head


def preflight() -> None:
    release_context()
    for name in ALL_CREDENTIALS:
        required(name)
    public_key(required("AXIA_MACOS_ED25519_PUBLIC_KEY"))
    public_key(required("AXIA_WINDOWS_ED25519_PUBLIC_KEY"))
    if not re.fullmatch(r"[A-Z0-9]{10}", required("AXIA_APPLE_TEAM_ID")):
        fail("Invalid Apple team ID.")
    if not required("AXIA_MACOS_SIGNING_IDENTITY").startswith("Developer ID Application:"):
        fail("A Developer ID Application signing identity is required.")
    for name in ("AXIA_MACOS_ED25519_PRIVATE_KEY", "AXIA_WINDOWS_ED25519_PRIVATE_KEY"):
        try:
            if len(base64.b64decode(required(name).strip(), validate=True)) not in (32, 96):
                fail("Unsupported Ed25519 private-key format.")
        except ValueError:
            fail("Invalid Ed25519 private-key encoding.")
    print("Release preflight passed; all required credential names are configured.")


def gradle(task: str) -> None:
    os.environ["AXIA_RELEASE_BUILD"] = "true"
    executable = "gradlew.bat" if sys.platform == "win32" else "gradlew"
    checked([str(ROOT / executable), ":app:desktopApp:" + task, "--no-daemon", "--no-configuration-cache", "--console=plain"])


def notarize(path: Path, key: Path) -> None:
    response = checked([
        "xcrun", "notarytool", "submit", str(path), "--key", str(key), "--key-id", required("AXIA_NOTARY_KEY_ID"),
        "--issuer", required("AXIA_NOTARY_ISSUER_ID"), "--wait", "--timeout", "30m", "--output-format", "json",
    ], capture=True)
    if json.loads(response).get("status") != "Accepted":
        fail("Apple notarization did not return Accepted; publication is forbidden.")


def mac_release(sdk: Path, output: Path, version: str, tag: str) -> None:
    identity = required("AXIA_MACOS_SIGNING_IDENTITY")
    team = required("AXIA_APPLE_TEAM_ID")
    if not identity.startswith("Developer ID Application:") or not identity.endswith("(" + team + ")"):
        fail("Developer ID signing identity must match the configured Apple team.")
    gradle("createDistributable")
    app = unique_path((ROOT / "app/desktopApp/build/compose/binaries/main/app").glob("Axia.app"), "packaged Axia.app")
    frameworks = app / "Contents/Frameworks"
    frameworks.mkdir(exist_ok=True)
    shutil.copytree(sdk / "Sparkle.framework", frameworks / "Sparkle.framework", symlinks=True)
    with (app / "Contents/Info.plist").open("rb") as source:
        info = plistlib.load(source)
    if info.get("CFBundleIdentifier") != "team.themoment.axia" or info.get("CFBundleVersion") != version:
        fail("Packaged bundle identity/version does not match the release.")
    if info.get("SUPublicEDKey") != public_key(required("AXIA_MACOS_ED25519_PUBLIC_KEY")) or not info.get("SURequireSignedFeed") or not info.get("SUVerifyUpdateBeforeExtraction") or info.get("SUSignedFeedFailureExpirationInterval") != 0:
        fail("Packaged bundle does not require strict signed-feed verification.")
    with tempfile.TemporaryDirectory(prefix="axia-signing-") as work:
        private = Path(work)
        notary_key = private / "AuthKey.p8"
        notary_key.write_text(required("AXIA_NOTARY_KEY_P8"), encoding="utf-8")
        notary_key.chmod(0o600)
        entitlements = private / "jvm.plist"
        with entitlements.open("wb") as destination:
            plistlib.dump({
                "com.apple.security.cs.allow-jit": True,
                "com.apple.security.cs.allow-unsigned-executable-memory": True,
                "com.apple.security.cs.disable-library-validation": True,
            }, destination)
        # JIT/library entitlements support the JVM/Skiko, not updater signature bypasses.
        native_files = []
        for path in app.rglob("*"):
            if path.is_symlink() or not path.is_file():
                continue
            kind = checked(["file", "-b", str(path)], capture=True)
            if "Mach-O" not in kind:
                continue
            architectures = checked(["lipo", "-archs", str(path)], capture=True).split()
            if "arm64" not in architectures:
                fail("A packaged native binary is missing arm64 support.")
            if architectures != ["arm64"]:
                thin = private / "thin-binary"
                checked(["lipo", str(path), "-thin", "arm64", "-output", str(thin)])
                shutil.copymode(path, thin)
                thin.replace(path)
            native_files.append(path)
        if not native_files:
            fail("The bundle contains no native application executable.")
        for path in sorted(native_files, key=lambda value: len(value.parts), reverse=True):
            args = ["codesign", "--force", "--timestamp", "--options", "runtime", "--sign", identity]
            if path.name in ("Axia", "java", "libjli.dylib"):
                args += ["--entitlements", str(entitlements)]
            checked(args + [str(path)])
        bundles = [path for path in app.rglob("*") if path.is_dir() and not path.is_symlink() and path.suffix in (".app", ".xpc", ".framework")]
        # The runtime has its own Info.plist but no .app suffix.
        bundles.append(app / "Contents/runtime")
        for bundle in sorted(bundles, key=lambda value: len(value.parts), reverse=True):
            checked(["codesign", "--force", "--timestamp", "--options", "runtime", "--sign", identity, str(bundle)])
        checked(["codesign", "--force", "--timestamp", "--options", "runtime", "--entitlements", str(entitlements), "--sign", identity, str(app)])
        checked(["codesign", "--verify", "--deep", "--strict", "-R", f'anchor apple generic and certificate leaf[subject.OU] = "{team}"', str(app)])
        archive = private / "Axia.zip"
        checked(["ditto", "-c", "-k", "--keepParent", str(app), str(archive)])
        notarize(archive, notary_key)
        checked(["xcrun", "stapler", "staple", str(app)])
        checked(["xcrun", "stapler", "validate", str(app)])
        checked(["spctl", "--assess", "--type", "execute", str(app)])
        disk_root = private / "disk"
        disk_root.mkdir()
        shutil.copytree(app, disk_root / "Axia.app", symlinks=True)
        (disk_root / "Applications").symlink_to("/Applications")
        dmg = output / f"Axia-{version}.dmg"
        checked(["hdiutil", "create", "-volname", "Axia", "-srcfolder", str(disk_root), "-format", "UDZO", str(dmg)])
        checked(["codesign", "--timestamp", "--sign", identity, str(dmg)])
        notarize(dmg, notary_key)
        checked(["xcrun", "stapler", "staple", str(dmg)])
        checked(["xcrun", "stapler", "validate", str(dmg)])
        signing_key = required("AXIA_MACOS_ED25519_PRIVATE_KEY").strip() + "\n"
        checked([
            str(sdk / "bin/generate_appcast"), "--ed-key-file", "-", "--maximum-deltas", "0", "--maximum-versions", "1",
            "--download-url-prefix", f"https://kimtaeeun.site/axia/updates/macos/arm64/{version}/", str(output),
        ], input=signing_key)
        feed = output / "appcast.xml"
        if not feed.is_file() or b"sparkle:edSignature" not in feed.read_bytes():
            fail("Sparkle did not generate the signed appcast enclosure.")
        checked([str(sdk / "bin/sign_update"), "--ed-key-file", "-", "--verify", str(feed)], input=signing_key)
        # generate_appcast compares the app's public key with the supplied private key and signs the XML feed.


def windows_release(sdk: Path, output: Path, version: str, tag: str) -> None:
    gradle("packageMsi")
    source = unique_path((ROOT / "app/desktopApp/build/compose/binaries/main/msi").glob("*.msi"), "release MSI")
    installer = output / f"Axia-{version}.msi"
    shutil.copy2(source, installer)
    checked(["pwsh", "-NoProfile", "-NonInteractive", "-File", str(ROOT / "scripts/release/inspect_msi.ps1"), "-MsiPath", str(installer), "-Version", version])
    tool = unique_path(sdk.glob("**/winsparkle-tool.exe"), "WinSparkle signing tool")
    with tempfile.TemporaryDirectory(prefix="axia-signing-") as work:
        key = Path(work) / "ed25519.key"
        key.write_text(required("AXIA_WINDOWS_ED25519_PRIVATE_KEY").strip(), encoding="ascii")
        key.chmod(0o600)
        signature = checked([str(tool), "sign", "-f", str(key), str(installer)], capture=True).strip()
        if len(base64.b64decode(signature, validate=True)) != 64:
            fail("WinSparkle returned an invalid Ed25519 signature.")
        checked([str(tool), "verify", "-p", public_key(required("AXIA_WINDOWS_ED25519_PUBLIC_KEY")), "-s", signature, str(installer)])
    ET.register_namespace("sparkle", SPARKLE)
    rss = ET.Element("rss", version="2.0")
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = "Axia for Windows"
    item = ET.SubElement(channel, "item")
    ET.SubElement(item, "title").text = "Axia " + version
    ET.SubElement(item, "pubDate").text = email.utils.format_datetime(datetime.now(timezone.utc))
    ET.SubElement(item, "enclosure", {
        "url": f"https://kimtaeeun.site/axia/updates/windows/x64/{version}/{installer.name}",
        "length": str(installer.stat().st_size), "type": "application/octet-stream",
        f"{{{SPARKLE}}}version": version, f"{{{SPARKLE}}}shortVersionString": version,
        f"{{{SPARKLE}}}os": "windows", f"{{{SPARKLE}}}edSignature": signature,
    })
    ET.indent(rss)
    ET.ElementTree(rss).write(output / "appcast.xml", encoding="utf-8", xml_declaration=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preflight", action="store_true")
    args = parser.parse_args()
    if args.preflight:
        preflight()
        return
    version, tag, commit = release_context()
    target = detected_platform()
    required("AXIA_MACOS_ED25519_PRIVATE_KEY" if target == "macos-arm64" else "AXIA_WINDOWS_ED25519_PRIVATE_KEY")
    output = BUILD / "release" / target
    if output.exists() and any(output.iterdir()):
        fail("Release output already exists. Use a clean build checkout to prevent accidental replacement.")
    output.mkdir(parents=True, exist_ok=True)
    sdk = fetch_sdk(target)
    build_bridge(target, sdk, True)
    if target == "macos-arm64":
        mac_release(sdk, output, version, tag)
    else:
        windows_release(sdk, output, version, tag)
    manifest = {
        "version": version, "tag": tag, "commit": commit, "platform": target,
        "notarized": target == "macos-arm64",
        "files": {path.name: sha256(path) for path in sorted(output.iterdir()) if path.is_file()},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {target} release artifacts prepared. Nothing has been published.")


if __name__ == "__main__":
    main()
