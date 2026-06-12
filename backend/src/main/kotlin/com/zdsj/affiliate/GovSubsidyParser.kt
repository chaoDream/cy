package com.zdsj.affiliate

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * 京东国补促销标签解析（purchasePriceInfo.promotionLabelInfoList）。
 * 京东联盟官方接口和维易接口共用同一数据结构。
 */
object GovSubsidyParser {

    private val GOV_SUBSIDY_SUBTYPES = setOf(9100, 9105, 9107)

    fun parse(node: JsonNode): Map<String, Any?>? {
        val labels = node.path("purchasePriceInfo").path("promotionLabelInfoList").promotionLabels()
        val gov = labels.firstOrNull { it.path("subType").asInt(0) in GOV_SUBSIDY_SUBTYPES } ?: return null
        val rate = gov.path("rebate").positiveDecimalOrNull() ?: return null
        val provinces = gov.path("provinceNameList").asProvinceList()
        if (provinces.isEmpty()) return null
        return buildMap {
            put("govSubsidyRate", rate)
            put("govSubsidyCap", gov.path("topDiscount").positiveDecimalOrNull() ?: BigDecimal.ZERO)
            put("govSubsidyProvinces", provinces)
            put("govSubsidyType", gov.path("subType").asInt(0))
        }
    }

    fun tag(subType: Int?): String = when (subType) {
        9105 -> "以旧换新国补"
        9107 -> "国补购新立减"
        9100 -> "国补支付立减"
        else -> "国补"
    }

    private fun JsonNode.promotionLabels(): List<JsonNode> = when {
        isMissingNode || isNull -> emptyList()
        isArray -> toList()
        isObject -> {
            val inner = path("promotionLabelInfo")
            when {
                inner.isArray -> inner.toList()
                inner.isObject -> listOf(inner)
                else -> listOf(this)
            }
        }
        else -> emptyList()
    }

    private fun JsonNode.asProvinceList(): List<String> = when {
        isMissingNode || isNull -> emptyList()
        isArray -> mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotBlank) }
        isTextual -> asText().trim().removeSurrounding("[", "]")
            .split(',').map { it.trim().trim('"', '\'', '[', ']') }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun JsonNode.positiveDecimalOrNull(): BigDecimal? {
        if (isMissingNode || isNull) return null
        val v = asText(null)?.toBigDecimalOrNull() ?: if (isNumber) BigDecimal(asText()) else return null
        return v.takeIf { it > BigDecimal.ZERO }
    }
}
