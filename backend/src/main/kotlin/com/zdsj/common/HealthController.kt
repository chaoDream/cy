package com.zdsj.common

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class HealthController {

    @GetMapping("/health")
    fun health(): ApiResponse<Map<String, Any>> =
        ApiResponse.ok(mapOf("status" to "UP", "ts" to Instant.now().toString()))
}
