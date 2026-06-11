package com.zdsj.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "zdsj.product-image")
data class ProductImageProperties(
    /** 商品主图落盘目录（生产 Docker 挂载 /data/product-images） */
    val storageDir: String = "./data/product-images",
    /** 对外 URL 路径前缀，由 Nginx 或 Spring 静态资源托管 */
    val publicPathPrefix: String = "/static/products",
    /** 单文件大小上限（字节） */
    val maxBytes: Long = 1_024_000,
    /** 下载外链超时（秒） */
    val downloadTimeoutSeconds: Int = 15,
)
