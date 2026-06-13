#!/usr/bin/env bash
#
# 本地一键推送代码并在远程服务器重新部署
#
# 前置：
#   1. 在 deploy 目录: cp deploy.local.env.example deploy.local.env 并填写 SSH 信息
#   2. 服务器已 clone 仓库且 deploy/.env 已配置好密钥
#   3. 本机可免密 SSH 登录服务器（ssh-copy-id）
#
# 用法：
#   ./scripts/push-deploy.sh              # 远程 git pull + deploy
#   ./scripts/push-deploy.sh --push       # 先 git push 当前分支，再远程部署
#   ./scripts/push-deploy.sh --sync-env   # 从 application-local.yml 同步密钥后再部署
#   ./scripts/push-deploy.sh --dry-run    # 只打印将执行的命令
#
set -euo pipefail

DEPLOY_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$DEPLOY_ROOT/.." && pwd)"
LOCAL_ENV="$DEPLOY_ROOT/deploy.local.env"

DO_PUSH=false
DRY_RUN=false
SKIP_HEALTH=false
FORCE=false
SYNC_ENV=false

usage() {
  cat <<'EOF'
用法: ./scripts/push-deploy.sh [选项]

选项:
  --push         部署前先 git push 当前分支到 origin
  --sync-env     部署前从 backend/application-local.yml 同步密钥到本地/服务器 deploy/.env
  --dry-run      仅打印命令，不实际执行
  --skip-health  跳过部署后的 /api/health 检查
  --force        有未提交改动时也继续（配合 --push 时不会自动提交）
  -h, --help     显示帮助

示例:
  ./scripts/push-deploy.sh --push
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --push) DO_PUSH=true; shift ;;
    --sync-env) SYNC_ENV=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --skip-health) SKIP_HEALTH=true; shift ;;
    --force) FORCE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1"; usage; exit 1 ;;
  esac
done

if [[ ! -f "$LOCAL_ENV" ]]; then
  EXAMPLE="$DEPLOY_ROOT/deploy.local.env.example"
  if [[ -f "$EXAMPLE" ]]; then
    cp "$EXAMPLE" "$LOCAL_ENV"
    echo "已从示例创建: $LOCAL_ENV"
    echo "请编辑其中的 DEPLOY_HOST / DEPLOY_USER / DEPLOY_PATH 后重新运行。"
    exit 1
  fi
  echo "未找到配置文件: $LOCAL_ENV"
  echo "在 deploy 目录执行: cp deploy.local.env.example deploy.local.env"
  echo "并填写 DEPLOY_HOST / DEPLOY_USER / DEPLOY_PATH"
  exit 1
fi

# shellcheck disable=SC1090
source "$LOCAL_ENV"

: "${DEPLOY_HOST:?请在 deploy.local.env 中设置 DEPLOY_HOST}"
: "${DEPLOY_USER:?请在 deploy.local.env 中设置 DEPLOY_USER}"
: "${DEPLOY_PATH:?请在 deploy.local.env 中设置 DEPLOY_PATH}"

DEPLOY_BRANCH="${DEPLOY_BRANCH:-main}"
GIT_REMOTE="${GIT_REMOTE:-origin}"
HEALTH_URL="${HEALTH_URL:-http://${DEPLOY_HOST}/api/health}"

SSH_ARGS=()
if [[ -n "${DEPLOY_SSH_KEY:-}" ]]; then
  SSH_ARGS+=(-i "${DEPLOY_SSH_KEY/#\~/$HOME}")
fi
SSH_ARGS+=("${DEPLOY_USER}@${DEPLOY_HOST}")

run() {
  if $DRY_RUN; then
    echo "[dry-run] $*"
  else
    "$@"
  fi
}

log() { echo ">>> $*"; }

# ---------- 可选：从 application-local.yml 同步 deploy/.env ----------
if $SYNC_ENV; then
  SYNC_ARGS=()
  $DRY_RUN && SYNC_ARGS+=(--dry-run)
  $DRY_RUN || SYNC_ARGS+=(--remote)
  log "从 application-local.yml 同步密钥 ..."
  run python3 "$DEPLOY_ROOT/scripts/sync-env-from-local.py" "${SYNC_ARGS[@]}"
fi

# ---------- 本地 Git 检查 / 推送 ----------
if [[ -d "$REPO_ROOT/.git" ]]; then
  cd "$REPO_ROOT"
  if ! git diff --quiet || ! git diff --cached --quiet; then
    if $FORCE; then
      log "警告：工作区有未提交改动，已 --force 继续"
    else
      echo "错误：存在未提交的本地改动。请先 commit，或加 --force 跳过检查。"
      git status -sb
      exit 1
    fi
  fi

  CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
  log "当前分支: $CURRENT_BRANCH"

  if $DO_PUSH; then
    log "推送到 ${GIT_REMOTE}/${CURRENT_BRANCH} ..."
    run git push "${GIT_REMOTE}" "${CURRENT_BRANCH}"
  else
    LOCAL_HEAD="$(git rev-parse HEAD)"
    REMOTE_HEAD="$(git rev-parse "${GIT_REMOTE}/${CURRENT_BRANCH}" 2>/dev/null || echo "")"
    if [[ -n "$REMOTE_HEAD" && "$LOCAL_HEAD" != "$REMOTE_HEAD" ]]; then
      log "提示：本地与 ${GIT_REMOTE}/${CURRENT_BRANCH} 不一致，远程可能拉不到最新代码"
      log "      建议加 --push，或手动 git push 后再部署"
    fi
  fi
else
  log "未检测到 Git 仓库，跳过本地 push 步骤"
fi

# ---------- 远程部署 ----------
REMOTE_CMD=$(cat <<EOF
set -euo pipefail
if [[ ! -d "${DEPLOY_PATH}/.git" ]]; then
  echo "错误：服务器上未找到 Git 仓库: ${DEPLOY_PATH}"
  echo "请先在服务器执行: git clone <repo-url> ${DEPLOY_PATH}"
  exit 1
fi
cd "${DEPLOY_PATH}"
echo ">>> git fetch ${GIT_REMOTE} ..."
git fetch ${GIT_REMOTE}
echo ">>> git checkout ${DEPLOY_BRANCH} ..."
git checkout ${DEPLOY_BRANCH}
echo ">>> git pull --ff-only ${GIT_REMOTE} ${DEPLOY_BRANCH} ..."
git pull --ff-only ${GIT_REMOTE} ${DEPLOY_BRANCH}
cd deploy
chmod +x scripts/*.sh
echo ">>> 运行 deploy.sh ..."
./scripts/deploy.sh
EOF
)

log "SSH 连接 ${DEPLOY_USER}@${DEPLOY_HOST} 开始部署 ..."
if $DRY_RUN; then
  echo "[dry-run] ssh ${SSH_ARGS[*]} bash -s <<'REMOTE'"
  echo "$REMOTE_CMD"
else
  ssh "${SSH_ARGS[@]}" bash -s <<< "$REMOTE_CMD"
fi

# ---------- 健康检查 ----------
if ! $SKIP_HEALTH; then
  log "健康检查: ${HEALTH_URL}"
  if $DRY_RUN; then
    echo "[dry-run] curl -sf ${HEALTH_URL}"
  else
    sleep 3
    if curl -sf --connect-timeout 10 "${HEALTH_URL}" | grep -q '"status":"UP"'; then
      log "部署成功，服务已就绪。"
    else
      echo "警告：健康检查未通过，请登录服务器查看日志："
      echo "  ssh ${DEPLOY_USER}@${DEPLOY_HOST}"
      echo "  cd ${DEPLOY_PATH}/deploy && docker compose -f docker-compose.prod.yml logs -f backend"
      exit 1
    fi
  fi
fi

log "全部完成。"
