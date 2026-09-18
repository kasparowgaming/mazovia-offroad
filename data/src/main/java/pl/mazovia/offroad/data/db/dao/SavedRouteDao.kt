package pl.mazovia.offroad.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import pl.mazovia.offroad.data.db.entity.SavedRouteEntity

@Dao
interface SavedRouteDao {
    @Query("SELECT * FROM saved_routes ORDER BY createdAtMillis DESC")
    fun getAllRoutes(): Flow<List<SavedRouteEntity>>

    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun getRouteById(id: String): SavedRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: SavedRouteEntity)

    @Delete
    suspend fun deleteRoute(route: SavedRouteEntity)

    @Query("SELECT COUNT(*) FROM saved_routes")
    suspend fun getRouteCount(): Int
}
