#!/usr/bin/env bash
# 从 backend/application-local.yml 同步密钥到 deploy/.env
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/sync-env-from-local.py" "$@"
