# 后端构建与运行

## 前置

- **JDK 17**（本项目目标版本；已用 `/Library/Java/JavaVirtualMachines/jdk-17.jdk`）
- 本地依赖：`docker compose up -d`（在仓库根目录，启动 PostgreSQL + Redis）

## Gradle 版本（重要）

本项目固定使用 **Gradle 8.7**（已随仓库提供 `gradlew` + `gradle-wrapper.jar` + properties）。

- 直接用 `./gradlew`，wrapper 会自动下载/复用 8.7，**不要**再执行 `gradle wrapper`。
- 切勿升级到 Gradle 9.x：当前 `build.gradle.kts` 用的是 Spring Boot 3.2.5 + Kotlin 1.9.25 插件，仅兼容 Gradle 8.x。
  若确需 Gradle 9.x，需同步升级 Spring Boot 3.4+ 与 Kotlin 2.x。

## 设置 JAVA_HOME（zsh）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
# 或固定路径
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
java -version   # 应显示 17.x
```

## 若遇到 `.gradle/.../*.lock (Operation not permitted)`

多为残留守护进程/锁或换过 Gradle 版本所致，按序处理：

```bash
cd backend
./gradlew --stop        # 停掉旧 daemon（含 9.x）
rm -rf .gradle build    # 清理项目级缓存与卡住的锁
./gradlew bootRun       # 重新构建
```

## 运行

```bash
./gradlew bootRun
# 或打包
./gradlew bootJar && java -jar build/libs/zdsj-backend-0.1.0-SNAPSHOT.jar
```

启动后 Flyway 自动建表（V1 + V2 种子）。健康检查：

```bash
curl http://localhost:8080/api/health
```

## 关键开关（application.yml / 环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `AFFILIATE_MOCK` | true | 未接入联盟 API 时用 mock 目录跑通全链路 |
| `AI_MOCK` | true | 未配置大模型时用确定性规则推理 |
| `AI_API_KEY` | 空 | 配置后走真实大模型（OpenAI 兼容） |
| `WX_APPID`/`WX_SECRET` | dev | 配置后启用真实微信登录与订阅消息 |
| `JWT_SECRET` | dev | 生产必须替换为 ≥32 字节随机串 |

## 京东联盟（真实 API）

密钥放在 `backend/application-local.yml`（已 gitignore，勿提交），可参考 `application-local.yml.example`：

```yaml
zdsj:
  affiliate:
    mock: false
    jd:
      app-key: YOUR_JD_APP_KEY
      app-secret: YOUR_JD_APP_SECRET
      union-id: YOUR_JD_UNION_ID   # 媒体 ID
      site-id: YOUR_JD_SITE_ID     # 可选，有站点 ID 时用 common 转链
```

也可用环境变量：`JD_APP_KEY`、`JD_APP_SECRET`、`JD_UNION_ID`、`JD_SITE_ID`。

启动时 `./gradlew bootRun` 会自动加载同目录下的 `application-local.yml`。

| 接口 | 用途 |
|---|---|
| `jd.union.open.promotion.byunionid.get` | 短链/口令转推广链（默认，用 unionId） |
| `jd.union.open.promotion.common.get` | 转链（需配置 siteId） |
| `jd.union.open.goods.query` | SKU/关键词查商品（需联盟权限） |
| `jd.union.open.goods.promotiongoodsinfo.query` | 按 SKU 查推广信息（需权限） |

若商品查询接口返回 403，需在[京东联盟开放平台](https://union.jd.com/openplatform)申请对应 API 权限。

## 拼多多多多进宝（真实 API）

密钥同样放在 `backend/application-local.yml`：

```yaml
zdsj:
  affiliate:
    mock: false
    pdd:
      client-id: YOUR_PDD_CLIENT_ID
      client-secret: YOUR_PDD_CLIENT_SECRET
      pid: YOUR_PDD_PID   # 推广位 ID，转链必填
```

环境变量：`PDD_CLIENT_ID`、`PDD_CLIENT_SECRET`、`PDD_PID`。

**开通步骤（简要）：**

1. 在 [拼多多开放平台](https://open.pinduoduo.com) 注册开发者，创建应用，获取 `client_id` / `client_secret`
2. 在 [多多进宝](https://jinbao.pinduoduo.com) 绑定该 `client_id`（应用管理 → 绑定多多客）
3. 在多多进宝创建推广位，得到 `pid`（形如 `123456_789012345`）
4. 确认已开通「多多客 API」权限包（商品搜索、详情、转链）

| 接口 | 用途 |
|---|---|
| `pdd.ddk.goods.search` | 关键词/商品链接搜索，解析分享链 |
| `pdd.ddk.goods.detail` | 按 goodsSign 查详情 |
| `pdd.ddk.goods.promotion.url.generate` | 生成 CPS 推广链接（需 pid） |

解析成功后 `itemId` 为平台返回的 **goodsSign**（加密 goodsId），不再使用 `pdd_ps_` 占位 id。

```bash
curl -X POST http://localhost:8080/api/link/parse \
  -H 'content-type: application/json' \
  -d '{"linkText":"https://mobile.yangkeduo.com/goods1.html?ps=FuXUG9Y3SE"}'
```

## 本地联调（mock 全开）

```bash
# 解析京东链接（mock）
curl -X POST http://localhost:8080/api/link/parse \
  -H 'content-type: application/json' \
  -d '{"linkText":"https://item.jd.com/100012345678.html iPhone 16 Pro"}'

# 商品分析（带资产库）
curl 'http://localhost:8080/api/product/analysis?platform=jd&item_id=100012345678&assets=%7B%22jdPlus%22%3Atrue%7D'
```

## 测试

```bash
./gradlew test
```
