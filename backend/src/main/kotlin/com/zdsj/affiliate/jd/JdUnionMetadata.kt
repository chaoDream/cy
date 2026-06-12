package com.zdsj.affiliate.jd

import com.fasterxml.jackson.databind.JsonNode

/** 京东联盟商品节点中的品牌 / 类目 / SPU 等结构化字段 */
data class JdGoodsMeta(
    val brandName: String? = null,
    val spuId: String? = null,
    val category: String? = null,
)

object JdUnionMetadata {

    fun fromGoodsNode(node: JsonNode): JdGoodsMeta = JdGoodsMeta(
        brandName = node.path("brandName").asText(null)?.trim()?.takeIf { it.isNotBlank() },
        spuId = node.path("spuId").asText(null)?.trim()?.takeIf { it.isNotBlank() },
        category = extractCategory(node),
    )

    private fun extractCategory(node: JsonNode): String? {
        val catInfo = node.path("categoryInfo")
        if (!catInfo.isMissingNode && !catInfo.isNull) {
            val parts = listOf("cid1Name", "cid2Name", "cid3Name")
                .mapNotNull { key -> catInfo.path(key).asText(null)?.trim()?.takeIf { it.isNotBlank() } }
            if (parts.isNotEmpty()) return parts.joinToString("/")
        }
        return node.path("categoryName").asText(null)?.trim()?.takeIf { it.isNotBlank() }
    }
}
