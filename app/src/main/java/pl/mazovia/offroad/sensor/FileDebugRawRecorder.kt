package pl.mazovia.offroad.sensor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import pl.mazovia.offroad.domain.roughness.DebugRawRecorder
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import pl.mazovia.offroad.domain.roughness.NoOpDebugRawRecorder

class DynamicDebugRecorder(private val context: Context) : DebugRawRecorder {
    private var actualRecorder: DebugRawRecorder = NoOpDebugRawRecorder()
    
    override fun startSession(metadata: String) {
        val prefs = context.getSharedPreferences("mazovia_prefs", Context.MODE_PRIVATE)
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val enabled = isDebug && prefs.getBoolean("raw_validation_enabled", false)
        if (enabled) {
            actualRecorder = FileDebugRawRecorder(context)
        } else {
            actualRecorder = NoOpDebugRawRecorder()
        }
        actualRecorder.startSession(metadata)
    }
    
    override fun recordAccel(timestampNanos: Long, x: Float, y: Float, z: Float) = actualRecorder.recordAccel(timestampNanos, x, y, z)
    override fun recordOrientation(timestampNanos: Long, source: String, vals: FloatArray) = actualRecorder.recordOrientation(timestampNanos, source, vals)
    override fun recordLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float) = actualRecorder.recordLocation(elapsedRealtimeNanos, lat, lon, speed, accuracy, speedAccuracy)
    override fun recordDrop(count: Int) = actualRecorder.recordDrop(count)
    override fun stopSession() {
        actualRecorder.stopSession()
        actualRecorder = NoOpDebugRawRecorder()
    }
}

class FileDebugRawRecorder(private val context: Context) : DebugRawRecorder {
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var writer: BufferedWriter? = null
    
    private var droppedCount = 0

    override fun startSession(metadata: String) {
        stopSession() // ensure previous is closed
        
        handlerThread = HandlerThread("DebugRawRecorderThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
        
        handler?.post {
            try {
                val dir = File(context.getExternalFilesDir(null), "validation_logs")
                if (!dir.exists()) dir.mkdirs()
                
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val file = File(dir, "validation_v1_${sdf.format(Date())}.csv")
                
                writer = BufferedWriter(FileWriter(file, true))
                // Write session meta
                writer?.write("SESSION_META,validationFormatVersion=1,$metadata\n")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun writeLine(line: String) {
        val h = handler ?: run {
            droppedCount++
            return
        }
        val posted = h.post {
            try {
                if (droppedCount > 0) {
                    writer?.write("DROP,$droppedCount\n")
                    droppedCount = 0
                }
                writer?.write(line)
                writer?.write("\n")
            } catch (e: Exception) {
                droppedCount++
            }
        }
        if (!posted) droppedCount++
    }

    override fun recordAccel(timestampNanos: Long, x: Float, y: Float, z: Float) {
        writeLine("ACCEL,$timestampNanos,$x,$y,$z")
    }

    override fun recordOrientation(timestampNanos: Long, source: String, vals: FloatArray) {
        val joined = vals.joinToString(",")
        writeLine("ORIENTATION,$timestampNanos,$source,$joined")
    }

    override fun recordLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float) {
        writeLine("LOCATION,$elapsedRealtimeNanos,$lat,$lon,$speed,$accuracy,$speedAccuracy")
    }

    override fun recordDrop(count: Int) {
        writeLine("DROP,$count")
    }

    override fun stopSession() {
        val h = handler
        if (h != null) {
            h.post {
                try {
                    writer?.flush()
                    writer?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                writer = null
                handlerThread?.quitSafely()
            }
        }
        handlerThread = null
        handler = null
    }
}
