package com.zdsj.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Path

/**
 * 本地开发时由 Spring 托管 /static/products/ 路径；
 * 生产环境优先由 Nginx 直接读共享卷（不经后端）。
 */
@Configuration
class ProductImageWebConfig(
    private val props: ProductImageProperties,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val dir = Path.of(props.storageDir).toAbsolutePath().normalize()
        val prefix = props.publicPathPrefix.trimEnd('/') + "/"
        registry.addResourceHandler(prefix + "**")
            .addResourceLocations("file:${dir}/")
    }
}
