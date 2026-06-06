package com.zdsj.affiliate

/** 判断是否为可在小程序中加载的真实商品主图 URL（排除代理占位、mock 假图） */
object ProductImageUrls {
    fun isLoadable(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("/api/product/image")) return false
        val lower = url.lowercase()
        if (lower.contains("example.com")) return false
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")
    }
}
