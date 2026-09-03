#!/usr/bin/env python3
"""Publish verified dual-platform assets, then atomically promote their public feeds.

Only a manually approved workflow may call this script. SSH host keys are pinned;
no host-key discovery, password arguments, or signature-validation fallback exists.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from prepare_native import fail, sha256, validate_version
from release import SPARKLE, checked, release_context, required


def validate_artifact(directory: Path, target: str, version: str, tag: str, commit: str) -> tuple[Path, Path]:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if any(manifest.get(name) != value for name, value in (("platform", target), ("version", version), ("tag", tag), ("commit", commit))):
        fail("Artifact provenance does not match the selected release.")
    if target == "macos-arm64" and manifest.get("notarized") is not True:
        fail("macOS artifact was not approved by the notarization pipeline.")
    extension = "dmg" if target == "macos-arm64" else "msi"
    asset = directory / f"Axia-{version}.{extension}"
    feed = directory / "appcast.xml"
    files = manifest.get("files", {})
    for path in (asset, feed):
        if not path.is_file() or files.get(path.name) != sha256(path):
            fail("Release artifact digest validation failed.")
    root = ET.fromstring(feed.read_bytes())
    enclosures = root.findall("./channel/item/enclosure")
    if len(enclosures) != 1:
        fail("A release feed must contain exactly its verified platform artifact.")
    enclosure = enclosures[0]
    expected_url = f"https://kimtaeeun.site/axia/updates/{target.replace('-', '/')}/{version}/{asset.name}"
    if enclosure.get("url") != expected_url or enclosure.get("length") != str(asset.stat().st_size) or not enclosure.get(f"{{{SPARKLE}}}edSignature"):
        fail("Appcast enclosure does not match the approved release artifact.")
    item_version = enclosure.get(f"{{{SPARKLE}}}version") or root.findtext(f"./channel/item/{{{SPARKLE}}}version")
    if item_version != version:
        fail("Appcast version does not match the release.")
    return asset, feed


def reject_rollback(feed: bytes, version: str) -> None:
    root = ET.fromstring(feed)
    versions = [element.text for element in root.findall(f"./channel/item/{{{SPARKLE}}}version")]
    versions += [element.get(f"{{{SPARKLE}}}version") for element in root.findall("./channel/item/enclosure")]
    current = [tuple(map(int, validate_version(value).split("."))) for value in versions if value]
    if not current or max(current) >= tuple(map(int, version.split("."))):
        fail("Refusing an equal-version replacement or update-feed rollback.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    version, tag, commit = release_context()
    required("GH_TOKEN")
    host, user = required("AXIA_FEED_SSH_HOST"), required("AXIA_FEED_SSH_USER")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", host) or not re.fullmatch(r"[a-z_][a-z0-9_-]*", user):
        fail("Invalid feed SSH destination.")
    artifacts = {target: validate_artifact(args.artifacts / target, target, version, tag, commit) for target in ("macos-arm64", "windows-x64")}
    for target, (_, incoming_feed) in artifacts.items():
        url = "https://kimtaeeun.site/axia/updates/" + target.replace("-", "/") + "/appcast.xml"
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(1024 * 1024 + 1)
                if len(data) > 1024 * 1024:
                    fail("Existing feed is unexpectedly large.")
                if data != incoming_feed.read_bytes():
                    reject_rollback(data, version)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                fail("Existing public feed could not be checked safely.")
    # Absence must be an authenticated API 404, not any failed command (network/permissions).
    response = subprocess.run(["gh", "api", "repos/it-play/axia/releases/tags/" + tag], text=True, capture_output=True)
    existing_release = None
    if response.returncode == 0:
        existing_release = json.loads(response.stdout)
        expected_names = {asset.name for asset, _ in artifacts.values()}
        if existing_release.get("draft") or existing_release.get("prerelease") or existing_release.get("tag_name") != tag or {asset["name"] for asset in existing_release.get("assets", [])} != expected_names:
            fail("Existing GitHub release does not match the approved artifact set.")
    elif "HTTP 404" not in response.stderr:
        fail("Cannot establish that the release tag is unpublished.")
    with tempfile.TemporaryDirectory(prefix="axia-feed-publish-") as work:
        private = Path(work)
        key = private / "ssh-key"
        known_hosts = private / "known-hosts"
        key.write_text(required("AXIA_FEED_SSH_PRIVATE_KEY") + "\n", encoding="utf-8")
        known_hosts.write_text(required("AXIA_FEED_SSH_KNOWN_HOSTS") + "\n", encoding="utf-8")
        key.chmod(0o600)
        known_hosts.chmod(0o600)
        options = ["-i", str(key), "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + str(known_hosts)]
        destination = user + "@" + host
        if existing_release:
            downloaded = private / "existing-release"
            downloaded.mkdir()
            checked(["gh", "release", "download", tag, "--repo", "it-play/axia", "--dir", str(downloaded)])
            for asset, _ in artifacts.values():
                if sha256(downloaded / asset.name) != sha256(asset):
                    fail("Refusing to replace a previously published GitHub release asset.")
        staged = {}
        for target, (asset, feed) in artifacts.items():
            directory = "/srv/axia/updates/" + target.replace("-", "/")
            checked(["ssh", *options, destination, f"test -d {directory} && test -w {directory} && test ! -L {directory}"])
            remote = directory + "/.appcast-" + uuid.uuid4().hex + ".xml"
            remote_asset = directory + "/.asset-" + uuid.uuid4().hex
            for source, temporary in ((asset, remote_asset), (feed, remote)):
                checked(["scp", *options, str(source), destination + ":" + temporary])
                verified = checked(["ssh", *options, destination, "sha256sum " + temporary], capture=True).split()[0]
                if verified != sha256(source):
                    fail("Uploaded file digest did not match; existing feed remains unchanged.")
            staged[target] = (remote, remote_asset)
        # GitHub Releases remains the original distribution. Public mirrors require no client credentials.
        if existing_release is None:
            checked([
                "gh", "release", "create", tag, "--repo", "it-play/axia", "--verify-tag", "--title", "Axia " + version,
                "--notes", "macOS arm64: Developer ID signed and notarized. Windows x64: Ed25519-verified MSI; Windows publisher warnings may appear.",
                *[str(asset) for asset, _ in artifacts.values()],
            ])
        for target, (_, remote_asset) in staged.items():
            directory = "/srv/axia/updates/" + target.replace("-", "/")
            asset = artifacts[target][0]
            version_directory = directory + "/" + version
            final_asset = version_directory + "/" + asset.name
            # Atomic hard link fails rather than overwriting an immutable published version.
            command = (
                f"test ! -L {version_directory} && (test -d {version_directory} || mkdir {version_directory}) && "
                f"chmod 755 {version_directory} && chmod 644 {remote_asset} && "
                f"(ln {remote_asset} {final_asset} || test -f {final_asset}) && test ! -L {final_asset} && sha256sum {final_asset}"
            )
            if checked(["ssh", *options, destination, command], capture=True).split()[0] != sha256(asset):
                fail("An immutable mirrored asset already exists with different content; no feed was changed.")
            checked(["ssh", *options, destination, "unlink " + remote_asset])
        for target, (asset, _) in artifacts.items():
            url = f"https://kimtaeeun.site/axia/updates/{target.replace('-', '/')}/{version}/{asset.name}"
            digest = hashlib.sha256()
            received = 0
            with urllib.request.urlopen(url, timeout=60) as response:
                while chunk := response.read(1024 * 1024):
                    received += len(chunk)
                    if received > asset.stat().st_size:
                        fail("Public asset mirror returned unexpected content; no feed was changed.")
                    digest.update(chunk)
            if received != asset.stat().st_size or digest.hexdigest() != sha256(asset):
                fail("Public asset mirror validation failed; no feed was changed.")
        # Both mirrored assets are complete and immutable before any feed is promoted.
        for target, (remote, _) in staged.items():
            directory = "/srv/axia/updates/" + target.replace("-", "/")
            checked(["ssh", *options, destination, f"chmod 644 {remote} && mv -f {remote} {directory}/appcast.xml"])
        for target, (_, feed) in artifacts.items():
            url = "https://kimtaeeun.site/axia/updates/" + target.replace("-", "/") + "/appcast.xml"
            with urllib.request.urlopen(url, timeout=30) as response:
                if response.read() != feed.read_bytes():
                    fail("Published feed verification failed; inspect the release host before retrying.")
    print("GitHub release assets, immutable public asset mirrors, and platform feeds are published and verified.")


if __name__ == "__main__":
    main()
