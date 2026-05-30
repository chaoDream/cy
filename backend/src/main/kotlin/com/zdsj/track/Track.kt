package com.zdsj.track

import com.zdsj.common.ApiResponse
import com.zdsj.user.AuthInterceptor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.servlet.http.HttpServletRequest
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * 埋点事件（PRD §12）。核心事件：app_open / link_parse_* / analysis_view /
 * price_detail_expand / risk_detail_view / watch_create / target_price_update /
 * purchase_click(is_cps_matched) / ai_chat_send / feedback_submit / share_card_click。
 */
@Entity
@Table(name = "track_event")
class TrackEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "user_id") var userId: Long? = null,
    var openid: String? = null,
    var event: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "props_json", columnDefinition = "jsonb")
    var propsJson: MutableMap<String, Any?> = mutableMapOf(),
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface TrackEventRepository : JpaRepository<TrackEvent, Long>

@Service
class TrackService(private val repo: TrackEventRepository) {
    fun record(userId: Long?, event: String, props: Map<String, Any?>) {
        repo.save(TrackEvent(userId = userId, event = event, propsJson = props.toMutableMap()))
    }
}

data class TrackRequest(val event: String = "", val props: Map<String, Any?> = emptyMap())

@RestController
@RequestMapping("/api/track")
class TrackController(private val trackService: TrackService) {

    /** 埋点上报：游客也可上报（app_open 等），故在 WebConfig 不强制登录时需放行；这里兼容两种 */
    @PostMapping("/event")
    fun event(request: HttpServletRequest, @RequestBody req: TrackRequest): ApiResponse<Boolean> {
        val userId = request.getAttribute(AuthInterceptor.USER_ID_ATTR) as? Long
        trackService.record(userId, req.event, req.props)
        return ApiResponse.ok(true)
    }
}
