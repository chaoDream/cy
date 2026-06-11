package com.zdsj.affiliate.provider

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.PddLinkParser
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.RateLimiter
import com.zdsj.affiliate.pdd.PddDdkClient
import com.zdsj.affiliate.pdd.PddDdkService
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 拼多多官方多多进宝提供商：包装现有 PddDdkService。
 * 未配置密钥时 supports=false，Gateway 自动跳过到降级链。
 */
@Component
class PddOfficialProvider(
    private val pddDdkClient: PddDdkClient,
    private val pddDdkService: PddDdkService,
    private val rateLimiter: RateLimiter,
) : AffiliateProvider {

    override fun name() = "pdd_official"

    override fun supports(platform: Platform) = platform == Platform.PDD && pddDdkClient.isConfigured()

    override fun extractItemId(platform: Platform, linkText: String): String? =
        if (platform == Platform.PDD) PddLinkParser.extractItemId(linkText) else null

    override fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem? {
        guardRate()
        return runCatching { pddDdkService.fetchItem(itemId) }.getOrNull()
    }

    override fun fetchFromShareText(linkText: String, ctx: AffiliateContext?): AffiliateItem? {
        if (!PddLinkParser.isPddShareText(linkText)) return null
        guardRate()
        return runCatching { pddDdkService.fetchFromShareText(linkText) }.getOrNull()
    }

    override fun search(ctx: AffiliateContext, keyword: String, limit: Int): List<AffiliateItem> {
        guardRate()
        return pddDdkService.search(keyword, limit)
    }

    override fun buildCpsLink(ctx: AffiliateContext, itemId: String): String? {
        if (!PddLinkParser.isGoodsSign(itemId)) return null
        // ctx.userKey 为已格式化的 custom_parameters（备案用户）；游客为 null（不触发预判）
        return pddDdkService.buildCpsLink(itemId, customParameters = ctx.userKey)
    }

    private fun guardRate() {
        if (!rateLimiter.tryAcquire("pdd", maxPerWindow = 600, window = Duration.ofMinutes(1))) {
            throw BizException(ErrorCode.AFFILIATE_RATE_LIMIT, "拼多多接口限频，请稍后重试")
        }
    }
}
