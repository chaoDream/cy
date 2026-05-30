package com.zdsj.product

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.common.ApiResponse
import com.zdsj.price.UserAssets
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
class LinkController(private val analysisService: AnalysisService) {

    @PostMapping("/parse")
    fun parse(@RequestBody req: LinkParseRequest): ApiResponse<ParseResult> =
        ApiResponse.ok(analysisService.parseLink(req.linkText))
}

@RestController
@RequestMapping("/api/product")
class ProductController(
    private val analysisService: AnalysisService,
    private val objectMapper: ObjectMapper,
) {

    /**
     * GET /api/product/analysis?platform=&item_id=&assets=
     * assets 为 JSON 字符串（小程序把省钱资产库勾选透传），游客可不带。
     */
    @GetMapping("/analysis")
    fun analysis(
        @RequestParam platform: String,
        @RequestParam("item_id") itemId: String,
        @RequestParam(required = false) assets: String?,
    ): ApiResponse<AnalysisResult> {
        val userAssets = parseAssets(assets)
        return ApiResponse.ok(analysisService.analyze(platform, itemId, userAssets))
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
