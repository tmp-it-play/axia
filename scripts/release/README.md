# Desktop release publication

`desktop-release.yml` builds both signed platform artifacts, uploads their originals to GitHub Releases, then publishes public mirrors and feeds. It requires the existing manual workflow confirmation, matching release tag, signing credentials, and pinned-host SSH credentials. No production credentials are generated or substituted by these scripts.

## Explicit destination

Set the **repository-level GitHub Actions variable** `AXIA_FEED_REMOTE_ROOT` to the canonical absolute POSIX updates directory on the SSH host. Do not configure it only as an environment-scoped variable: the workflow uses it in its top-level environment before job environment variables are available.

For the current deployment layout, the intended value is:

```text
/Users/snowykte0426/Downloads/axia/updates
```

The publisher has no implicit `/srv` default. `publish.py --remote-root PATH` overrides `AXIA_FEED_REMOTE_ROOT`; both use the same validation. Root `/`, relative paths, empty/dot/parent segments, trailing separators, and control characters are rejected. Spaces, apostrophes, dollar signs, and backslashes are literal path characters, not shell syntax.

The following check validates configuration syntax only, without credentials or network access:

```sh
python scripts/release/publish.py --remote-root /Users/snowykte0426/Downloads/axia/updates --check-remote-root
```

Before an approved publication, the SSH administrator must prepare these directories below that root:

- `macos/arm64`
- `windows/x64`

They and their ancestors must be real directories, not symlinks. The publisher needs write access to the platform directories. Keep the entire path under trusted administration: shell prechecks cannot prevent another local writer from swapping directories concurrently. The script creates only version subdirectories and publication files, not the configured root or platform directories.

## Transfer and integrity

Incoming and existing public feeds share a 1 MiB (1,048,576-byte) limit; oversized incoming feeds are rejected before manifest validation or publication, without rewriting signed bytes.

The host needs POSIX `sh`, `cat`, `chmod`, `mkdir`, `ln`, `mv`, `unlink`, and either Linux `sha256sum` or macOS `shasum -a 256`. A working `sha256sum` is preferred when installed; a failed checksum command is an error, not a reason to fall back to another tool.

Uploads stream binary bytes through authenticated SSH standard input into private, exclusive staging files. Every remote shell path is quoted. Hashing standard input avoids checksum-tool filename escaping differences. Existing destination directories, symlinks, and special files are rejected before linking assets or replacing feeds.

Versioned `Axia-<version>.dmg` / `.msi` assets are immutable: an identical existing asset can be reused, but differing bytes are never overwritten. Both public asset URLs must return the expected byte counts and hashes before either `appcast.xml` is replaced. Feed replacement is atomic **per platform**, not a cross-platform transaction. An interruption after one feed can be retried with the same verified artifacts; the existing identical feed and assets are accepted.

## Serving is a separate requirement

Public URLs remain `https://kimtaeeun.site/axia/updates/{macos/arm64,windows/x64}/...`; changing the filesystem root does not change those URLs. The server's optional update routes and read-only deployment override are described in the [deployment instructions](../../deploy/README.md#optional-runtime-file-serving--implemented-not-enabled-on-the-host). Use the same `AXIA_FEED_REMOTE_ROOT` host directory for publishing and mounting; only the container-side path becomes `/opt/axia/updates`.

The current production deployment does not enable that mount or forward the public update path to the application. Setting the publication variable alone does **not** make feeds/assets public, and publication will stop at public asset verification until serving is connected. This script does not install Nginx locations, cache rules, IP restrictions, maintenance responses, or logging policy.
