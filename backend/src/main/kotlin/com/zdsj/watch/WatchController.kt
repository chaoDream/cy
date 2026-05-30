package com.zdsj.watch

import com.zdsj.common.ApiResponse
import com.zdsj.product.ProductRawRepository
import com.zdsj.user.currentUserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

data class WatchCreateRequest(
    val rawProductId: Long = 0,
    val skuId: Long? = null,
    val targetPrice: BigDecimal? = null,
)

data class TargetUpdateRequest(val watchId: Long = 0, val targetPrice: BigDecimal = BigDecimal.ZERO)
data class NotifyToggleRequest(val watchId: Long = 0, val enabled: Boolean = true)
data class WatchRemoveRequest(val watchId: Long = 0)

@RestController
@RequestMapping("/api/watch")
class WatchController(
    private val watchService: WatchService,
    private val rawRepo: ProductRawRepository,
) {

    @PostMapping("/create")
    fun create(request: HttpServletRequest, @RequestBody req: WatchCreateRequest): ApiResponse<Map<String, Any?>> {
        val w = watchService.create(request.currentUserId(), req.rawProductId, req.skuId, req.targetPrice)
        return ApiResponse.ok(
            mapOf("watchId" to w.id, "targetPrice" to w.targetPrice, "notifyStatus" to w.notifyEnabled),
        )
    }

    @GetMapping("/list")
    fun list(request: HttpServletRequest): ApiResponse<List<Map<String, Any?>>> {
        val items = watchService.listByUser(request.currentUserId()).map {
            val diff = it.currentPrice?.subtract(it.targetPrice)
            val raw = rawRepo.findById(it.rawProductId).orElse(null)
            mapOf(
                "watchId" to it.id,
                "rawProductId" to it.rawProductId,
                "skuId" to it.skuId,
                "platform" to raw?.platform,
                "platformItemId" to raw?.platformItemId,
                "title" to raw?.title,
                "imageUrl" to raw?.imageUrl,
                "targetPrice" to it.targetPrice,
                "currentPrice" to it.currentPrice,
                "diffToTarget" to diff,
                "status" to it.status,
                "notifyEnabled" to it.notifyEnabled,
            )
        }
        return ApiResponse.ok(items)
    }

    @PostMapping("/target")
    fun updateTarget(request: HttpServletRequest, @RequestBody req: TargetUpdateRequest): ApiResponse<Map<String, Any?>> {
        val w = watchService.updateTarget(request.currentUserId(), req.watchId, req.targetPrice)
        return ApiResponse.ok(mapOf("watchId" to w.id, "targetPrice" to w.targetPrice))
    }

    @PostMapping("/notify")
    fun toggleNotify(request: HttpServletRequest, @RequestBody req: NotifyToggleRequest): ApiResponse<Map<String, Any?>> {
        val w = watchService.toggleNotify(request.currentUserId(), req.watchId, req.enabled)
        return ApiResponse.ok(mapOf("watchId" to w.id, "notifyEnabled" to w.notifyEnabled))
    }

    @PostMapping("/remove")
    fun remove(request: HttpServletRequest, @RequestBody req: WatchRemoveRequest): ApiResponse<Boolean> {
        watchService.remove(request.currentUserId(), req.watchId)
        return ApiResponse.ok(true)
    }
}
