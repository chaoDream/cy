#!/usr/bin/env bash
#
# 腾讯云 CVM 初始化脚本（方案 A：Docker 单机部署）
# 支持：Ubuntu 22.04/24.04、Debian 11/12、OpenCloudOS 8/9、CentOS Stream 9
#
# 用法（需 root）：
#   curl -fsSL <raw-url>/init-tencent-cloud.sh | sudo bash
#   或克隆仓库后：
#   sudo ./scripts/init-tencent-cloud.sh [--swap 2G] [--skip-firewall]
#
set -euo pipefail

SWAP_SIZE=""
SKIP_FIREWALL=false
APP_USER="${APP_USER:-ubuntu}"

usage() {
  cat <<'EOF'
腾讯云 CVM 初始化脚本

选项：
  --swap SIZE        创建 swap 文件（如 2G），低内存机器构建 Docker 镜像时建议开启
  --skip-firewall    跳过本机防火墙（仅依赖腾讯云安全组时可加此参数）
  --app-user USER    将当前用户加入 docker 组（默认 ubuntu，不存在则跳过）
  -h, --help         显示帮助

示例：
  sudo ./scripts/init-tencent-cloud.sh --swap 2G
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --swap) SWAP_SIZE="${2:?--swap 需要指定大小，如 2G}"; shift 2 ;;
    --skip-firewall) SKIP_FIREWALL=true; shift ;;
    --app-user) APP_USER="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1"; usage; exit 1 ;;
  esac
done

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 运行：sudo $0"
  exit 1
fi

log() { echo "[init] $*"; }

OS_ID=""
OS_VERSION_ID=""
PKG=""
if [[ -f /etc/os-release ]]; then
  # shellcheck disable=SC1091
  source /etc/os-release
  OS_ID="${ID:-}"
  OS_VERSION_ID="${VERSION_ID:-}"
fi

case "$OS_ID" in
  ubuntu|debian) PKG=apt ;;
  opencloudos|centos|rhel|rocky|almalinux|fedora) PKG=yum ;;
  *)
    echo "不支持的操作系统: ${OS_ID:-unknown}（/etc/os-release）"
    exit 1
    ;;
esac

log "检测到系统: ${PRETTY_NAME:-$OS_ID}"

install_base_packages() {
  log "安装基础工具..."
  if [[ "$PKG" == apt ]]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq \
      ca-certificates curl gnupg lsb-release \
      git vim unzip tar gzip \
      htop chrony
    systemctl enable --now chrony 2>/dev/null || true
  else
    if command -v dnf >/dev/null 2>&1; then
      dnf install -y ca-certificates curl git vim unzip tar gzip htop chrony
    else
      yum install -y ca-certificates curl git vim unzip tar gzip htop chrony
    fi
    systemctl enable --now chronyd 2>/dev/null || true
  fi
}

configure_timezone() {
  log "设置时区 Asia/Shanghai..."
  if command -v timedatectl >/dev/null 2>&1; then
    timedatectl set-timezone Asia/Shanghai
  else
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
  fi
}

configure_swap() {
  [[ -z "$SWAP_SIZE" ]] && return 0
  if swapon --show | grep -q '/swapfile'; then
    log "swap 已存在，跳过"
    return 0
  fi
  log "创建 swap: $SWAP_SIZE ..."
  fallocate -l "$SWAP_SIZE" /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=$(( ${SWAP_SIZE%G} * 1024 )) status=progress
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  if ! grep -q '/swapfile' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi
}

install_docker() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    log "Docker 已安装: $(docker --version), $(docker compose version)"
    return 0
  fi

  log "安装 Docker Engine + Compose v2..."
  curl -fsSL https://get.docker.com | sh

  systemctl enable docker
  systemctl start docker

  if id "$APP_USER" &>/dev/null; then
    usermod -aG docker "$APP_USER"
    log "已将用户 $APP_USER 加入 docker 组（重新登录后生效）"
  fi
}

configure_firewall() {
  if [[ "$SKIP_FIREWALL" == true ]]; then
    log "跳过本机防火墙配置"
    return 0
  fi

  log "配置本机防火墙（22/80/443）..."
  if [[ "$PKG" == apt ]]; then
    apt-get install -y -qq ufw
    ufw --force reset
    ufw default deny incoming
    ufw default allow outgoing
    ufw allow 22/tcp comment 'SSH'
    ufw allow 80/tcp comment 'HTTP'
    ufw allow 443/tcp comment 'HTTPS'
    ufw --force enable
    ufw status verbose
  else
    if command -v firewall-cmd >/dev/null 2>&1; then
      systemctl enable --now firewalld
      firewall-cmd --permanent --add-service=ssh
      firewall-cmd --permanent --add-service=http
      firewall-cmd --permanent --add-service=https
      firewall-cmd --reload
      firewall-cmd --list-all
    else
      log "未找到 ufw/firewalld，请仅在腾讯云安全组中放行 22/80/443"
    fi
  fi
}

tune_sysctl() {
  log "写入基础内核参数..."
  cat > /etc/sysctl.d/99-zdsj.conf <<'EOF'
# 真到手价后端
net.core.somaxconn = 1024
net.ipv4.tcp_max_syn_backlog = 2048
vm.swappiness = 10
EOF
  sysctl --system >/dev/null 2>&1 || true
}

verify() {
  log "验证安装..."
  docker --version
  docker compose version
  docker run --rm hello-world >/dev/null
  log "Docker 运行正常"
}

print_next_steps() {
  cat <<EOF

========================================
  腾讯云 CVM 初始化完成
========================================

请确认腾讯云控制台「安全组」入站规则已放行：
  - TCP 22   (SSH，建议限制来源 IP)
  - TCP 80   (HTTP)
  - TCP 443  (HTTPS)

后续部署步骤：

  # 若使用非 root 用户，先重新登录以生效 docker 组权限
  git clone <你的仓库地址> shengxin-buy
  cd shengxin-buy/deploy
  cp .env.example .env && vim .env
  ./scripts/deploy.sh
  curl http://<公网IP>/api/health

详细说明见 deploy/README.md

EOF
}

install_base_packages
configure_timezone
configure_swap
install_docker
configure_firewall
tune_sysctl
verify
print_next_steps
