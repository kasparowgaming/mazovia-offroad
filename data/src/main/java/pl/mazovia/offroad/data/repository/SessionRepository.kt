package pl.mazovia.offroad.data.repository

import pl.mazovia.offroad.data.db.dao.SessionDao
import pl.mazovia.offroad.data.db.entity.NavigationSessionEntity
import pl.mazovia.offroad.data.db.entity.RecordingSessionEntity
import pl.mazovia.offroad.domain.model.*

/**
 * Manages persistent session state for navigation and recording.
 * Enables recovery after process death.
 */
class SessionRepository(
    private val sessionDao: SessionDao
) {
    suspend fun saveNavigationSession(
        route: Route,
        currentSegmentIndex: Int,
        routeJson: String
    ) {
        sessionDao.saveNavigationSession(
            NavigationSessionEntity(
                routeDataJson = routeJson,
                profile = route.profile.name,
                destinationLat = route.destination.latitude,
                destinationLon = route.destination.longitude,
                originLat = route.origin.latitude,
                originLon = route.origin.longitude,
                currentSegmentIndex = currentSegmentIndex,
                isActive = true,
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun getActiveNavigationSession(): NavigationSessionEntity? {
        return sessionDao.getActiveNavigationSession()
    }

    suspend fun clearNavigationSession() {
        sessionDao.clearNavigationSession()
    }

    suspend fun saveRecordingSession(state: RecordingState) {
        sessionDao.saveRecordingSession(
            RecordingSessionEntity(
                trackId = state.trackId ?: return,
                status = state.status.name,
                startTimeMillis = state.startTimeMillis,
                distanceMeters = state.distanceMeters,
                durationSeconds = state.durationSeconds,
                pointCount = state.pointCount,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun getActiveRecordingSession(): RecordingSessionEntity? {
        return sessionDao.getActiveRecordingSession()
    }

    suspend fun clearRecordingSession() {
        sessionDao.clearRecordingSession()
    }
}
