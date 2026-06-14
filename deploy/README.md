# 方案 A：单机 Docker 部署（腾讯云 CVM）

一台 CVM 上运行 **Nginx + Spring Boot + PostgreSQL + Redis**，与本地 `docker-compose.yml` 架构一致，但生产环境不对公网暴露 5432/6379/8080。

Nginx 同时托管 **Web 前端**（`web/dist/`）和 **API 反向代理**，同源部署，无 CORS 问题。

> **文档维护约定**：凡改动 `deploy/` 下部署脚本、`.env.example`、`docker-compose.prod.yml`、密钥同步流程、发版命令或运维步骤，**须同步更新本 README**（必要时一并更新 `deploy/.env.example` 顶部注释）。避免「代码/脚本已变、文档仍写旧流程」。

---

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

---

## 密钥与环境变量

### 两份配置，分工明确

| 文件 | 是否进 Git | 用途 |
|------|------------|------|
| `backend/application-local.yml` | 否（`backend/.gitignore`） | **密钥唯一来源**：本地 `./gradlew bootRun` 与同步脚本均读此文件 |
| `deploy/.env` | 否（`deploy/.gitignore`） | Docker Compose 读此文件，供本地/服务器容器启动 |
| `deploy/.env.example` | 是 | 字段模板与说明，**不含真实密钥** |
| `deploy/deploy.local.env` | 否 | 本机 SSH 发版目标（`DEPLOY_HOST` 等），不参与容器 |

### 从 `application-local.yml` 同步到 `deploy/.env`

**日常只改 `application-local.yml`**，再执行同步脚本即可更新 `deploy/.env` 中的联盟/微信/AI 等字段；**不会覆盖**数据库密码、JWT、Nginx 等部署专有项。

```bash
cd deploy

# 更新本地 deploy/.env
./scripts/sync-env-from-local.sh

# 预览将写入的键值（不实际改文件）
./scripts/sync-env-from-local.sh --dry-run

# 同时更新服务器上的 deploy/.env（需已配置 deploy.local.env）
./scripts/sync-env-from-local.sh --remote
```

同步脚本：`scripts/sync-env-from-local.py`（由 `sync-env-from-local.sh` 调用）。

**由 YAML 同步的字段**（示例）：`WX_*`、`VEAPI_*`、`JD_*`、`PDD_*`、`AI_*`、`AFFILIATE_MOCK`、`JD_PROVIDER_*`、`PDD_PROVIDER_*` 等。

**仅在 `deploy/.env` 手动维护的字段**（首次部署填一次，通常不变）：

- `POSTGRES_PASSWORD`、`JWT_SECRET`
- `NGINX_CONF`、`WX_SUB_TEMPLATE`（若与本地 yml 不一致）
- `PRICE_SEED_ENABLED`（种子采价开关，默认 `true`）

### 联盟 Provider（生产仅真实数据）

生产默认与本地 `application-local.yml` 一致：

- 京东：`veapi` → 降级 `jd_official`（**不含 mock**）
- 拼多多：`veapi`（无 mock 降级）

对应 `.env` 示例见 `.env.example`。`AFFILIATE_MOCK=false` 时，种子采价任务也会拒绝 mock 数据源。

---

## 首次部署（新 CVM）

```bash
# 1. 克隆代码到服务器
git clone <repo-url> shengxin-buy && cd shengxin-buy/deploy

# 2. 配置 deploy/.env
cp .env.example .env
vim .env
# 必填：POSTGRES_PASSWORD、JWT_SECRET
# 联盟/微信/AI：建议在开发机改 application-local.yml 后执行 sync-env-from-local.sh --remote

# 3. 种子采价清单（40 款，可选）
cp ../backend/price-seeds.yml.example ../backend/price-seeds.yml
# 编辑 price-seeds.yml；compose 已挂载到容器 /app/price-seeds.yml

# 4. 首次启动（HTTP，验证链路）
chmod +x scripts/*.sh
./scripts/deploy.sh

# 5. 验证
curl http://<公网IP>/api/health

# 6. 配置 SSL（证书放到 deploy/certs/：fullchain.pem + privkey.pem）
# 编辑 .env：NGINX_CONF=./nginx/default.conf
./scripts/deploy.sh

# 7. 小程序侧
#    - 后台配置 request 合法域名 https://api.xxx.com
#    - miniprogram/utils/config.js：LOCAL_DEBUG=false，prod.baseUrl 改为真实域名
```

---

## 日常发版（推荐）

在**开发机**配置 SSH 目标（不提交 Git）：

```bash
cd deploy
cp deploy.local.env.example deploy.local.env
vim deploy.local.env   # DEPLOY_HOST / DEPLOY_USER / DEPLOY_PATH
chmod +x scripts/*.sh
```

### 标准流程

```bash
# 改完 application-local.yml 后：同步密钥 + push 代码 + 远程部署
./scripts/push-deploy.sh --push --sync-env
```

### 其他用法

```bash
# 仅远程拉代码并部署（密钥未变）
./scripts/push-deploy.sh

# 先 push，不同步密钥
./scripts/push-deploy.sh --push

# 仅同步密钥到本地与服务器，不部署
./scripts/sync-env-from-local.sh --remote

# 预览将执行的命令
./scripts/push-deploy.sh --push --sync-env --dry-run

# 跳过健康检查
./scripts/push-deploy.sh --push --skip-health
```

**说明：**

- `--sync-env` 会在部署前从 `backend/application-local.yml` 更新**本地** `deploy/.env`，并通过 SSH 更新**服务器** `deploy/.env`（只改映射字段，不动 `POSTGRES_PASSWORD` / `JWT_SECRET` 等）。
- 脚本**不会**把 `.env` 或密钥提交到 Git。
- 需本机可 `ssh user@host` 免密登录；仓库已在服务器的 `DEPLOY_PATH` clone 好。

---

## 种子采价（定时任务）

| 项 | 说明 |
|----|------|
| 开关 | `PRICE_SEED_ENABLED=true`（`.env`） |
| 清单 | `backend/price-seeds.yml`（从 `price-seeds.yml.example` 复制，**不进 Git**） |
| 挂载 | `docker-compose.prod.yml` 将清单只读挂载到容器 `/app/price-seeds.yml` |
| 定时 | 默认每天 **06:00**（`zdsj.price-seed.poll-cron`） |
| 手动补采 | 管理看板 `/admin/` →「立即重新采价」，或 `POST /api/admin/price-seed/run` |
| 执行信号 | 看板「今日 06:00 窗口内快照数」；或查库 `price_snapshot` |

改清单或开关后需 **重建 backend 容器**（`deploy.sh` 或 `push-deploy.sh`）。

---

## 常用命令

```bash
cd deploy

# 查看日志
docker compose -f docker-compose.prod.yml logs -f backend

# 重建并启动 backend（改 .env / compose 后）
docker compose -f docker-compose.prod.yml up -d --force-recreate backend

# 重启
docker compose -f docker-compose.prod.yml restart backend

# 停止
docker compose -f docker-compose.prod.yml down

# 数据库备份（建议 cron 每日执行）
./scripts/backup-db.sh

# 容器内健康检查
docker compose -f docker-compose.prod.yml exec backend wget -qO- http://127.0.0.1:8080/api/health
```

---

## 脚本索引

| 脚本 | 作用 |
|------|------|
| `scripts/deploy.sh` | 服务器上构建 Web 前端 + 拉镜像/构建后端并启动 compose |
| `scripts/push-deploy.sh` | 本机 SSH 远程 `git pull` + `deploy.sh`；支持 `--push`、`--sync-env` |
| `scripts/build-web.sh` | 构建 Web 前端到 `web/dist/`（`deploy.sh` 内部自动调用） |
| `scripts/sync-env-from-local.sh` | 从 `application-local.yml` 同步密钥到 `deploy/.env`；`--remote` 同步到 CVM |
| `scripts/backup-db.sh` | PostgreSQL 备份 |
| `scripts/init-tencent-cloud.sh` | 新 CVM 安装 Docker 等 |

---

## Web 前端

Web 前端代码位于 `web/` 目录，基于 Vue 3 + Vite + Element Plus。

### 本地开发

```bash
cd web
npm install
npm run dev    # http://localhost:5173，自动代理 /api 到 localhost:8080
```

### 生产构建

`deploy.sh` 会在启动容器前自动执行 `build-web.sh`，将 `web/dist/` 构建好后挂载到 Nginx 容器的 `/var/www/web`。

也可以手动构建：

```bash
cd deploy
./scripts/build-web.sh
```

### 部署架构

```
浏览器 → Nginx:80/443
              ├─ /api/*         → backend:8080（Spring Boot）
              ├─ /static/*      → 头像/商品图（Docker 卷）
              └─ /*             → web/dist/（SPA，try_files → index.html）
```

服务器需安装 Node.js 16+（构建 Web 前端用）。可通过 `init-tencent-cloud.sh` 自动安装，或手动：

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

---

## 注意事项

- **构建内存**：`docker compose build` 会在容器内跑 Gradle，CVM 建议 ≥4G 内存；若 OOM，可在本地 `./gradlew bootJar` 后改用预构建镜像。
- **Node.js**：服务器需安装 Node.js 16+ 用于构建 Web 前端（`npm ci && vite build`）。
- **密钥**：`.env`、`deploy.local.env`、`certs/`、`backend/application-local.yml`、`backend/price-seeds.yml` 均勿提交仓库。
- **改密钥后**：本地跑 `sync-env-from-local.sh --remote`，再 `up -d --force-recreate backend`（或 `push-deploy.sh --sync-env` 一并完成）。
- **用户头像**：登录时下载到 Docker 卷 `avatar_data`，Nginx 路径 `/static/avatars/` 静态托管。
- **商品主图**：解析/盯价/采价时按需落盘到 `product_image_data`，Nginx 路径 `/static/products/` 静态托管。
- **上线清单**：见仓库 `docs/RELEASE_CHECKLIST.md`。
