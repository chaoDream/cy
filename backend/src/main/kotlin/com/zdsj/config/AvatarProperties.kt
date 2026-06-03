package com.zdsj.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "zdsj.avatar")
data class AvatarProperties(
    /** 头像文件落盘目录（生产 Docker 挂载 /data/avatars） */
    val storageDir: String = "./data/avatars",
    /** 对外 URL 路径前缀，由 Nginx 或 Spring 静态资源托管 */
    val publicPathPrefix: String = "/static/avatars",
    /** 单文件大小上限（字节） */
    val maxBytes: Long = 512_000,
    /** 下载微信临时链超时（秒） */
    val downloadTimeoutSeconds: Int = 10,
)
