package com.zdsj.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 大模型客户端（OpenAI 兼容 Chat Completions）。
 *
 * 设计要点（PRD §9.3 防幻觉）：
 * - 事实（价格/历史价/风险）作为结构化输入喂给模型，模型只做理解与解释，不算价。
 * - 复杂促销/SKU 解析用高精度模型(modelHigh)，常规走便宜快模型(modelFast)。
 * - Function Calling 工具在 §parse_link/search_products/get_best_price/get_price_history/detect_risk
 *   由后端各服务实现并注入事实，这里仅做结论编排。
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

    fun analyze(input: AiInput): AiResult? {
        return try {
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
            val content = objectMapper.readTree(resp)
                .path("choices").path(0).path("message").path("content").asText()
            val node = objectMapper.readTree(content)
            val conclusion = node.path("conclusion").asText("caution")
            AiResult(
                conclusion = conclusion,
                conclusionLabel = Conclusion.valueOf(conclusion.uppercase()).label,
                reasons = node.path("reasons").map { it.asText() }.take(3),
                riskHint = node.path("riskHint").asText(null),
                confidence = node.path("confidence").asText("中"),
            )
        } catch (e: Exception) {
            log.warn("大模型调用失败，降级到规则推理: {}", e.message)
            null
        }
    }
}
