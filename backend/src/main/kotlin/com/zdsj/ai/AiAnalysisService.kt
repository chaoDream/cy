package com.zdsj.ai

import com.zdsj.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration

/**
 * AI 买/等建议（PRD §5.4 / §9.3）。
 * 关键红线：事实数据全部来自工具（价格事实由规则引擎与价格服务提供），
 * AI 只负责理解/编排/解释，绝不编价。mock 模式下用确定性规则推理，
 * 真实模式下走大模型 Function Calling（事实仍来自工具）。
 */
data class AiInput(
    val title: String,
    val standardSku: String?,
    val platform: String,
    val shopType: String?,
    val currentFinalPrice: BigDecimal,
    val low30: BigDecimal?,
    val low90: BigDecimal?,
    val nearLow: Boolean,
    val fakeDiscount: Boolean,
    val riskTags: List<String>,
    val discountBreakdown: List<String>,
)

enum class Conclusion(val label: String) {
    BUY("建议买"), WAIT("可以等"), CAUTION("谨慎买"), AVOID("不建议买")
}

data class AiResult(
    val conclusion: String,
    val conclusionLabel: String,
    val reasons: List<String>,
    val riskHint: String?,
    val confidence: String,
)

@Service
class AiAnalysisService(
    private val props: AiProperties,
    private val recordRepo: AiAnalysisRecordRepository,
    private val redis: RedisTemplate<String, Any>,
    private val llmClient: LlmClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun analyze(skuId: Long?, input: AiInput): AiResult {
        val cacheKey = "ai:analysis:${input.platform}:${skuId ?: input.title.hashCode()}:${input.currentFinalPrice}"
        readCache(cacheKey)?.let { return it }

        val result = try {
            resolveRecommendation(input)
        } catch (e: Exception) {
            log.warn("AI 建议生成异常，降级到规则推理: {}", e.message)
            ruleBased(input)
        }

        persistBestEffort(skuId, input, result)
        writeCache(cacheKey, result)
        return result
    }

    /** 跳过 LLM，直接返回规则推理结论（供接口失败兜底）。 */
    fun ruleBasedRecommendation(input: AiInput): AiResult = ruleBased(input)

    private fun persistBestEffort(skuId: Long?, input: AiInput, result: AiResult) {
        runCatching {
            recordRepo.save(
                AiAnalysisRecord(
                    skuId = skuId,
                    platform = input.platform,
                    inputJson = mutableMapOf(
                        "title" to input.title,
                        "currentFinalPrice" to input.currentFinalPrice,
                        "low30" to input.low30,
                        "low90" to input.low90,
                        "riskTags" to input.riskTags,
                    ),
                    conclusion = result.conclusion,
                    reasons = result.reasons.toMutableList(),
                    confidence = result.confidence,
                ),
            )
        }.onFailure { log.warn("AI 记录写入失败（不影响返回）: {}", it.message) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readCache(key: String): AiResult? =
        runCatching { redis.opsForValue().get(key) as? AiResult }.getOrNull()

    private fun writeCache(key: String, result: AiResult) {
        runCatching { redis.opsForValue().set(key, result, Duration.ofHours(6)) }
    }

    /** mock / 无 key 直接走规则；否则调大模型，无有效返回时自动降级 ruleBased。 */
    private fun resolveRecommendation(input: AiInput): AiResult {
        if (props.mock || props.apiKey.isBlank()) {
            return ruleBased(input)
        }
        val llm = llmClient.analyze(input)
        if (llm != null) return llm
        log.info("大模型无有效返回，降级到规则推理")
        return ruleBased(input)
    }

    /** 确定性规则推理（mock / 降级）。结论可追溯到价格事实。 */
    internal fun ruleBased(input: AiInput): AiResult {
        val reasons = mutableListOf<String>()
        var conclusion = Conclusion.CAUTION

        val hasHardRisk = input.riskTags.any {
            it.contains("翻新") || it.contains("非国行") || it.contains("套装")
        }
        val thirdParty = input.shopType == "thirdparty"

        when {
            hasHardRisk -> {
                conclusion = Conclusion.AVOID
                reasons += "该商品存在风险标签（${input.riskTags.joinToString("、")}），不建议直接比价下单"
            }
            input.fakeDiscount -> {
                conclusion = Conclusion.WAIT
                reasons += "检测到先涨后降，当前折扣可能不实，建议再等"
            }
            input.nearLow -> {
                conclusion = Conclusion.BUY
                reasons += "当前参考到手价已接近近 30 天低价，价格处于较优区间"
            }
            input.low30 != null && input.currentFinalPrice > input.low30 -> {
                val diff = input.currentFinalPrice - input.low30
                conclusion = if (diff > BigDecimal("200")) Conclusion.WAIT else Conclusion.CAUTION
                reasons += "当前参考到手价比近 30 天低价高 ${diff.toPlainString()} 元"
            }
            else -> {
                conclusion = Conclusion.CAUTION
                reasons += "历史价格数据积累中，暂以当前公开优惠后价格参考"
            }
        }

        if (thirdParty) reasons += "该商品为第三方店铺，需确认发票与官方保修"
        if (input.discountBreakdown.isNotEmpty() && reasons.size < 3) {
            reasons += "已纳入优惠：${input.discountBreakdown.take(3).joinToString("、")}"
        }

        val riskHint = if (input.riskTags.isNotEmpty()) {
            "风险提示：${input.riskTags.joinToString("、")}；如不急，建议关注京东自营或拼多多百亿补贴官方渠道"
        } else null

        val confidence = when {
            hasHardRisk || input.fakeDiscount -> "高"
            input.low30 != null -> "中"
            else -> "低"
        }

        return AiResult(
            conclusion = conclusion.name.lowercase(),
            conclusionLabel = conclusion.label,
            reasons = reasons.take(3),
            riskHint = riskHint,
            confidence = confidence,
        )
    }
}
