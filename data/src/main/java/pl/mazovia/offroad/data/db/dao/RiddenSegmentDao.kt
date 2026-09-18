package pl.mazovia.offroad.data.db.dao

import androidx.room.*
import pl.mazovia.offroad.data.db.entity.RiddenSegmentEntity

@Dao
interface RiddenSegmentDao {
    @Query("SELECT * FROM ridden_segments WHERE osmWayId = :osmWayId")
    suspend fun getByOsmWayId(osmWayId: Long): RiddenSegmentEntity?

    @Query("SELECT * FROM ridden_segments WHERE segmentHash = :hash")
    suspend fun getByHash(hash: String): RiddenSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: RiddenSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<RiddenSegmentEntity>)

    @Query("SELECT COUNT(*) FROM ridden_segments")
    suspend fun getTotalSegmentCount(): Int

    @Query("SELECT * FROM ridden_segments WHERE startLat BETWEEN :minLat AND :maxLat AND startLon BETWEEN :minLon AND :maxLon")
    suspend fun getSegmentsInBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<RiddenSegmentEntity>
}
