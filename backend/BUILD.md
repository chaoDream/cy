# 后端构建与运行

## 前置

- JDK 17+
- Gradle 8.7+（或先生成 wrapper）
- 本地依赖：`docker compose up -d`（在仓库根目录，启动 PostgreSQL + Redis）

## 首次：生成 Gradle Wrapper

仓库已包含 `gradle/wrapper/gradle-wrapper.properties`，但二进制 `gradle-wrapper.jar` 需本地生成一次：

```bash
cd backend
gradle wrapper --gradle-version 8.7
```

之后即可使用 `./gradlew`。

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
