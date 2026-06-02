#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "未找到 .env，正在从 .env.example 复制..."
  cp .env.example .env
  echo "请先编辑 deploy/.env 填入密钥，再重新运行本脚本。"
  exit 1
fi

# shellcheck disable=SC1091
source .env

if [[ "${NGINX_CONF:-./nginx/default.http-only.conf}" == *"default.conf"* ]]; then
  if [[ ! -f certs/fullchain.pem || ! -f certs/privkey.pem ]]; then
    echo "错误：NGINX_CONF 指向 HTTPS 配置，但 deploy/certs/ 下缺少 fullchain.pem 或 privkey.pem"
    exit 1
  fi
fi

echo ">>> 构建并启动服务..."
docker compose -f docker-compose.prod.yml up -d --build

echo ">>> 等待健康检查..."
sleep 5

if curl -sf http://127.0.0.1/api/health >/dev/null 2>&1; then
  echo "健康检查通过（经 Nginx → Backend）。"
else
  echo "服务尚未就绪，查看日志：docker compose -f docker-compose.prod.yml logs -f backend"
fi

echo ""
echo "部署完成。对外访问："
if [[ "${NGINX_CONF:-}" == *"http-only"* ]]; then
  echo "  http://<服务器公网IP>/api/health"
  echo "  （小程序上线前请配置 SSL 并改 NGINX_CONF=./nginx/default.conf）"
else
  echo "  https://<你的域名>/api/health"
fi
