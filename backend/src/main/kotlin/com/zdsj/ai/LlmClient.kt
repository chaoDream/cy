package com.zdsj.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 大模型客户端（OpenAI 兼容 Chat Completions，默认 DeepSeek）。
 *
 * 设计要点（PRD §9.3 防幻觉）：
 * - 事实（价格/历史价/风险）作为结构化输入喂给模型，模型只做理解与解释，不算价。
 * - 复杂促销/SKU 解析用高精度模型(modelHigh)，常规走便宜快模型(modelFast)。
 * - 无有效响应时返回 null，由 [AiAnalysisService] 降级到 ruleBased。
 */
@Component
class LlmClient(
    private val props: AiProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient: RestClient by lazy {
        RestClient.builder().baseUrl(props.baseUrl).build()
    }

    /** 调用大模型；无有效数据时返回 null，触发规则兜底。 */
    fun analyze(input: AiInput): AiResult? {
        return try {
            val content = requestCompletion(input) ?: return null
            parseAiResult(content)
        } catch (e: Exception) {
            log.warn("大模型调用失败，将降级到规则推理: {}", e.message)
            null
        }
    }

    private fun requestCompletion(input: AiInput): String? {
        val system = """
            你是「真到手价」的购物决策助手。只能基于我给出的结构化事实做判断，禁止编造或推算价格。
            输出 JSON：{"conclusion":"buy|wait|caution|avoid","reasons":["≤3条"],"riskHint":"","confidence":"高|中|低"}。
            敢于输出"不建议买"。不得使用"全网绝对最低价"等绝对化表述。
        """.trimIndent()
        val userMsg = objectMapper.writeValueAsString(input)
        val body = mapOf(
            "model" to props.modelHigh,
            "temperature" to 0.2,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to userMsg),
            ),
        )
        val resp = restClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer ${props.apiKey}")
            .body(body)
            .retrieve()
            .body(String::class.java)
        if (resp.isNullOrBlank()) {
            log.warn("大模型返回空 HTTP 响应")
            return null
        }
        val content = objectMapper.readTree(resp)
            .path("choices").path(0).path("message").path("content").asText("")
        if (content.isBlank()) {
            log.warn("大模型返回空 content: {}", resp.take(200))
            return null
        }
        return content
    }

    private fun parseAiResult(content: String): AiResult? {
        return try {
            val node = objectMapper.readTree(content)
            val conclusionRaw = node.path("conclusion").asText("").lowercase()
            if (conclusionRaw.isBlank()) {
                log.warn("大模型响应缺少 conclusion")
                return null
            }
            val conclusion = runCatching { Conclusion.valueOf(conclusionRaw.uppercase()) }.getOrNull()
                ?: run {
                    log.warn("大模型返回无效 conclusion: {}", conclusionRaw)
                    return null
                }
            val reasons = node.path("reasons").map { it.asText() }.filter { it.isNotBlank() }.take(3)
            if (reasons.isEmpty()) {
                log.warn("大模型响应缺少有效 reasons")
                return null
            }
            AiResult(
                conclusion = conclusion.name.lowercase(),
                conclusionLabel = conclusion.label,
                reasons = reasons,
                riskHint = node.path("riskHint").asText(null)?.takeIf { it.isNotBlank() },
                confidence = node.path("confidence").asText("中").ifBlank { "中" },
            )
        } catch (e: Exception) {
            log.warn("大模型响应解析失败，将降级到规则推理: {}", e.message)
            null
        }
    }
}
