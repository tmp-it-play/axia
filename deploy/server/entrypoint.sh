#!/bin/sh
set -eu
umask 027

# Preserve JVM initialization failures too; operational logs are retained by the host's daily logrotate job.
# The host bind directory must already be writable by UID/GID 10001 before starting the service.
exec /opt/axia/bin/server >>/var/log/axia/launcher.log 2>&1
