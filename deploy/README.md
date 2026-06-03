# 方案 A：单机 Docker 部署（腾讯云 CVM）

一台 CVM 上运行 **Nginx + Spring Boot + PostgreSQL + Redis**，与本地 `docker-compose.yml` 架构一致，但生产环境不对公网暴露 5432/6379/8080。

## 腾讯云准备

1. CVM（建议 2 核 4G+）、弹性公网 IP
2. 安全组：入站仅 **22 / 80 / 443**
3. 域名 + ICP 备案 + SSL 证书（小程序必须 HTTPS）
4. 服务器安装 Docker 与 Docker Compose v2

### 0. 一键初始化 CVM（推荐）

SSH 登录新购 CVM 后执行（建议 2 核 4G+，低内存可加 swap）：

```bash
# 克隆仓库后
cd shengxin-buy/deploy
sudo ./scripts/init-tencent-cloud.sh --swap 2G

# 或直接从脚本路径运行（需先把脚本拷到服务器）
sudo bash init-tencent-cloud.sh --swap 2G
```

脚本会自动：安装 Docker / Compose v2、基础工具、时区、可选 swap、本机防火墙（22/80/443）。
若只依赖腾讯云安全组，可加 `--skip-firewall`。

## 部署步骤

```bash
# 1. 克隆代码到服务器
git clone <repo-url> shengxin-buy && cd shengxin-buy/deploy

# 2. 配置环境变量
cp .env.example .env
vim .env   # 填入数据库密码、JWT、微信、联盟、AI 等

# 3. 首次启动（HTTP，用于验证链路）
chmod +x scripts/*.sh
./scripts/deploy.sh

# 4. 验证
curl http://<公网IP>/api/health

# 5. 配置 SSL（腾讯云下载证书 PEM，放到 deploy/certs/）
#    fullchain.pem  +  privkey.pem
# 编辑 .env：NGINX_CONF=./nginx/default.conf
./scripts/deploy.sh

# 6. 小程序侧
#    - 后台配置 request 合法域名 https://api.xxx.com
#    - miniprogram/utils/config.js：LOCAL_DEBUG=false，prod.baseUrl 改为真实域名
```

### 本地一键部署到服务器（推荐日常更新）

在**开发机**配置 SSH 目标（不提交 Git）：

```bash
cd deploy
cp deploy.local.env.example deploy.local.env
vim deploy.local.env   # DEPLOY_HOST / DEPLOY_USER / DEPLOY_PATH
chmod +x scripts/push-deploy.sh
```

日常发版（先 push 再远程 pull + 重建容器）：

```bash
./scripts/push-deploy.sh --push
```

仅远程拉代码并部署（代码已在 origin 上）：

```bash
./scripts/push-deploy.sh
```

预览将执行的命令：

```bash
./scripts/push-deploy.sh --push --dry-run
```

**说明：**
- 脚本**不会**同步 `deploy/.env` 或密钥到服务器；服务器 `.env` 仅在 CVM 上维护一份。
- 需本机可 `ssh user@host` 免密登录；仓库已在服务器的 `DEPLOY_PATH` clone 好。

## 常用命令

```bash
cd deploy

# 查看日志
docker compose -f docker-compose.prod.yml logs -f backend

# 重启
docker compose -f docker-compose.prod.yml restart backend

# 停止
docker compose -f docker-compose.prod.yml down

# 数据库备份（建议 cron 每日执行 scripts/backup-db.sh）
./scripts/backup-db.sh
```

## 注意事项

- **构建内存**：`docker compose build` 会在容器内跑 Gradle，CVM 建议 ≥4G 内存；若 OOM，可在本地 `./gradlew bootJar` 后改用预构建镜像。
- **密钥**：`.env` 与 `certs/` 已 gitignore，勿提交仓库。
- **上线清单**：见仓库 `docs/RELEASE_CHECKLIST.md`。
