# 联盟接入层重构 · 多 Provider 架构（维易 VEAPI + 官方）最终方案

> 目标：把「数据来自哪个 API（维易 / 京东官方 / 拼多多官方 / mock）」与业务逻辑彻底解耦，
> 通过配置切换提供商、统一数据模型、自动降级与缓存，支撑 CPS 商品查询 / 转链 / 盯价闭环。

---

## 1. 设计原则

1. **完全解耦**：业务层只依赖 `AffiliateGateway` 和统一模型，永不感知底层是维易还是官方。
2. **策略模式**：每个提供商实现同一 `AffiliateProvider` 接口。
3. **配置驱动**：按平台选择提供商与降级链，切换 0 改码。
4. **统一数据模型**：所有 API 输出统一转换为 `AffiliateItem`，并用 `AffiliateResult` 携带来源/缓存/降级元信息。

---

## 2. 现状与改造点（基于现有代码）

现有 `affiliate` 层抽象维度是「按平台」（`AffiliateAdapter` → `JdAdapter`/`PddAdapter`），每个平台只有官方一种实现，且业务层已穿透抽象：

- `product/AnalysisService.kt` 直接注入 `JdUnionService`
- `product/ProductImageController.kt` 直接注入 `JdUnionService` / `JdImageResolver` / `PddDdkService`
- `watch/WatchPollingJob.kt` 通过 `AffiliateRegistry.get(platform)` 调用

**改造核心**：引入「提供商（Provider）」维度，新增 `AffiliateGateway` 门面收口所有业务调用；现有 `JdUnionService` / `PddDdkService` / `MockCatalog` / `JdImageResolver` 不删，降级为被 Provider 包装的「底层数据源」。

可复用资产：`AffiliateItem`（统一模型）、`RateLimiter`（Redis 限频）、`JdLinkParser`/`PddLinkParser`、`MockCatalog`。
现有依赖：Spring Boot 3.2.5、Redis（已用）、Jackson。**无** Actuator/Micrometer、**无** Resilience4j（监控/熔断采用轻量自研，见 §7）。

---

## 3. 分层架构

```
业务层   AnalysisService / WatchPollingJob / ProductImageController
            │ 只依赖 ↓
─────────────────────────────────────────────────────────────
门面层   AffiliateGateway        ← 业务唯一入口；缓存 + 降级链 + 熔断 + 监控
            │ 按配置选 provider ↓
─────────────────────────────────────────────────────────────
策略层   AffiliateProvider (interface)
         ├── VeapiProvider        （维易，新增；京东+拼多多通吃）
         ├── JdOfficialProvider   （包装 JdUnionService）
         ├── PddOfficialProvider  （包装 PddDdkService）
         └── MockProvider         （包装 MockCatalog）
            │ 各自把原始响应转成 ↓
─────────────────────────────────────────────────────────────
模型层   AffiliateItem / ParsedLink / CpsLinkResult / AffiliateResult
```

---

## 4. 统一数据模型与接口

### 4.1 数据模型（模型层，provider 无关）

```kotlin
// 复用现有 AffiliateItem（不动）：商品标准结构

enum class AuthMode { SELF, PUBLIC }   // 维易自有联盟号 / 维易公共账号

/** 调用上下文：用对象承载入参，新增字段不破坏方法签名（建议一） */
data class AffiliateContext(
    val platform: Platform,
    val bypassCache: Boolean = false,     // 盯价等需新鲜数据时置 true
    val authMode: AuthMode = AuthMode.SELF,
    val traceId: String? = null,
)

/** 统一返回包装：携带来源/缓存/降级标记（建议一 + 补2/4/5 的落点） */
data class AffiliateResult<T>(
    val data: T?,
    val source: String,          // 实际命中的 provider 名
    val fromCache: Boolean = false,
    val degraded: Boolean = false,
    val warnings: List<String> = emptyList(),
)
```

### 4.2 策略接口（策略层）

```kotlin
interface AffiliateProvider {
    fun name(): String                       // "veapi" / "jd_official" / "pdd_official" / "mock"
    fun supports(platform: Platform): Boolean // 未配置密钥时返回 false → gateway 自动跳过
    fun healthy(platform: Platform): Boolean = true   // 主动健康检查可选实现（建议二）

    fun extractItemId(platform: Platform, linkText: String): String?
    fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem?
    fun fetchFromShareText(linkText: String): AffiliateItem?   // 内部识别平台
    fun search(ctx: AffiliateContext, keyword: String, limit: Int = 10): List<AffiliateItem>
    fun buildCpsLink(ctx: AffiliateContext, itemId: String): String?
}
```

### 4.3 门面（业务唯一依赖）

```kotlin
@Service
class AffiliateGateway(
    providers: List<AffiliateProvider>,
    private val props: AffiliateProperties,
    private val cache: AffiliateCache,        // §5 Redis 缓存
    private val breaker: ProviderCircuitBreaker, // §7 熔断
    private val metrics: AffiliateMetrics,    // §7 监控
) {
    private val byName = providers.associateBy { it.name() }

    fun fetchItem(platform: Platform, itemId: String, bypassCache: Boolean = false): AffiliateResult<AffiliateItem>
    fun buildCpsLink(platform: Platform, itemId: String): AffiliateResult<String>
    fun search(platform: Platform, keyword: String, limit: Int = 10): AffiliateResult<List<AffiliateItem>>
    fun fetchFromShareText(linkText: String): AffiliateResult<AffiliateItem>
    fun detect(linkText: String): Pair<Platform, String>?
    fun resolveImage(platform: Platform, itemId: String): String?   // 供图片接口收口
}
```

> 业务层只 import `AffiliateGateway` + `AffiliateItem`/`AffiliateResult`。

---

## 5. 缓存（补充一）

放在 **Gateway 层**（不在 provider 内），复用现有 `RedisTemplate`。

| 数据 | Key | TTL | 说明 |
|------|-----|-----|------|
| 商品详情 | `aff:item:{provider}:{platform}:{itemId}` | 5–30 min | 价格类，盯价路径 `bypassCache=true` |
| 转链 | `aff:cps:{provider}:{platform}:{itemId}` | 可较长（如 6h） | 相对稳定 |
| 搜索 | `aff:search:{provider}:{platform}:{md5(kw)}` | 1–5 min | 或不缓存 |
| 空结果 | 同上 + 标记 | 30–60s | 防穿透 |

要点：① key 带 `provider` 维度，切换提供商不读脏数据；② 支持 stale-while-error（主源失败时可用过期缓存兜底，见 §6）。

---

## 6. 失败降级链（补充二）

降级在 Gateway 集中实现，按场景分级：

```
fetchItem(platform, itemId):
  1) 主 provider（provider.{platform}.primary）
  2) 缓存命中（含 stale-while-error）
  3) fallback provider 链（provider.{platform}.fallback: [...]）
  4) 平台页兜底（仅图片/标题，rawPrice=0 + uncertaintyFlag）
  5) 返回部分数据 + degraded=true（不直接抛错，除非完全无数据）
```

分场景策略：

| 场景 | 失败处理 |
|------|----------|
| `buildCpsLink` | 回退「复制官方商品链接」，标 degraded，不致命 |
| `fetchItem` 无价 | 返回 `rawPrice=0` + `priceInfo.uncertaintyFlags`（已有字段） |
| 盯价轮询 | 跳过本轮，不发错误提醒，不改 watch 状态 |
| 图片 | gateway.resolveImage 失败 → JdImageResolver/PC 页兜底 → 占位图 |

每次降级写结构化日志（`provider`, `platform`, `reason`, `traceId`），供 §7 统计。

配置：
```yaml
provider:
  jd:  { primary: veapi, fallback: [jd_official, mock], autoFailover: true }
  pdd: { primary: veapi, fallback: [pdd_official, mock], autoFailover: true }
```

---

## 7. 监控、健康度与自动降级（补充四 + 建议二）

拆为「立即做的轻量自动降级」与「后置的全量 Metrics」。

### 7.1 自动降级 / 熔断（立即做，Redis 自研）

```
计数 key: aff:health:{provider}:{platform}
连续失败 ≥ N（默认3）→ 置 open + 冷却 TTL（默认60s）
冷却期内 gateway 直接走 fallback（前提 autoFailover=true）
冷却到期 → half-open，放一个探测请求；成功则 close，失败则再 open
状态翻转打 WARN 日志（可挂告警钩子）
```

- 不引入 Resilience4j，Redis 计数 + 状态足够 MVP。
- **手动配置优先级**：`primary` 是强制指定；熔断只在「主挂了且 `autoFailover=true`」时临时切备；`autoFailover=false` 回到纯手动。

### 7.2 主动健康检查（建议二，可选）

挂现有 Quartz，低频（如 5 min）对各 provider 探一个已知可查 SKU，结果写入 `aff:health:*`，让熔断冷启动更准。MVP 可先只做被动，不做主动探测。

### 7.3 可观测性 Metrics（后置）

成功率 / P95 耗时等，待运维需要时再加 `spring-boot-starter-actuator + micrometer-registry-prometheus`，在 gateway 调用处包计时/计数。**非 MVP 阻塞项**。

---

## 8. 维易接入（VeapiClient / VeapiProvider / VeapiMapper）

### 8.1 客户端与签名（补充三）

- `VeapiClient`：`base-url` + 公共参数 `vekey`（可选 `secret` 加签），HTTP GET/POST，统一 JSON 解包。
- **签名单测优先**：新增 `VeapiSignerTest`，用维易文档示例参数做固定输入/输出断言（不依赖网络），比照现有 `JdUnionSignerTest`；再加 `@EnabledIfEnvironmentVariable` 的 live 冒烟测试。

### 8.2 接口映射

| 能力 | 维易接口 | 映射 |
|------|----------|------|
| 链接/口令解析 + 转链 | 全网万能转链 `/global/unionhc`（返回 `plat_type`） | `fetchFromShareText` + `buildCpsLink` |
| 京东商品 | 维易京东商品接口 | `fetchItem(JD)` / `search` |
| 拼多多商品 | 维易多多进宝接口（goodsSign） | `fetchItem(PDD)` / `search` |

`plat_type`（1淘宝/2拼多多/3京东/4唯品会/5抖客）→ 内部 `Platform` 的转换收敛在 `VeapiMapper`。

### 8.3 Mapper 校验（补充五，轻量）

`VeapiMapper` 产出 `AffiliateItem` 后过 `validate()`：

- **硬校验**：`title`、`platformItemId` 非空，否则视为解析失败走降级。
- **软校验**：`rawPrice<=0` / `imageUrl` 空 → 不拦截，加 `warnings` + WARN 日志（字段改名预警）。
- 连续 mapper 失败计入 §7 健康度。
- 单测用脱敏真实响应样例做固定断言，字段一变测试即红。

### 8.4 鉴权模式（补充六）

支持两种，做成配置：

```yaml
veapi:
  jd:
    auth-mode: self      # self（你自己的联盟号）| public（维易公共号）
    sessionkey: ...      # self 必填
    union-id: ...        # CPS 归因（self）
    position-id: ...
  pdd:
    auth-mode: self
    sessionkey: ...
    pid: ...
```

> ⚠️ 商业前置确认：**public 模式下 CPS 佣金归属可能不在你账户**，接入前必须与维易确认结算到谁。技术上两种都支持，由 `auth-mode` 切换。

---

## 9. 配置 Schema（最终）

```yaml
zdsj:
  affiliate:
    provider:
      jd:  { primary: veapi, fallback: [jd_official, mock], autoFailover: true }
      pdd: { primary: veapi, fallback: [pdd_official, mock], autoFailover: true }
    breaker:
      fail-threshold: 3
      cooldown-seconds: 60
    cache:
      item-ttl-seconds: 600
      cps-ttl-seconds: 21600
      empty-ttl-seconds: 45
    veapi:
      base-url: ${VEAPI_BASE:https://api.veapi.cn}
      vekey: ${VEAPI_KEY:}
      secret: ${VEAPI_SECRET:}
      jd:  { auth-mode: self, sessionkey: ${VEAPI_JD_SESSION:}, union-id: ${JD_UNION_ID:}, position-id: ${JD_PID:} }
      pdd: { auth-mode: self, sessionkey: ${VEAPI_PDD_SESSION:}, pid: ${PDD_PID:} }
    jd:  { app-key: ..., app-secret: ..., union-id: ..., site-id: ..., position-id: ... }   # 官方，保留
    pdd: { client-id: ..., client-secret: ..., pid: ... }                                   # 官方，保留
```

> 兼容：旧 `affiliate.mock=true` 过渡期映射为 `provider.*.primary=mock`，迁移完成后移除。

---

## 10. 文件改动清单

### 新增
| 文件 | 作用 |
|------|------|
| `affiliate/provider/AffiliateProvider.kt` | 策略接口 + `AffiliateContext`/`AffiliateResult`/`AuthMode` |
| `affiliate/provider/AffiliateGateway.kt` | 门面：缓存 + 降级 + 熔断 + 监控 |
| `affiliate/provider/AffiliateCache.kt` | Redis 读穿缓存 |
| `affiliate/provider/ProviderCircuitBreaker.kt` | Redis 轻量熔断 |
| `affiliate/provider/AffiliateMetrics.kt` | 调用计数/耗时（先打日志，后接 Micrometer） |
| `affiliate/provider/MockProvider.kt` | 包装 `MockCatalog` |
| `affiliate/provider/JdOfficialProvider.kt` | 包装 `JdUnionService` |
| `affiliate/provider/PddOfficialProvider.kt` | 包装 `PddDdkService` |
| `affiliate/provider/veapi/VeapiClient.kt` | 维易 HTTP + 签名 |
| `affiliate/provider/veapi/VeapiProvider.kt` | 维易策略 |
| `affiliate/provider/veapi/VeapiMapper.kt` | 维易响应 → `AffiliateItem` + 校验 |
| `config` 增 `VeapiProperties`/`ProviderRouting`/`BreakerProperties`/`CacheProperties` | 配置类 |
| 测试 `VeapiSignerTest` / `VeapiMapperTest` / `AffiliateGatewayTest` | 单测 |

### 改造（收口耦合）
| 文件 | 改动 |
|------|------|
| `product/AnalysisService.kt` | 去掉 `JdUnionService`，注入 `AffiliateGateway` |
| `product/ProductImageController.kt` | 去掉三个直注，改 `gateway.resolveImage` |
| `watch/WatchPollingJob.kt` | `registry.get(...)` → `gateway.fetchItem(..., bypassCache=true)` |
| `config/AppProperties.kt` | 新增配置类 |
| `application.yml` / `application-local.yml.example` | provider 路由 + veapi 段 |

### 保留（被包装，不对业务暴露）
`jd/*`、`pdd/*`、`MockCatalog`、`RateLimiter`、`*LinkParser`、`AffiliateItem`。

### 过渡
`AffiliateAdapter` / `AffiliateRegistry` 标 `@Deprecated`，PR2 内部可复用，业务全切 gateway 后于 PR4 删除。

---

## 11. 落地顺序（4 个 PR）

| PR | 范围 | 验收 |
|----|------|------|
| **PR1 骨架** | `AffiliateProvider`(Context/Result) + `Gateway` + `MockProvider`；业务层收口；配置预留 fallback/authMode/autoFailover | 全 mock 下功能不变，证明解耦成立 |
| **PR2 官方包装** | `JdOfficialProvider` / `PddOfficialProvider` | `primary: jd_official` 复现今日官方行为 |
| **PR3 维易+韧性** | `VeapiClient`/`Provider`/`Mapper`、签名单测、Redis 缓存、轻量熔断+自动降级、mapper 校验、auth-mode | `primary: veapi` 实测拿到价/图/CPS；熔断可切回官方 |
| **PR4 收尾** | 删旧抽象、补单测、（可选）Actuator/Prometheus | 旧 `AffiliateAdapter` 移除，CI 绿 |

---

## 12. 风险与前置确认

| 项 | 说明 |
|----|------|
| 维易授权 | self 模式仍需在维易后台绑定你自己的京东/拼多多授权（sessionkey/PID） |
| 佣金归属 | public 模式佣金可能不归你 —— **接入前与维易书面确认** |
| 调用上限 | 维易套餐有 QPS/日调用上限；盯价轮询需估算 SKU 量，靠缓存削峰 |
| 字段漂移 | 维易/官方字段会变 → mapper 校验 + 样例单测兜底 |
| 熔断与手动冲突 | `autoFailover=false` 时回到纯手动，避免与强制指定冲突 |
| 合规 | 数据来源在隐私说明中标注；仅用联盟类接口，不引入爬取能力 |

---

## 13. 验收清单（Definition of Done）

- [x] 业务层（Analysis/Watch/Image）无任何 `JdUnionService`/`PddDdkService`/`Veapi*` 直接依赖（统一走 `AffiliateGateway`）。
- [x] 改 `application.yml` 即可在 veapi / 官方 / mock 间切换，无需改码。
- [x] mapper 对样例响应断言通过（`VeapiMapperTest`）；Gateway 降级/熔断/缓存逻辑单测通过（`AffiliateGatewayTest`）。
- [x] 主 provider 连续失败可自动切 fallback，并在冷却后恢复重试。
- [x] 命中缓存路径有 `fromCache=true`；盯价路径 `bypassCache=true` 不读旧价。
- [x] 降级路径有结构化日志，且对用户表现为「诚实展示」而非报错。

---

## 14. 落地说明（As-Built，2026-06-04）

> 实现与计划基本一致，以下为按维易官方文档核对后的关键修正：

### 14.1 鉴权：维易无 HMAC 签名
核对维易《怎样接入》后确认：公共参数仅 `vekey`（必填）+ `secret`（可选，明文加强参数），**不需要复杂 sign 算法**。
因此未引入 `VeapiSigner`，`VeapiClient` 仅做 `vekey`(+`secret`) 拼接；如官方后续要求签名，集中在 `VeapiClient.appendAuth` 补充即可，业务层无感。
单测相应改为 `VeapiMapperTest` + `AffiliateGatewayTest`（覆盖映射校验、降级链、熔断、缓存命中）。

### 14.2 已对接的维易端点
| 用途 | 端点 | 关键入参 |
|------|------|----------|
| 京东·按 SKU 取价/图/标题 | `/jd/promotiongoodsinfo` | `skuIds` |
| 京东·关键词搜索 | `/jd/jd_search` | `keyword` / `pageSize` |
| 京东·单品转链 | `/jd/jd_prombyuid` | `materialId`、`unionId`（必填）、`sceneId=2`、`chainType=1`（长链 clickURL） |
| 拼多多·详情/搜索 | `/pdd/pdd_goodssearch` | `goods_sign_list` / `keyword`（数字 ID 加 `usenumid=1`） |
| 拼多多·转链 | `/pdd/pdd_promlink` | `goods_sign_list`、`p_id` |

> 返回信封统一为 `{"error":"0","msg":"...","data":{...}}`，`error=="0"` 即成功；`data` 形态兼容数组/含 `goods_list` 对象/单对象。

### 14.3 配置切换（默认仍为 mock，安全）
```yaml
zdsj.affiliate.provider.jd:   { primary: veapi, fallback: [jd_official, mock], auto-failover: true }
zdsj.affiliate.provider.pdd:  { primary: veapi, fallback: [mock],             auto-failover: true }
zdsj.affiliate.veapi: { vekey: <你的vekey>, jd.auth-mode: self, pdd.auth-mode: self }
```
购卡并填入 `VEAPI_VEKEY` 后，把 `JD_PROVIDER_PRIMARY=veapi`、`PDD_PROVIDER_PRIMARY=veapi` 即可灰度切换；维易异常会自动按 fallback 链回落官方/mock。

### 14.4 新增文件
- `affiliate/provider/`：`AffiliateProvider`、`AffiliateGateway`、`AffiliateCache`、`ProviderCircuitBreaker`、`AffiliateMetrics`、`MockProvider`、`JdOfficialProvider`、`PddOfficialProvider`
- `affiliate/veapi/`：`VeapiClient`、`VeapiMapper`、`VeapiProvider`
- 删除：`affiliate/Adapters.kt`（旧 `JdAdapter/PddAdapter/AffiliateRegistry`）、`AffiliateAdapter` 接口（保留 `Platform`/`AffiliateItem`）。
