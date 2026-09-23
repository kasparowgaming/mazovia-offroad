package pl.mazovia.offroad.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import pl.mazovia.offroad.domain.roughness.RoughnessAlgorithmConfig

import pl.mazovia.offroad.domain.roughness.SensorSource

class AndroidMotionSensorSource(
    context: Context,
    private val config: RoughnessAlgorithmConfig
) : SensorSource {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    // Preferred orientation hierarchy
    private val orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private var onSample: ((accelTimestamp: Long, accel: FloatArray, orientation: FloatArray?) -> Unit)? = null

    // Store latest orientation
    private var lastOrientation: FloatArray? = null
    private var lastOrientationTimestamp = 0L

    override val orientationSourceName: String = orientationSensor?.name ?: "UNKNOWN"

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GRAVITY -> {
                    lastOrientation = event.values.clone()
                    lastOrientationTimestamp = event.timestamp
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val currentOrientation = lastOrientation
                    val currentOrientationTs = lastOrientationTimestamp
                    
                    val age = Math.abs(event.timestamp - currentOrientationTs)
                    val validOrientation = if (currentOrientation != null && age <= config.maxOrientationAgeMillis * 1_000_000L) {
                        currentOrientation
                    } else null

                    onSample?.invoke(event.timestamp, event.values.clone(), validOrientation)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start(callback: (accelTimestamp: Long, accel: FloatArray, orientation: FloatArray?) -> Unit) {
        onSample = callback
        lastOrientation = null
        lastOrientationTimestamp = 0L

        handlerThread = HandlerThread("MotionSensorThread").apply { start() }
        sensorHandler = Handler(handlerThread!!.looper)

        // 100 Hz = 10,000 microseconds. 50 Hz = 20,000 microseconds
        val accelDelayUs = (1_000_000 / config.accelerometerTargetRateHz)
        val orientDelayUs = (1_000_000 / config.orientationTargetRateHz)

        accelerometer?.let {
            sensorManager.registerListener(sensorListener, it, accelDelayUs, sensorHandler)
        }
        orientationSensor?.let {
            sensorManager.registerListener(sensorListener, it, orientDelayUs, sensorHandler)
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(sensorListener)
        handlerThread?.quitSafely()
        handlerThread = null
        sensorHandler = null
        onSample = null
    }
}
