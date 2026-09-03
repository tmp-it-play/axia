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
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tempfile
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from prepare_native import fail, sha256, validate_version
from release import SPARKLE, checked, release_context, required

MAX_FEED_BYTES = 1024 * 1024


def validate_remote_root(value: str | None) -> PurePosixPath:
    if value is None or not value.strip():
        fail("Set --remote-root or AXIA_FEED_REMOTE_ROOT to the absolute updates directory.")
    if (
        not value.startswith("/") or value == "/"
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
        or any(part in ("", ".", "..") for part in value.split("/")[1:])
    ):
        fail("Update root must be a canonical absolute POSIX directory, without traversal or control characters.")
    return PurePosixPath(value)


def directory_guard(directory: PurePosixPath) -> str:
    # Reject symlink ancestors too; publication directories must be controlled by the publisher.
    checks = []
    for ancestor in reversed((directory, *directory.parents)):
        path = shlex.quote(str(ancestor))
        checks.extend((f"test -d {path}", f"test ! -L {path}"))
    checks.append(f"test -w {shlex.quote(str(directory))}")
    return " && ".join(checks)


def regular_file_guard(path: PurePosixPath, *, allow_absent: bool = False) -> str:
    quoted = shlex.quote(str(path))
    kind = f"(test ! -e {quoted} || test -f {quoted})" if allow_absent else f"test -f {quoted}"
    return f"test ! -L {quoted} && {kind}"


def remote_hash_command(path: PurePosixPath) -> str:
    # Hash stdin: filename escaping differs between GNU sha256sum and macOS shasum.
    quoted = shlex.quote(str(path))
    return (
        regular_file_guard(path) + " && { "
        f"if command -v sha256sum >/dev/null 2>&1; then sha256sum < {quoted}; "
        f"elif command -v shasum >/dev/null 2>&1; then shasum -a 256 < {quoted}; "
        "else exit 1; fi; }"
    )


def digest_from_output(output: str) -> str:
    fields = output.split()
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]) or fields[1] != "-":
        fail("Remote SHA-256 verification returned invalid output.")
    return fields[0]


def upload_file(remote_command: list[str], source: Path, target: PurePosixPath) -> None:
    # Binary stdin avoids SCP's different legacy-shell and SFTP path-quoting rules.
    command = (
        directory_guard(target.parent) + " && " + regular_file_guard(target, allow_absent=True)
        + f" && umask 077 && set -C && cat > {shlex.quote(str(target))}"
    )
    with source.open("rb") as contents:
        result = subprocess.run([*remote_command, command], stdin=contents, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        fail("Release upload failed; existing feeds remain unchanged.")


def validate_artifact(directory: Path, target: str, version: str, tag: str, commit: str) -> tuple[Path, Path]:
    feed = directory / "appcast.xml"
    if not feed.is_file():
        fail("Release artifact digest validation failed.")
    with feed.open("rb") as contents:
        feed_bytes = contents.read(MAX_FEED_BYTES + 1)
    if len(feed_bytes) > MAX_FEED_BYTES:
        fail("Release feed exceeds the 1 MiB limit.")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if any(manifest.get(name) != value for name, value in (("platform", target), ("version", version), ("tag", tag), ("commit", commit))):
        fail("Artifact provenance does not match the selected release.")
    if target == "macos-arm64" and manifest.get("notarized") is not True:
        fail("macOS artifact was not approved by the notarization pipeline.")
    extension = "dmg" if target == "macos-arm64" else "msi"
    asset = directory / f"Axia-{version}.{extension}"
    files = manifest.get("files", {})
    for path in (asset, feed):
        if not path.is_file() or files.get(path.name) != (hashlib.sha256(feed_bytes).hexdigest() if path == feed else sha256(path)):
            fail("Release artifact digest validation failed.")
    root = ET.fromstring(feed_bytes)
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
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--remote-root", default=os.environ.get("AXIA_FEED_REMOTE_ROOT"))
    parser.add_argument("--check-remote-root", action="store_true", help="Validate only the configured remote root syntax; no network access")
    args = parser.parse_args()
    remote_root = validate_remote_root(args.remote_root)
    if args.check_remote_root:
        print("Update root syntax passed; remote directory permissions and serving are not verified.")
        return
    if args.artifacts is None:
        parser.error("--artifacts is required for publication")
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
                data = response.read(MAX_FEED_BYTES + 1)
                if len(data) > MAX_FEED_BYTES:
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
        remote_command = ["ssh", *options, destination]
        if existing_release:
            downloaded = private / "existing-release"
            downloaded.mkdir()
            checked(["gh", "release", "download", tag, "--repo", "it-play/axia", "--dir", str(downloaded)])
            for asset, _ in artifacts.values():
                if sha256(downloaded / asset.name) != sha256(asset):
                    fail("Refusing to replace a previously published GitHub release asset.")
        staged = {}
        for target, (asset, feed) in artifacts.items():
            directory = remote_root / target.replace("-", "/")
            checked([*remote_command, directory_guard(directory)])
            remote = directory / (".appcast-" + uuid.uuid4().hex + ".xml")
            remote_asset = directory / (".asset-" + uuid.uuid4().hex)
            for source, temporary in ((asset, remote_asset), (feed, remote)):
                upload_file(remote_command, source, temporary)
                command = directory_guard(directory) + " && " + remote_hash_command(temporary)
                verified = digest_from_output(checked([*remote_command, command], capture=True))
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
            directory = remote_root / target.replace("-", "/")
            asset = artifacts[target][0]
            version_directory = directory / version
            final_asset = version_directory / asset.name
            quoted_version = shlex.quote(str(version_directory))
            quoted_asset, quoted_staged = shlex.quote(str(final_asset)), shlex.quote(str(remote_asset))
            # Atomic hard link fails rather than overwriting an immutable published version.
            command = (
                directory_guard(directory) + " && " + regular_file_guard(remote_asset) + " && "
                f"test ! -L {quoted_version} && (test -d {quoted_version} || mkdir {quoted_version}) && "
                + directory_guard(version_directory) + " && " + regular_file_guard(final_asset, allow_absent=True) + " && "
                f"chmod 755 {quoted_version} && chmod 644 {quoted_staged} && "
                f"(ln {quoted_staged} {quoted_asset} || test -f {quoted_asset}) && " + remote_hash_command(final_asset)
            )
            if digest_from_output(checked([*remote_command, command], capture=True)) != sha256(asset):
                fail("An immutable mirrored asset already exists with different content; no feed was changed.")
            command = directory_guard(directory) + " && " + regular_file_guard(remote_asset) + " && unlink " + quoted_staged
            checked([*remote_command, command])
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
            directory = remote_root / target.replace("-", "/")
            final_feed = directory / "appcast.xml"
            command = (
                directory_guard(directory) + " && " + regular_file_guard(remote) + " && "
                + regular_file_guard(final_feed, allow_absent=True) + " && "
                f"chmod 644 {shlex.quote(str(remote))} && mv -f {shlex.quote(str(remote))} {shlex.quote(str(final_feed))}"
            )
            checked([*remote_command, command])
        for target, (_, feed) in artifacts.items():
            url = "https://kimtaeeun.site/axia/updates/" + target.replace("-", "/") + "/appcast.xml"
            with urllib.request.urlopen(url, timeout=30) as response:
                if response.read() != feed.read_bytes():
                    fail("Published feed verification failed; inspect the release host before retrying.")
    print("GitHub release assets, immutable public asset mirrors, and platform feeds are published and verified.")


if __name__ == "__main__":
    main()
