# Axia deployment on the existing Mac mini

Use the existing Docker Desktop, Nginx, MySQL, and Redis. Compose runs only the Linux arm64 Axia server; no replacement MySQL/Redis service is created and other applications remain unchanged.

## Database and private configuration

- Use the existing MySQL container and its existing `root` credentials, as requested. Create only database `axia`; do not reset passwords or modify other databases. The application initializes its tables, not schema migrations.
- From Docker Desktop, connect to the existing published MySQL port through `host.docker.internal:3306`, not the Axia container's loopback address.
- Put application settings in the deployment directory's `config/server.env`, mode `0600`, outside Git and the image. Required key names are in `.env.example`: database access, DataGSM registration/Event credentials, GitHub App/installation credentials, and independent application secrets. Do not substitute dummy provider keys.
- Compose reads raw `KEY=value`: do not add surrounding quotes or `source` the file. Store a one-line GitHub PEM with literal `\n`; the application expands it. The issued lowercase hex64 DataGSM Event secret is used literally.
- Enter secrets through a private prompt or protected standard input, never command-line arguments, shell tracing, terminal output, or resolved `docker compose config` / `docker inspect` output. Preflight reports key names and errors, not values.

The macOS override mounts a truststore containing the existing MySQL **public CA certificate only**, read-only, at `/opt/axia/config/mysql-truststore.p12`. Set `AXIA_MYSQL_TRUSTSTORE` to its absolute host path and configure JDBC to use it with certificate verification. Do not copy MySQL private keys or disable verification.

## Stage and start the loaded image

The deployment directory is `/Users/snowykte0426/Downloads/axia`. Prepare it with these files:

| Deployed file | Source / purpose |
| --- | --- |
| `docker-compose.yml` | Root Compose file |
| `compose.host.yml` | `deploy/macos/compose.override.yml` |
| `start.sh`, `preflight.sh` | `deploy/server/` scripts |
| `.env` | Nonsecret Compose image, path, resource, and UID/GID settings |
| `config/server.env` | Private application settings, `0600` |

Nonsecret Compose settings: `AXIA_SERVER_IMAGE`, `AXIA_SERVER_LOG_DIR`, `AXIA_SERVER_MEMORY_LIMIT`, `AXIA_SERVER_UID`, `AXIA_SERVER_GID`, and `AXIA_MYSQL_TRUSTSTORE`. The start script supplies `AXIA_ENV_FILE`.

Create the log directory before launch; bind mounts do not create it. On Docker Desktop, set `AXIA_SERVER_UID` / `AXIA_SERVER_GID` to the non-root host owner of the writable log directory. Generic Linux defaults are `10001:10001`; do not assume they match the Mac account or run the container as root.

Build Linux arm64 outside the shared production host, then transfer/load the verified image. Set `AXIA_SERVER_IMAGE` to its immutable loaded image ID or digest, not a mutable tag. The macOS override removes the build configuration.

Run preflight **before** start:

```sh
bash /Users/snowykte0426/Downloads/axia/preflight.sh /Users/snowykte0426/Downloads/axia/config/server.env
bash /Users/snowykte0426/Downloads/axia/start.sh /Users/snowykte0426/Downloads/axia
curl --fail http://127.0.0.1:18080/health/live
curl --fail http://127.0.0.1:18080/health/ready
```

`start.sh` repeats preflight, validates Compose without printing resolved values, and starts only `server` with `--no-build --pull never`. It checks liveness and database readiness; neither preflight nor readiness proves provider credentials, OAuth, or synchronization work.

Keep host `127.0.0.1:18080` → container `0.0.0.0:8080`: Ktor accepts forwarded headers, so the published port stays private. Health checks run every 30 seconds. `restart: unless-stopped` restarts exited processes, not a still-running unhealthy process.

## Nginx: reverse proxy only

Copy the location blocks from `nginx/axia-locations.conf` into the existing `kimtaeeun.site` server in `/opt/homebrew/etc/nginx/servers/kimtaeeun.conf`, alongside sandrone/nxdi. Change only that site file: do not alter global `nginx.conf`, replace the server block, or change other applications.

| Public path | Upstream |
| --- | --- |
| `/axia/api` | Existing-style trailing-slash redirect to `/axia/api/` |
| `/axia/api/v1/...` | `http://127.0.0.1:18080/v1/...` |
| `/axia/api/auth/datagsm/callback` | `/auth/datagsm/callback` |
| `/axia/api/webhooks/datagsm`, `/axia/api/webhooks/github` | Corresponding `/webhooks/...` routes |

Keep Cloudflare **Flexible** and the existing HTTP origin on port 80. The public origin and registered OAuth callback remain HTTPS. Forward Cloudflare's incoming `X-Forwarded-Proto`, not the origin's `$scheme`. Keep the trailing slash on `proxy_pass` to strip only `/axia/api/`. Run the existing Nginx installation's `nginx -t` before reloading.

No Axia-only IP restriction, maintenance/503 stub, cache rule, Nginx log policy, or static update location is installed. Existing site/Cloudflare policies stay unchanged; the optional Cloudflare cache-rule fragment is not part of this deployment. Flexible leaves the Cloudflare-to-origin hop unencrypted; this deployment does not change that mode.

## Server logs and unfinished prerequisites

The mounted server log directory contains `server.json` and `launcher.log`; use those files, not `docker compose logs`, because Docker logging is disabled. Keep server-only daily/14-day retention with `logrotate/axia-server`, adapting host paths and ownership as needed. Its `copytruncate` has a small concurrent-copy loss window. Do not add a separate Axia Nginx logrotate policy or verbose provider HTTP logging.

Provider keys have not been supplied: successful production login, Event lifecycle, and GitHub synchronization are **not established**. Missing keys stop preflight/start; no fake HTTP service replaces the API. DataGSM's stable-ID/full-snapshot Event contract remains an external prerequisite. GitHub Draft assignment changes reversed entirely between observations cannot be reconstructed from its current-assignee-only API.

Desktop update code expects signed feeds/assets at `https://kimtaeeun.site/axia/updates/...`, but **this Nginx deployment does not serve those paths**. Signing, publication, and an explicitly chosen serving arrangement remain unfinished; do not claim update delivery is deployed.

### Desktop publication directory — not yet served

The release workflow requires the repository-level Actions variable `AXIA_FEED_REMOTE_ROOT`, with `/Users/snowykte0426/Downloads/axia/updates` as the intended explicit destination. `publish.py --remote-root` can override it; no `/srv` fallback exists. The publisher supports Linux `sha256sum` and macOS `shasum -a 256` and requires pre-existing, trusted `macos/arm64` and `windows/x64` directories beneath that root. See the [release publication instructions](../scripts/release/README.md).

This setting selects only where approved release files are published. It does not create a Ktor route, host mount, or Nginx update location, and the current deployment remains unable to serve update files.
