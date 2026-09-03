#!/bin/sh
set -eu

port="${AXIA_SERVER_PORT:-8080}"
curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${port}/health/live" >/dev/null
curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${port}/health/ready" >/dev/null
