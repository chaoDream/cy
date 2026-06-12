package com.zdsj.sku

data class SkuParseResult(
    val brand: String?,
    val series: String?,
    val model: String?,
    val storage: String?,
    val color: String?,
    val networkVersion: String?,
    val regionVersion: String?,
    val condition: String,
    val packageType: String,
    val standardName: String,
    val confidence: String,
    val riskTags: List<String>,
)
