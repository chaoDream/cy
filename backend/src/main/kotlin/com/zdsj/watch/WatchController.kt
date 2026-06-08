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
    val watchMode: String = MODE_MERCHANT,
)

data class TargetUpdateRequest(val watchId: Long = 0, val targetPrice: BigDecimal = BigDecimal.ZERO)
data class NotifyAllRequest(val enabled: Boolean = true)
data class WatchModeRequest(val watchId: Long = 0, val watchMode: String = MODE_MERCHANT)
data class WatchRemoveRequest(val watchId: Long = 0)

@RestController
@RequestMapping("/api/watch")
class WatchController(
    private val watchService: WatchService,
    private val rawRepo: ProductRawRepository,
) {

    @PostMapping("/create")
    fun create(request: HttpServletRequest, @RequestBody req: WatchCreateRequest): ApiResponse<Map<String, Any?>> {
        val w = watchService.create(request.currentUserId(), req.rawProductId, req.skuId, req.targetPrice, req.watchMode)
        return ApiResponse.ok(
            mapOf(
                "watchId" to w.id,
                "targetPrice" to w.targetPrice,
                "notifyStatus" to w.notifyEnabled,
                "watchMode" to w.watchMode,
            ),
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
                "originalPrice" to raw?.rawPrice,
                "targetPrice" to it.targetPrice,
                "currentPrice" to it.currentPrice,
                "diffToTarget" to diff,
                "status" to it.status,
                "notifyEnabled" to it.notifyEnabled,
                "watchMode" to it.watchMode,
            )
        }
        return ApiResponse.ok(items)
    }

    @PostMapping("/target")
    fun updateTarget(request: HttpServletRequest, @RequestBody req: TargetUpdateRequest): ApiResponse<Map<String, Any?>> {
        val w = watchService.updateTarget(request.currentUserId(), req.watchId, req.targetPrice)
        return ApiResponse.ok(mapOf("watchId" to w.id, "targetPrice" to w.targetPrice))
    }

    @PostMapping("/notify-all")
    fun toggleNotifyAll(request: HttpServletRequest, @RequestBody req: NotifyAllRequest): ApiResponse<Map<String, Any?>> {
        val updated = watchService.setNotifyAll(request.currentUserId(), req.enabled)
        return ApiResponse.ok(mapOf("updated" to updated, "notifyEnabled" to req.enabled))
    }

    @PostMapping("/mode")
    fun updateMode(request: HttpServletRequest, @RequestBody req: WatchModeRequest): ApiResponse<Map<String, Any?>> {
        val w = watchService.updateMode(request.currentUserId(), req.watchId, req.watchMode)
        return ApiResponse.ok(mapOf("watchId" to w.id, "watchMode" to w.watchMode))
    }

    @PostMapping("/remove")
    fun remove(request: HttpServletRequest, @RequestBody req: WatchRemoveRequest): ApiResponse<Boolean> {
        watchService.remove(request.currentUserId(), req.watchId)
        return ApiResponse.ok(true)
    }
}
