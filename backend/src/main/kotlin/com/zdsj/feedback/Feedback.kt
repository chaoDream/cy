package com.zdsj.feedback

import com.zdsj.common.ApiResponse
import com.zdsj.user.currentUserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@Entity
@Table(name = "user_feedback")
class UserFeedback(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "user_id") var userId: Long? = null,
    @Column(name = "raw_product_id") var rawProductId: Long? = null,
    @Column(name = "feedback_type") var feedbackType: String = "other",
    @Column(name = "feedback_content") var feedbackContent: String? = null,
    var status: String = "open",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface UserFeedbackRepository : JpaRepository<UserFeedback, Long>

@Service
class FeedbackService(private val repo: UserFeedbackRepository) {
    fun create(userId: Long, rawProductId: Long?, type: String, content: String?): Long =
        repo.save(
            UserFeedback(userId = userId, rawProductId = rawProductId, feedbackType = type, feedbackContent = content),
        ).id!!
}

data class FeedbackRequest(
    val rawProductId: Long? = null,
    val feedbackType: String = "other",
    val feedbackContent: String? = null,
)

@RestController
@RequestMapping("/api/feedback")
class FeedbackController(private val feedbackService: FeedbackService) {

    @PostMapping("/create")
    fun create(request: HttpServletRequest, @RequestBody req: FeedbackRequest): ApiResponse<Map<String, Any?>> {
        val id = feedbackService.create(request.currentUserId(), req.rawProductId, req.feedbackType, req.feedbackContent)
        return ApiResponse.ok(mapOf("feedbackId" to id))
    }
}
