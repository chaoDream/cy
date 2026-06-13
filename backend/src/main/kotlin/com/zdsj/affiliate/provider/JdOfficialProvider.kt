package com.zdsj.affiliate.provider

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.RateLimiter
import com.zdsj.affiliate.jd.JdUnionClient
import com.zdsj.affiliate.jd.JdUnionService
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 京东官方联盟提供商：包装现有 JdUnionService（含 JdImageResolver 兜底）。
 * 未配置 AppKey 时 supports=false，Gateway 自动跳过到降级链。
 */
@Component
class JdOfficialProvider(
    private val jdUnionClient: JdUnionClient,
    private val jdUnionService: JdUnionService,
    private val rateLimiter: RateLimiter,
) : AffiliateProvider {

    override fun name() = "jd_official"

    override fun supports(platform: Platform) = platform == Platform.JD && jdUnionClient.isConfigured()

    override fun extractItemId(platform: Platform, linkText: String): String? =
        if (platform == Platform.JD) JdLinkParser.extractItemId(linkText) else null

    override fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem? {
        guardRate()
        return runCatching { jdUnionService.fetchBySkuId(itemId) }.getOrNull()
    }

    override fun fetchFromShareText(linkText: String, ctx: AffiliateContext?): AffiliateItem? {
        if (JdLinkParser.extractItemId(linkText) == null) return null
        guardRate()
        return runCatching { jdUnionService.fetchFromShareText(linkText) }.getOrNull()
    }

    override fun search(ctx: AffiliateContext, keyword: String, limit: Int): List<AffiliateItem> {
        guardRate()
        return jdUnionService.search(keyword, limit)
    }

    override fun fetchEliteGoods(ctx: AffiliateContext, eliteId: Int, limit: Int): List<AffiliateItem> {
        guardRate()
        return jdUnionService.jingfen(eliteId, limit)
    }

    override fun fetchMaterialRecommend(ctx: AffiliateContext, eliteId: Int, limit: Int): List<AffiliateItem> {
        guardRate()
        return jdUnionService.materialRecommend(eliteId, limit, ctx.userKey)
    }

    override fun buildCpsLink(ctx: AffiliateContext, itemId: String): String? =
        jdUnionService.buildCpsLink(itemId)

    private fun guardRate() {
        if (!rateLimiter.tryAcquire("jd", maxPerWindow = 600, window = Duration.ofMinutes(1))) {
            throw BizException(ErrorCode.AFFILIATE_RATE_LIMIT, "京东接口限频，请稍后重试")
        }
    }
}
