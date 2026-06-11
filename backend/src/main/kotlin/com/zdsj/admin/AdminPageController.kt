package com.zdsj.admin

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * 把干净 URL /admin、/admin/ 转发到静态看板 classpath:/static/admin/index.html。
 * （Spring Boot 的 welcome-page 只在根路径自动映射 index.html，子目录需手动转发。）
 */
@Controller
class AdminPageController {

    @GetMapping("/admin", "/admin/")
    fun dashboard(): String = "forward:/admin/index.html"
}
