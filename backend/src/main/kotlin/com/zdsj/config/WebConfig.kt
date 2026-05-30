package com.zdsj.config

import com.zdsj.user.AuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val authInterceptor: AuthInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/health",
                "/api/user/login",
                "/api/link/parse",       // 游客可解析
                "/api/product/analysis", // 游客可看分析
                "/api/product/search",   // 游客可搜索
                "/api/rank/**",          // 游客可看榜单
                "/api/track/event",      // 游客可埋点(app_open 等)
                "/api/admin/**",         // 后台另行鉴权(MVP 简化)
            )
    }
}
