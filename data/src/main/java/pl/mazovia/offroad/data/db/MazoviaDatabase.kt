package pl.mazovia.offroad.data.db

import android.content.Context
import androidx.room.*
import pl.mazovia.offroad.data.db.dao.*
import pl.mazovia.offroad.data.db.entity.*
import pl.mazovia.offroad.data.db.converter.Converters

@Database(
    entities = [
        RideEntity::class,
        TrackPointEntity::class,
        SavedRouteEntity::class,
        RoadFeedbackEntity::class,
        RiddenSegmentEntity::class,
        NavigationSessionEntity::class,
        RecordingSessionEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MazoviaDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun roadFeedbackDao(): RoadFeedbackDao
    abstract fun riddenSegmentDao(): RiddenSegmentDao
    abstract fun sessionDao(): SessionDao

    companion object {
        private const val DATABASE_NAME = "mazovia_offroad.db"

        @Volatile
        private var INSTANCE: MazoviaDatabase? = null

        fun getInstance(context: Context): MazoviaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MazoviaDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE rides ADD COLUMN terrainClassificationAvailable INTEGER NOT NULL DEFAULT 0")
                    }
                })
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
