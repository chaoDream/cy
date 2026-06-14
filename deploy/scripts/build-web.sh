#!/usr/bin/env bash
#
# 构建 Web 前端到 web/dist/
# 用法：./scripts/build-web.sh
#
set -euo pipefail

DEPLOY_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$DEPLOY_ROOT/.." && pwd)"
WEB_DIR="$REPO_ROOT/web"

if [[ ! -f "$WEB_DIR/package.json" ]]; then
  echo "错误：未找到 web/package.json"
  exit 1
fi

echo ">>> 安装依赖..."
cd "$WEB_DIR"
npm ci --prefer-offline 2>/dev/null || npm install

echo ">>> 构建前端..."
npx vite build

echo ">>> 构建完成：web/dist/"
ls -lh dist/index.html
