package pl.mazovia.offroad.data.repository

import pl.mazovia.offroad.data.db.dao.RiddenSegmentDao
import pl.mazovia.offroad.data.db.entity.RiddenSegmentEntity
import pl.mazovia.offroad.domain.model.BoundingBox
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.RiddenSegment

class ExplorationRepository(
    private val riddenSegmentDao: RiddenSegmentDao
) {
    suspend fun isSegmentRidden(osmWayId: Long?, segmentHash: String): Boolean {
        if (osmWayId != null) {
            riddenSegmentDao.getByOsmWayId(osmWayId)?.let { return true }
        }
        return riddenSegmentDao.getByHash(segmentHash) != null
    }

    suspend fun recordRiddenSegment(segment: RiddenSegment) {
        val existing = riddenSegmentDao.getByHash(segment.segmentHash)
        if (existing != null) {
            riddenSegmentDao.insertSegment(
                existing.copy(
                    lastRiddenMillis = segment.lastRiddenMillis,
                    rideCount = existing.rideCount + 1
                )
            )
        } else {
            riddenSegmentDao.insertSegment(segment.toEntity())
        }
    }

    suspend fun recordRiddenSegments(segments: List<RiddenSegment>) {
        segments.forEach { recordRiddenSegment(it) }
    }

    suspend fun getRiddenSegmentsInArea(bounds: BoundingBox): List<RiddenSegment> {
        return riddenSegmentDao.getSegmentsInBounds(
            minLat = bounds.south,
            maxLat = bounds.north,
            minLon = bounds.west,
            maxLon = bounds.east
        ).map { it.toDomain() }
    }

    suspend fun getTotalRiddenSegments(): Int = riddenSegmentDao.getTotalSegmentCount()

    private fun RiddenSegment.toEntity() = RiddenSegmentEntity(
        segmentHash = segmentHash,
        osmWayId = osmWayId,
        firstRiddenMillis = firstRiddenMillis,
        lastRiddenMillis = lastRiddenMillis,
        rideCount = rideCount,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon
    )

    private fun RiddenSegmentEntity.toDomain() = RiddenSegment(
        osmWayId = osmWayId,
        segmentHash = segmentHash,
        firstRiddenMillis = firstRiddenMillis,
        lastRiddenMillis = lastRiddenMillis,
        rideCount = rideCount,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon
    )
}
