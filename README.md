# 真到手价 · AI 购物决策助手（MVP）

> 站在用户这边、会算「个人专属到手价」、能主动盯价、帮你做购买决定、还帮你避开打折陷阱的 AI 购物决策助手。首发手机/3C 数码，微信小程序 + PC Web 端。

完整需求见 [cc_PRD.md](cc_PRD.md)。

## 仓库结构

```
shengxin-buy/
├── cc_PRD.md            # 产品需求文档（冻结）
├── docker-compose.yml   # 本地 PostgreSQL + Redis
├── backend/             # Spring Boot 3 (Kotlin) 模块化单体
├── miniprogram/         # 微信小程序原生工程
├── web/                 # PC Web 端（Vue 3 + Vite + Element Plus）
└── deploy/              # 单机 Docker 部署（Nginx + 后端 + DB）
```

## 技术栈

| 层 | 选型 |
|---|---|
| 前端（小程序） | 微信小程序原生（WXML/WXSS/JS + 自定义组件） |
| 前端（Web） | Vue 3 + Vite + TypeScript + Element Plus + ECharts |
| 后端 | Kotlin + Spring Boot 3，模块化单体（按服务边界分包） |
| 数据库 | PostgreSQL（业务 + 价格快照）+ Redis（缓存/限流） |
| 定时任务 | Quartz（漏斗轮询盯价） |
| AI | 大模型 Function Calling（事实走工具、AI 不编价） |
| 部署 | Docker + 单台云服务器 |

## 后端模块（package 边界）

`com.zdsj.{common,config,user,product,sku,price,ai,watch,notify,affiliate,rank,feedback,admin}`

## 快速开始（后端）

```bash
# 1. 启动依赖
docker compose up -d        # PostgreSQL:5432 + Redis:6379

# 2. 启动后端（需 JDK 17+）
cd backend
./gradlew bootRun           # 默认 dev profile，Flyway 自动建表

# 健康检查
curl http://localhost:8080/api/health
```

## 快速开始（Web 端）

```bash
# 1. 确保后端已启动（localhost:8080）
# 2. 启动 Web 开发服务器
cd web
npm install
npm run dev         # http://localhost:5173
```

详细说明见 [web/README.md](web/README.md)。

## 快速开始（小程序）

1. 用「微信开发者工具」导入 `miniprogram/` 目录。
2. 在 `miniprogram/utils/config.js` 中配置后端 `baseUrl`。
3. 在小程序后台「开发管理 → 开发设置」配置 request 合法域名（https）。
4. 准备比价/导购类目资质与企业主体（提审前置）。

## 合规红线（强制）

- 仅用官方联盟 API，不爬虫、不抓 Cookie、不模拟登录、不绕过风控。
- 不承诺「全网绝对最低价」，统一表述「已知可领券与公开补贴后的最优价」。
- AI 不编价：到手价由规则引擎计算，AI 仅解释。
- CPS 返佣不影响推荐排序，且透明披露。
- 价格展示必附免责声明：「价格为公开优惠与平台返回数据估算，最终以下单页为准。」

## 8 周里程碑

见 [cc_PRD.md](cc_PRD.md) §14 与 `.cursor/plans/` 中的开发计划。
