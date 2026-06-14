package com.zdsj.config

import com.zdsj.affiliate.Platform
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "zdsj.jwt")
data class JwtProperties(
    val secret: String = "",
    val ttlSeconds: Long = 7776000,
)

@ConfigurationProperties(prefix = "zdsj.wechat")
data class WechatProperties(
    val appid: String = "",
    val secret: String = "",
    val subscribeTemplateId: String = "",
)

@ConfigurationProperties(prefix = "zdsj.affiliate")
data class AffiliateProperties(
    /** 兼容旧开关：true 时默认走 mock（provider 未显式配置时的回退语义） */
    val mock: Boolean = true,
    /** 各平台的提供商路由（维易 / 官方 / mock + 降级链） */
    val provider: ProviderRouting = ProviderRouting(),
    /** 维易 VEAPI 配置 */
    val veapi: Veapi = Veapi(),
    /** Provider 熔断配置 */
    val breaker: Breaker = Breaker(),
    /** Gateway 缓存配置 */
    val cache: Cache = Cache(),
    val pdd: Pdd = Pdd(),
    val jd: Jd = Jd(),
) {
    data class Pdd(val clientId: String = "", val clientSecret: String = "", val pid: String = "")
    data class Jd(
        val appKey: String = "",
        val appSecret: String = "",
        /** 联盟媒体 ID（unionId） */
        val unionId: String = "",
        /** 推广站点 ID（可选；未配置时用 byunionid 转链） */
        val siteId: String = "",
        /** 推广位 ID（可选） */
        val positionId: String = "",
    )

    /** 按平台指定提供商与降级链 */
    data class ProviderRouting(
        val jd: PlatformRoute = PlatformRoute(),
        val pdd: PlatformRoute = PlatformRoute(),
    )

    data class PlatformRoute(
        /** 主提供商：veapi | jd_official | pdd_official | mock */
        val primary: String = "mock",
        /** 降级链，按序尝试 */
        val fallback: List<String> = emptyList(),
        /** 主源熔断时是否自动切到 fallback（false=纯手动） */
        val autoFailover: Boolean = true,
    )

    data class Veapi(
        val baseUrl: String = "https://api.veapi.cn",
        val vekey: String = "",
        /** 可选加签密钥；为空则不加签 */
        val secret: String = "",
        val jd: VeapiJd = VeapiJd(),
        val pdd: VeapiPdd = VeapiPdd(),
    )

    data class VeapiJd(
        /** self（自有联盟号）| public（维易公共号） */
        val authMode: String = "self",
        val sessionkey: String = "",
        /** CPS 归因：你自己的联盟媒体 ID */
        val unionId: String = "",
        val positionId: String = "",
        /** 京东单品转链场景：2=item.jd.com/数字SKU（需联盟 sceneId=2 权限） */
        val sceneId: Int = 2,
        /** 1=长链 clickURL，2=短链 u.jd.com，3=长短都返回（维易 jd_prombyuid） */
        val chainType: Int = 1,
    )

    data class VeapiPdd(
        val authMode: String = "self",
        val sessionkey: String = "",
        /** CPS 归因：多多进宝推广位 PID（维易 pdd_goodssearch 必填，须已在维易/拼多多备案） */
        val pid: String = "",
        /**
         * 游客解析链接时的 custom_parameters（JSON 或纯 uid 字符串）。
         * 若备案时只绑了 pid、未绑 custom_parameters，可留空。
         */
        val defaultCustomParameters: String = "",
    )

    data class Breaker(
        /** 连续失败达到阈值则熔断 */
        val failThreshold: Int = 3,
        /** 熔断冷却时长（秒） */
        val cooldownSeconds: Long = 60,
    )

    data class Cache(
        val itemTtlSeconds: Long = 600,
        val cpsTtlSeconds: Long = 21600,
        val emptyTtlSeconds: Long = 45,
        val searchTtlSeconds: Long = 120,
    )
}

/**
 * 政府国补规则表（按地区维护，PRD §5.1 资产库国补）。
 * 国补无实时 API，由运营在 application.yml 维护各地区比例/封顶/门槛。
 */
@ConfigurationProperties(prefix = "zdsj.subsidy")
data class GovSubsidyProperties(
    val enabled: Boolean = false,
    val regions: List<Region> = emptyList(),
) {
    data class Region(
        /** 与 user_profile.assets 中 govSubsidyRegion 精确匹配 */
        val region: String = "",
        /** 补贴比例，如 0.15 表示 15% */
        val rate: BigDecimal = BigDecimal.ZERO,
        /** 封顶金额（元）；<=0 表示不封顶 */
        val cap: BigDecimal = BigDecimal.ZERO,
        /** 享补价格门槛（元）：标价低于此值不补；<=0 表示无门槛 */
        val minPrice: BigDecimal = BigDecimal.ZERO,
        val enabled: Boolean = true,
    )

    /** 命中地区规则；未开启 / 无地区 / 未配置时返回 null */
    fun match(region: String?): Region? {
        if (!enabled || region.isNullOrBlank()) return null
        return regions.firstOrNull { it.enabled && it.region == region }
    }
}

@ConfigurationProperties(prefix = "zdsj.ai")
data class AiProperties(
    val mock: Boolean = true,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val modelHigh: String = "deepseek-v4-flash",
    val modelFast: String = "deepseek-v4-flash",
)

@ConfigurationProperties(prefix = "zdsj.watch")
data class WatchProperties(
    val enabled: Boolean = true,
    val pollHotCron: String = "0 0 */2 * * ?",
    val pollUserCron: String = "0 30 */4 * * ?",
    val pollNormalCron: String = "0 0 4 * * ?",
)

/** 冷启动种子采价（YAML 清单，见 zdsj.price-seed） */
@ConfigurationProperties(prefix = "zdsj.price-seed")
data class PriceSeedProperties(
    val enabled: Boolean = false,
    /** 每天定时采价（cron） */
    val pollCron: String = "0 0 6 * * ?",
    /** 按名称搜索时每个平台取前 N 条候选 */
    val searchLimit: Int = 10,
    /** 京东批量 promotiongoodsinfo 每批 SKU 数（维易/官方上限 100） */
    val jdBatchSize: Int = 50,
    /** 拼多多批量 goods_sign_list 每批数量 */
    val pddBatchSize: Int = 20,
    val items: List<SeedItem> = emptyList(),
    /** 在静态 YAML 基础上，按品牌产品线自动追加比现有种子更新的机型 */
    val autoLatest: AutoLatest = AutoLatest(),
) {
    data class AutoLatest(
        val enabled: Boolean = false,
        /** 每个品牌最多追加几条动态种子 */
        val maxPerBrand: Int = 2,
        /** 动态种子总数上限（叠加在 40 条静态之后） */
        val maxTotal: Int = 15,
        /** 发现最新机型时的搜索平台 */
        val platform: String = "jd",
        val searchLimit: Int = 15,
        /** 动态列表缓存小时数，避免同一天内重复搜索 */
        val cacheHours: Int = 24,
        /** 自动刷新 cron（默认每天 05:55，早于采价 06:00） */
        val refreshCron: String = "0 55 5 * * ?",
    )

    data class SeedItem(
        /** 商品名称/搜索词（推荐）：自动在京东+拼多多搜索 */
        val name: String = "",
        /** 京东数字 SKU：配置后跳过搜索，走批量 promotiongoodsinfo 直查 */
        val jdSkuId: String = "",
        /** 拼多多 goods_sign：配置后跳过搜索，走批量 goodssearch 直查 */
        val pddGoodsSign: String = "",
        /** 名称模式搜索的平台，默认京东+拼多多 */
        val platforms: List<String> = listOf("jd", "pdd"),
        val note: String? = null,
        val enabled: Boolean = true,
    ) {
        fun isNameMode(): Boolean = name.isNotBlank()

        fun directItemId(platform: Platform): String? = when (platform) {
            Platform.JD -> jdSkuId.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            Platform.PDD -> pddGoodsSign.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun enabledItems(): List<SeedItem> = items.filter { it.enabled && it.isNameMode() }
}

/** 品牌 / 型号目录定时同步（从京东、拼多多搜索充盈 catalog 表） */
@ConfigurationProperties(prefix = "zdsj.catalog-sync")
data class CatalogSyncProperties(
    val enabled: Boolean = false,
    /** 默认定时：每周一 03:00 */
    val pollCron: String = "0 0 3 ? * MON",
    val searchLimit: Int = 20,
    val platforms: List<String> = listOf("jd", "pdd"),
    /** 固定搜索词 */
    val keywords: List<String> = listOf(
        "智能手机", "5G手机", "iPhone", "华为 Mate", "小米", "一加", "vivo", "OPPO", "荣耀",
    ),
    /** 为 true 时，每次同步会把已入库品牌名也作为搜索词（上限 max-brand-keywords） */
    val includeKnownBrands: Boolean = true,
    val maxBrandKeywords: Int = 15,
) {
    fun resolvedKeywords(catalogReader: com.zdsj.sku.SkuCatalogReader): List<String> { // injected at runtime
        val base = keywords.map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        if (includeKnownBrands) {
            catalogReader.activeBrandNames()
                .take(maxBrandKeywords)
                .forEach { base.add(it) }
        }
        return base.toList()
    }
}
