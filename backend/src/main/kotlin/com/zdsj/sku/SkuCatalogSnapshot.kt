package com.zdsj.sku

/** 内存中的品牌 / 型号目录快照，由 SkuCatalogReader 从 DB 加载 */
data class SkuCatalogSnapshot(
    /** 小写别名 → 规范品牌名 */
    val brandAliasToCanonical: Map<String, String> = emptyMap(),
    /** 规范品牌名 → 型号列表（按长度降序，优先长匹配） */
    val modelsByBrand: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        val EMPTY = SkuCatalogSnapshot()
    }
}
