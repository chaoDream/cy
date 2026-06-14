# 省心买 · Web 端（PC 比价助手）

> 微信小程序的 PC Web 版替代渠道，支持搜索手机型号、粘贴京东链接、查看真实到手价、AI 购买建议、价格走势和低价榜。

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Vue 3 + Vite + TypeScript |
| UI 库 | Element Plus（中文，按需导入） |
| 图表 | ECharts + vue-echarts |
| HTTP | Axios（拦截器统一处理 `{code, message, data}` 响应） |
| 路由 | Vue Router 4（History 模式，URL 可分享） |

---

## 快速开始（本地开发）

### 前置条件

- Node.js 16+（推荐 20）
- 后端服务运行在 `localhost:8080`（见仓库根目录 README）

### 启动

```bash
cd web
npm install
npm run dev
```

打开 http://localhost:5173 即可使用。Vite 会自动将 `/api/*` 请求代理到 `localhost:8080`。

### 构建生产包

```bash
npm run build      # 产出在 web/dist/
```

---

## 页面与功能说明

### 1. 首页（`/`）

| 功能 | 说明 |
|------|------|
| 搜索框 | 输入手机型号（如"iPhone 16 Pro"）搜索，或粘贴京东商品链接/URL 直接解析 |
| 品牌快筛 | 点击品牌按钮跳转到对应品牌的低价榜 |
| 当前好价 | 自动加载推荐商品卡片，点击进入分析页 |
| 小程序横幅 | 底部提示微信小程序即将上线 |

**搜索逻辑**：
- 输入内容包含 `jd.com`、`3.cn`、`item.jd`、`https://` → 识别为链接，调用链接解析接口后跳转分析页
- 其他文本 → 按关键词搜索，下拉展示匹配商品列表

### 2. 商品分析页（`/analysis?platform=jd&item_id=xxx`）

核心页面，展示一个商品的完整价格分析：

| 模块 | 说明 |
|------|------|
| 商品信息 | 图片、标题、标准型号、店铺类型（自营/旗舰店/第三方）、活动标签 |
| 价格拆解 | 从显示价逐步扣减各项优惠（平台券、国补等），最终展示红色到手价 |
| 价格走势 | ECharts 折线图，标注 30/90 天最低价参考线；提示是否接近低价或疑似先涨后降 |
| AI 购买建议 | 四级建议（建议买/可以等/谨慎买/不建议买），附理由和置信度 |
| 风险提示 | 第三方店铺、疑似翻新等风险标签 |
| 更多选择 | 同平台其他店铺、跨平台替代方案及价格 |
| 底部操作栏 | 到手价 + 分享按钮（复制 URL）+ 去京东购买 |

**URL 可分享**：将当前页面链接发给朋友，对方打开即可看到同样的分析结果。

### 3. 低价榜（`/rank`）

| 功能 | 说明 |
|------|------|
| 品牌筛选 | 下拉选择品牌（Apple/华为/小米/vivo/OPPO/荣耀/三星/一加/全部） |
| 价格区间 | 设定最低价和最高价过滤 |
| 排行表格 | 按到手价升序排列，展示型号、图片、价格、平台、风险标签 |
| 查看分析 | 点击跳转对应商品的分析页 |

### 4. 资产库（右上角齿轮图标）

侧边抽屉，设置个人权益影响到手价计算：

| 设置项 | 说明 |
|--------|------|
| 88VIP 会员 | 开启后扣减 88VIP 专属折扣 |
| 京东 Plus 会员 | 开启后扣减 Plus 会员优惠 |
| 国补地区 | 选择省份后纳入政府手机消费补贴 |

数据存储在浏览器 localStorage，切换后分析页自动重新计算。

---

## 项目结构

```
web/
├── index.html                         # 入口 HTML（含 SEO meta）
├── vite.config.ts                     # Vite 配置（代理、别名、Element Plus 自动导入）
├── tsconfig.json
├── package.json
└── src/
    ├── main.ts                        # Vue 应用入口
    ├── App.vue                        # 根组件（Header + Router View + Footer）
    ├── router/index.ts                # 路由定义（/、/analysis、/rank）
    ├── api/
    │   ├── client.ts                  # Axios 实例 + 响应拦截器
    │   ├── types.ts                   # 所有 API 响应的 TypeScript 接口
    │   ├── product.ts                 # 商品相关 API（解析、分析、搜索、推荐）
    │   └── rank.ts                    # 低价榜 API
    ├── composables/
    │   ├── useAssets.ts               # 资产库（localStorage 持久化）
    │   └── useAnalysis.ts             # 并行加载分析 + AI 数据
    ├── views/
    │   ├── HomePage.vue               # 首页
    │   ├── AnalysisPage.vue           # 商品分析页
    │   └── RankPage.vue               # 低价榜
    └── components/
        ├── layout/                    # AppHeader、AppFooter
        ├── home/                      # SearchBar、BrandQuickFilter、ProductCard、RecommendGrid
        ├── analysis/                  # ProductHeader、PriceWaterfall、PriceTrendChart、
        │                              # AiRecommendation、RiskTags、CrossPlatform、BuyButton
        ├── rank/                      # RankFilters、RankTable
        └── common/                    # AssetDrawer、PriceDisplay、MiniProgramBanner
```

---

## 对接的后端 API

所有接口均为公开接口（无需登录），响应格式统一为 `{code: 0, message: "ok", data: T}`。

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/link/parse` | 解析京东商品链接/分享文本 |
| GET | `/api/product/analysis` | 商品价格分析（价格拆解、走势、风险、跨平台） |
| GET | `/api/product/ai-recommendation` | AI 购买建议（买/等/慎/避） |
| GET | `/api/product/search` | 按型号关键词搜索商品 |
| GET | `/api/product/recommend` | 首页推荐商品列表 |
| GET | `/api/product/image` | 商品图片代理 |
| GET | `/api/rank/phone` | 手机低价榜（支持品牌、价格区间筛选） |

---

## 部署

Web 端和后端部署在同一台服务器，由 Nginx 统一托管，一键部署。

### 部署架构

```
浏览器 → Nginx:80/443
              ├─ /api/*         → backend:8080（Spring Boot）
              ├─ /static/*      → 头像/商品图（Docker 卷）
              └─ /*             → web/dist/（SPA，try_files → index.html）
```

### 一键部署（和后端一起）

```bash
cd deploy

# 完整部署：推送代码 + 同步密钥 + 构建 Web + 构建后端 + 启动容器
./scripts/push-deploy.sh --push --sync-env
```

`deploy.sh` 会在启动 Docker 容器前自动执行 `build-web.sh` 构建前端，无需手动干预。

### 仅更新 Web 前端（不重建后端容器）

```bash
cd deploy

# 1. 在服务器上重新构建前端
./scripts/build-web.sh

# 2. 重启 Nginx 使新文件生效（Nginx 读的是 web/dist 目录挂载）
docker compose -f docker-compose.prod.yml restart nginx
```

### 相关脚本

| 脚本 | 说明 |
|------|------|
| `deploy/scripts/push-deploy.sh` | 一键推送 + 部署（含 Web 构建），日常发版用这个 |
| `deploy/scripts/deploy.sh` | 服务器上执行：构建 Web → 构建后端 → 启动所有容器 |
| `deploy/scripts/build-web.sh` | 仅构建 Web 前端（`npm ci && vite build` → `web/dist/`） |

### 服务器要求

- Docker + Docker Compose v2
- Node.js 16+（构建 Web 前端用）

Node.js 安装：

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

---

## 与小程序的关系

| 对比 | Web 端 | 小程序 |
|------|--------|--------|
| 入口 | 浏览器直接访问 | 微信扫码/搜索 |
| 搜索方式 | 型号搜索 + 粘贴链接 | 粘贴分享链接 + 剪贴板自动检测 |
| 登录 | 无需登录 | 微信静默登录 |
| 盯价 | 不支持（引导到小程序） | 支持，微信推送通知 |
| 数据 | 和小程序共用同一个后端，数据完全一致 | 同左 |
| 定位 | 不干扰后端和小程序运行 | 同左 |
