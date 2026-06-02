#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shellcheck disable=SC1091
source .env

BACKUP_DIR="$ROOT/backups"
mkdir -p "$BACKUP_DIR"

STAMP="$(date +%Y%m%d_%H%M%S)"
FILE="$BACKUP_DIR/zdsj_${STAMP}.sql.gz"

echo ">>> 备份 PostgreSQL 到 $FILE"
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "${POSTGRES_USER:-zdsj}" "${POSTGRES_DB:-zdsj}" | gzip > "$FILE"

echo "完成。建议定期上传到 COS 或异地保存。"
