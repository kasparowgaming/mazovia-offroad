package pl.mazovia.offroad.domain.roughness

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.FileWriter

class ReplayRunnerTest {

    @Test
    fun `test offline replay of raw validation format`() {
        val file = File.createTempFile("validation_test", ".csv")
        try {
            FileWriter(file).use { writer ->
                writer.write("SESSION_META,validationFormatVersion=1,test=meta\n")
                writer.write("LOCATION,1000000000,52.2297,21.0122,15.0,10.0,0.5\n")
                // Generate 500 samples of ACCEL and ORIENTATION (~5.0s)
                for (i in 0 until 500) {
                    val ts = 1000000000L + i * 10_000_000L
                    val bump = Math.sin(i * 0.1).toFloat() * 2.0f
                    writer.write("ACCEL,$ts,0.0,0.0,${9.81f + bump}\n")
                    writer.write("ORIENTATION,$ts,TYPE_GAME_ROTATION_VECTOR,0.0,0.0,0.0,1.0\n")
                }
                writer.write("DROP,5\n")
                writer.write("LOCATION,3500000000,52.2298,21.0122,15.0,10.0,0.5\n")
            }

            val config = RoughnessAlgorithmConfig.DEFAULT
            val accumulator = RoughnessWindowAccumulator(config)
            val filter = RoughnessFilter(
                config.accelerometerTargetRateHz.toDouble(),
                config.highPassHz,
                config.lowPassHz
            )
            val projector: VerticalAccelerationProjector = RotationVectorProjector()

            var metaCount = 0
            var accelCount = 0
            var orientCount = 0
            var locCount = 0
            var dropCount = 0
            var windowsGenerated = 0

            accumulator.reset(1000000000L)
            filter.reset()

            val parser = ValidationLogParser(object : ValidationLogHandler {
                var lastOrientation: FloatArray? = null
                
                override fun onMeta(meta: ValidationLogMetadata) {
                    assertEquals(1, meta.formatVersion)
                    metaCount++
                }
                override fun onAccel(timestampNanos: Long, x: Float, y: Float, z: Float) {
                    accelCount++
                    lastOrientation?.let { ori ->
                        val vert = projector.projectToVertical(x, y, z, ori)
                        val filtered = filter.process(vert.toDouble())
                        val feat = accumulator.process(filtered, timestampNanos)
                        if (feat != null) {
                            windowsGenerated++
                            assertTrue(feat.durationMillis >= 2000)
                        }
                    }
                }
                override fun onOrientation(timestampNanos: Long, source: String, vals: FloatArray) {
                    orientCount++
                    lastOrientation = vals
                }
                override fun onLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float) {
                    locCount++
                    accumulator.updateSpeed(speed.toDouble())
                }
                override fun onDrop(count: Int) {
                    dropCount++
                    // On drop, reset filter and accumulator
                    accumulator.reset(0L) // fake timestamp
                    filter.reset()
                }
            })

            parser.parse(file)

            assertEquals(1, metaCount)
            assertEquals(500, accelCount)
            assertEquals(500, orientCount)
            assertEquals(2, locCount)
            assertEquals(1, dropCount)
            // One window should have been generated (2.5s duration)
            assertEquals(1, windowsGenerated)

        } finally {
            file.delete()
        }
    }
    
    @Test
    fun `test parser fails on unsupported version`() {
        val file = File.createTempFile("validation_test_fail", ".csv")
        try {
            FileWriter(file).use { writer ->
                writer.write("SESSION_META,validationFormatVersion=2,test=meta\n")
            }
            val parser = ValidationLogParser(object : ValidationLogHandler {
                override fun onMeta(meta: ValidationLogMetadata) {}
                override fun onAccel(timestampNanos: Long, x: Float, y: Float, z: Float) {}
                override fun onOrientation(timestampNanos: Long, source: String, vals: FloatArray) {}
                override fun onLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float) {}
                override fun onDrop(count: Int) {}
            })
            
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(file)
            }
        } finally {
            file.delete()
        }
    }
}
