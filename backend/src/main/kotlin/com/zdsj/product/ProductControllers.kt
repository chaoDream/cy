package com.zdsj.product

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.common.ApiResponse
import com.zdsj.ai.AiResult
import com.zdsj.affiliate.pdd.PddCustomParams
import com.zdsj.price.UserAssets
import com.zdsj.user.JwtService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class LinkParseRequest(@field:NotBlank val linkText: String = "")

@RestController
@RequestMapping("/api/link")
class LinkController(
    private val analysisService: AnalysisService,
    private val jwtService: JwtService,
) {

    @PostMapping("/parse")
    fun parse(
        @RequestBody req: LinkParseRequest,
        request: HttpServletRequest,
    ): ApiResponse<ParseResult> =
        ApiResponse.ok(analysisService.parseLink(req.linkText, optionalPddUserKey(request)))

    /** 解析接口对游客开放，但若带了有效 token 则传入拼多多 custom_parameters */
    private fun optionalPddUserKey(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        val userId = jwtService.verify(header.substring(7)) ?: return null
        return PddCustomParams.of(userId.toString())
    }
}

@RestController
@RequestMapping("/api/product")
class ProductController(
    private val analysisService: AnalysisService,
    private val objectMapper: ObjectMapper,
) {

    /**
     * GET /api/product/analysis?platform=&item_id=&assets=
     * 核心分析数据（价格/趋势/风险/跨平台），不含 AI 建议。
     */
    @GetMapping("/analysis")
    fun analysis(
        @RequestParam platform: String,
        @RequestParam("item_id") itemId: String,
        @RequestParam(required = false) assets: String?,
        @RequestParam(required = false) uid: String?,
    ): ApiResponse<AnalysisCoreResult> {
        val userAssets = parseAssets(assets)
        val userKey = com.zdsj.affiliate.pdd.PddCustomParams.of(uid)
        return ApiResponse.ok(analysisService.analyze(platform, itemId, userAssets, userKey))
    }

    /**
     * GET /api/product/ai-recommendation?platform=&item_id=&assets=
     * AI 购买建议，与 analysis 并行请求，不阻塞首屏。
     */
    @GetMapping("/ai-recommendation")
    fun aiRecommendation(
        @RequestParam platform: String,
        @RequestParam("item_id") itemId: String,
        @RequestParam(required = false) assets: String?,
        @RequestParam(required = false, defaultValue = "false") forceRule: Boolean,
    ): ApiResponse<AiResult> {
        val userAssets = parseAssets(assets)
        return ApiResponse.ok(analysisService.aiRecommendation(platform, itemId, userAssets, forceRule))
    }

    @GetMapping("/search")
    fun search(@RequestParam keyword: String): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.ok(analysisService.search(keyword))

    private fun parseAssets(assets: String?): UserAssets {
        if (assets.isNullOrBlank()) return UserAssets()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(assets, Map::class.java) as Map<String, Any?>
            UserAssets.from(map)
        }.getOrDefault(UserAssets())
    }
}
