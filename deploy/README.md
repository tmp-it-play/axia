# Axia Linux server deployment

This configuration runs the Java 21 server behind the existing HTTPS Nginx site. It does not provision MySQL, replace the site's TLS configuration, change Cloudflare, or publish an image. Use the existing external **MySQL 8.0.32** instance.

## Runtime and paths

| Public request | Destination |
| --- | --- |
| `/axia/api/v1/...` | `127.0.0.1:18080/v1/...` → container port 8080 |
| `/axia/api/auth/datagsm/callback` | `/auth/datagsm/callback` |
| `/axia/api/webhooks/datagsm`, `/axia/api/webhooks/github` | corresponding `/webhooks/...` route |
| `/axia/updates/macos/arm64/appcast.xml` | `/srv/axia/updates/macos/arm64/appcast.xml` |
| `/axia/updates/windows/x64/appcast.xml` | `/srv/axia/updates/windows/x64/appcast.xml` |
| `/axia/updates/macos/arm64/<version>/Axia-<version>.dmg` | the same path under `/srv` |
| `/axia/updates/windows/x64/<version>/Axia-<version>.msi` | the same path under `/srv` |

The public origin is `https://kimtaeeun.site`. `/aixa` is not an alias. Release automation uploads the original assets to GitHub Releases, publishes an identical-byte immutable asset mirror in the version directory, then atomically replaces the signed feed last. Feed enclosure URLs point to this public mirror, so GitHub repository visibility does not interrupt client downloads. The release publisher needs write access only to `/srv/axia/updates`, not server secrets or logs. Never replace an existing versioned installer with different bytes. Only numeric `x.y.z` versioned DMG/MSI paths receive immutable caching and attachment headers; feeds always revalidate.

## Prepare the existing host

Require Docker Engine, Docker Compose **2.30+** (`env_file.format: raw`), existing Nginx with the HTTP proxy module, and a functioning **daily logrotate job**. The TLS site must already have a valid certificate. Check host architecture and build for that Linux architecture; do not assume the developer desktop architecture is the server's architecture.

Prepare these directories before starting Compose; bind mounts deliberately do not create them:

```sh
sudo install -d -m 0700 /etc/axia
sudo install -d -m 0750 -o 10001 -g 10001 /var/log/axia/server
sudo install -d -m 0755 /srv/axia/updates/macos/arm64 /srv/axia/updates/windows/x64
```

Assign the last two directories to the dedicated release publisher according to the host's existing account policy. Never make the tree world-writable. Confirm UID/GID 10001 is suitable for this service on the host.

Create `/etc/axia/server.env`, mode `0600`, from the variable names in the repository's `.env.example`. Fill actual credentials outside Git and outside the build context. Compose reads this file in **raw** format: do not surround values with quotes; `$`, `#`, and literal `\n` remain part of the value. Put `AXIA_GITHUB_PRIVATE_KEY` on one line using literal `\n` between PEM lines; the application expands them. Never run a command that prints the resolved environment or publish `docker inspect` output.

Required application settings are the external database URL/user/password; DataGSM client ID, immutable The Moment club ID, callback URI, and Event secret; GitHub App ID, installation ID, private key and webhook secret; and three independent application signing/transaction/refresh secrets. DataGSM's 64-character hex Event secret is used literally, not hex-decoded. Application secrets must satisfy the validation in `AxiaConfig`; blanks are rejected.

Use a database hostname reachable **from the container**, not `127.0.0.1` unless the database is in that container (it is not). Prefer a verified TLS JDBC connection, for example `jdbc:mysql://db.internal.example:3306/axia?sslMode=VERIFY_IDENTITY&serverTimezone=UTC`. Supply the database's trusted CA using the deployment's Java trust-store policy where necessary. Do not put user/password parameters in the JDBC URL. The application initializes its own schema and requires the corresponding CREATE/REFERENCES and normal CRUD privileges on the Axia database; do not grant global administration or DROP privileges. No database service, password, or production data is included in Compose.

The development schema is not a migration framework. Do not assume an existing older schema is upgraded merely because the container starts. Bootstrap is currently the explicit one-time logic in `MemberBootstrap`, applied only when provisioning an initially empty database; there are no invented bootstrap environment variables.

## Build and start (operator action)

The Docker build invokes the real `:server:installDist` task. Its distribution is `server/build/install/server`, with `bin/server` launching `ApplicationKt`; there is no separate `application.conf` file. Runtime settings come from `AxiaConfig.fromEnvironment`. The final image contains only the distribution, JRE, probe, launcher, and log configuration—not the build tree or credentials. Base images are pinned by multi-architecture digest; update them deliberately for JRE/OS security patches.

```sh
docker compose config --quiet
docker compose build server
docker compose up --detach server
docker compose ps
curl --fail http://127.0.0.1:18080/health/live
curl --fail http://127.0.0.1:18080/health/ready
```

Only the loopback port is published. Do not bind 18080 publicly: Ktor trusts forwarded headers. Every 30 seconds, the container health check checks both liveness and database readiness, with a 60-second startup allowance. Docker marks failed checks unhealthy; `restart: unless-stopped` restarts exited processes, **not unhealthy but still-running processes**. Monitor the health status and investigate failures rather than repeatedly restarting an unavailable database.

Optional Compose overrides are `AXIA_ENV_FILE`, `AXIA_SERVER_IMAGE`, `AXIA_SERVER_LOG_DIR`, and `AXIA_SERVER_MEMORY_LIMIT`. If you change the log directory, update the logrotate path too. Keep the application server on container port 8080 and the host mapping on 18080 unless the Nginx upstream is changed consistently. GitHub synchronization/reminder intervals remain the application environment settings from `.env.example`.

## Add Nginx routes without replacing the site

Install `nginx/axia-http.conf` once under the existing `http {}` block, and `nginx/axia-locations.conf` inside the existing HTTPS `server {}` for `kimtaeeun.site`. Use absolute include paths. Preserve all unrelated routes and TLS settings. Nginx normally stops inheriting parent `add_header` directives when a location declares its own: repeat the site's required security headers (including its existing HSTS policy) in these locations, or use the installed Nginx version's explicit header-inheritance support. The port-80 site should already redirect to HTTPS.

```nginx
# In http {}, before the site server block:
include /etc/nginx/axia/axia-http.conf;

# Inside the existing HTTPS server {} for kimtaeeun.site:
include /etc/nginx/axia/axia-locations.conf;
```

Validate with `sudo nginx -t` before an operator reload. The proxy replaces—not trusts—client-supplied forwarded authority and request IDs, strips only `/axia/api/`, and forwards webhook entity bytes without buffering/body rewriting. It disables upstream retry, interception, and caches. API responses, including failures, receive `no-store`/`no-transform`. Do not add Lua/body transformations or automatic retry middleware to these routes.

The request ID generated by Nginx is sent to Ktor's CallId plugin and recorded in the JSON access log. Access logs include only method, normalized path **without query**, status, byte count, timing, and request ID; not Authorization, cookies, user agent, referrer, request bodies, or OAuth query values. Native Nginx request-scoped error logs can contain the whole OAuth request line, so those are suppressed in the API locations. Failures remain visible as JSON 4xx/5xx/upstream status and server error-type logs; unrelated Nginx infrastructure errors continue using the site's normal error log.

## Daily log retention

Install `logrotate/axia-server` and `logrotate/axia-nginx` under `/etc/logrotate.d/` as root-owned, non-writable configuration files, and confirm the host's daily logrotate timer/cron actually runs. Both rotate daily and delete after 14 days. Empty logs are rotated too, so retention still advances during inactivity.

Server events are JSON Lines in `/var/log/axia/server/server.json`. Initialization errors and uncaught JVM stderr are retained in `launcher.log`; they are not discarded when Docker engine logging is disabled. Read these files, not `docker compose logs`. The directory must be writable by UID/GID 10001 before launch. The server log policy uses `copytruncate` because the JVM keeps files open: a very small concurrent-copy loss window is an operational logging tradeoff, not a loss of database events. Nginx logs use reopen signaling instead. Do not enable verbose provider HTTP logging, JVM argument dumps, or resolved Compose configuration logging.

```sh
sudo logrotate --debug /etc/logrotate.d/axia-server /etc/logrotate.d/axia-nginx
sudo tail -n 50 /var/log/axia/server/server.json
sudo tail -n 50 /var/log/axia/server/launcher.log
```

## Cloudflare and external prerequisites

`cloudflare/cache-rules.fragment.json` is a mergeable Rulesets API fragment for the `http_request_cache_settings` phase—not a replacement for the zone's existing ruleset. Have the zone operator merge its API/webhook and feed bypass rules, in an order where later rules do not override them. Do not blindly PUT this fragment as the whole zone ruleset. Origin headers alone cannot override an existing cache-everything rule. Bypass can be reported as `CF-Cache-Status: DYNAMIC`, not necessarily `BYPASS`.

Keep Cloudflare-to-origin TLS in verified HTTPS mode. Apply the existing origin firewall/trusted-proxy policy; do not trust arbitrary client `CF-Connecting-IP` values. Interactive browser challenges and request-body transformations must not intercept OAuth callbacks or signed webhooks. These zone settings, DNS, TLS certificates, existing database access, actual provider registration, and release-signing credentials require operator verification; the repository cannot establish them locally.

**DataGSM production contract is an outstanding prerequisite.** The current documented student snapshot lacks a stable major-club ID, while club members lack immutable student IDs; the safe event decoder rejects insufficient contracts instead of guessing by names/student numbers. A provider-side stable-ID/full-snapshot contract and ordering/version semantics must be established before claiming reliable event lifecycle support. OAuth `/userinfo` must expose the specified `majorClub` and `githubId` fields. There is no polling/OpenAPI fallback.

**Draft residual permissions have an upstream history limitation.** Issue/PR permissions retained after POC removal are bound to immutable close and assignee-removal event IDs, so close/reopen and unassign/reassign cannot revive an old grant. For Drafts, observed removal, Done, archival, or board removal also ends the grant. However, the [DraftIssue API](https://docs.github.com/en/graphql/reference/projects#draftissue) exposes only current assignees, not an assignment timeline, and Project item webhooks do not provide stable before/after assignee IDs. An external Draft change that is reversed entirely between observations cannot be reconstructed. Neither an unrelated `updatedAt` change nor a webhook delivery ID is treated as proof of removal. Resolve this policy limitation with the product owner before relying on strict termination across unobserved Draft transitions in production.

After authorized deployment, verify callback/webhook paths and cache bypass at the public origin and both signed feed URLs. This repository's local verification does not imply that DNS, Cloudflare, TLS, production providers, or external MySQL were changed or tested.
