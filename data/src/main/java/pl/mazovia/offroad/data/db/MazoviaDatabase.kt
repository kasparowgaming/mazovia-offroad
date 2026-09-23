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
        RecordingSessionEntity::class,
        RoughnessSampleEntity::class,
        RideCalibrationProfileEntity::class
    ],
    version = 3,
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
    abstract fun roughnessSampleDao(): RoughnessSampleDao
    abstract fun calibrationProfileDao(): CalibrationProfileDao

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
                .addMigrations(object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `roughness_samples` (
                                `id` TEXT NOT NULL,
                                `rideId` TEXT NOT NULL,
                                `startElapsedRealtimeNanos` INTEGER NOT NULL,
                                `endElapsedRealtimeNanos` INTEGER NOT NULL,
                                `midpointElapsedRealtimeNanos` INTEGER NOT NULL,
                                `epochMillis` INTEGER NOT NULL,
                                `latitude` REAL NOT NULL,
                                `longitude` REAL NOT NULL,
                                `meanSpeedMps` REAL NOT NULL,
                                `speedDeltaMps` REAL,
                                `gpsAccuracyMeters` REAL NOT NULL,
                                `speedAccuracyMps` REAL,
                                `windowDurationMillis` INTEGER NOT NULL,
                                `windowDistanceMeters` REAL NOT NULL,
                                `sampleCount` INTEGER NOT NULL,
                                `expectedSampleCount` INTEGER NOT NULL,
                                `verticalRms` REAL NOT NULL,
                                `p95AbsVerticalAccel` REAL NOT NULL,
                                `peakAbsVerticalAccel` REAL NOT NULL,
                                `baselineRatio` REAL,
                                `qualityStateCode` INTEGER NOT NULL,
                                `qualityReasonFlags` INTEGER NOT NULL,
                                `calibrationProfileId` TEXT NOT NULL,
                                `algorithmVersion` INTEGER NOT NULL,
                                `speedModelVersion` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roughness_samples_rideId` ON `roughness_samples` (`rideId`)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_roughness_samples_rideId_startElapsedRealtimeNanos_endElapsedRealtimeNanos_algorithmVersion` ON `roughness_samples` (`rideId`, `startElapsedRealtimeNanos`, `endElapsedRealtimeNanos`, `algorithmVersion`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roughness_samples_calibrationProfileId` ON `roughness_samples` (`calibrationProfileId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `ride_calibration_profiles` (
                                `id` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `bikeLabel` TEXT,
                                `mountLabel` TEXT,
                                `deviceModel` TEXT NOT NULL,
                                `baselineVerticalRms` REAL NOT NULL,
                                `baselineP95` REAL,
                                `baselineIqrRatio` REAL,
                                `baselineSpeedMps` REAL NOT NULL,
                                `acceptedWindowCount` INTEGER NOT NULL,
                                `calibrationDistanceMeters` REAL NOT NULL,
                                `algorithmVersion` INTEGER NOT NULL,
                                `speedModelVersion` INTEGER NOT NULL,
                                `createdAtMillis` INTEGER NOT NULL,
                                `isActive` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                    }
                })
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
