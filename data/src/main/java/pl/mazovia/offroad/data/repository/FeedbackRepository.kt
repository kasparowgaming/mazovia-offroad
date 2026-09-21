package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.mazovia.offroad.data.db.dao.RoadFeedbackDao
import pl.mazovia.offroad.data.db.entity.RoadFeedbackEntity
import pl.mazovia.offroad.domain.model.*

class FeedbackRepository(
    private val feedbackDao: RoadFeedbackDao
) {
    fun getUnansweredFeedback(): Flow<List<RoadFeedback>> =
        feedbackDao.getUnansweredFeedback().map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun getFeedbackForRide(rideId: String): List<RoadFeedback> {
        return feedbackDao.getFeedbackForRide(rideId).map { it.toDomain() }
    }

    suspend fun saveFeedback(feedback: RoadFeedback) {
        feedbackDao.insertFeedback(feedback.toEntity())
    }

    suspend fun submitAnswer(feedbackId: String, answer: FeedbackAnswer) {
        val feedback = requireNotNull(feedbackDao.getFeedbackById(feedbackId))
        require(answer in FeedbackQuestion.valueOf(feedback.question).allowedAnswers)
        if (feedback.answer == answer.name) return // Safe retry after interrupted completion.
        check(feedbackDao.answerPending(feedbackId, answer.name) == 1)
    }

    suspend fun getUnansweredCount(): Int = feedbackDao.getUnansweredCount()

    private fun RoadFeedbackEntity.toDomain() = RoadFeedback(
        id = id,
        rideId = rideId,
        segmentOsmWayId = segmentOsmWayId,
        segmentStart = GeoPoint(segmentStartLat, segmentStartLon),
        segmentEnd = GeoPoint(segmentEndLat, segmentEndLon),
        question = try { FeedbackQuestion.valueOf(question) } catch (_: Exception) { FeedbackQuestion.SURFACE_QUALITY },
        answer = answer?.let { try { FeedbackAnswer.valueOf(it) } catch (_: Exception) { null } },
        timestampMillis = timestampMillis,
        dataSource = dataSource
    )

    private fun RoadFeedback.toEntity() = RoadFeedbackEntity(
        id = id,
        rideId = rideId,
        segmentOsmWayId = segmentOsmWayId,
        segmentStartLat = segmentStart.latitude,
        segmentStartLon = segmentStart.longitude,
        segmentEndLat = segmentEnd.latitude,
        segmentEndLon = segmentEnd.longitude,
        question = question.name,
        answer = answer?.name,
        timestampMillis = timestampMillis,
        dataSource = dataSource
    )
}
