package pl.mazovia.offroad.domain.roughness

interface DebugRawRecorder {
    fun startSession(metadata: String)
    fun recordAccel(timestampNanos: Long, x: Float, y: Float, z: Float)
    fun recordOrientation(timestampNanos: Long, source: String, vals: FloatArray)
    fun recordLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float)
    fun recordDrop(count: Int)
    fun stopSession()
}

class NoOpDebugRawRecorder : DebugRawRecorder {
    override fun startSession(metadata: String) {}
    override fun recordAccel(timestampNanos: Long, x: Float, y: Float, z: Float) {}
    override fun recordOrientation(timestampNanos: Long, source: String, vals: FloatArray) {}
    override fun recordLocation(elapsedRealtimeNanos: Long, lat: Double, lon: Double, speed: Float, accuracy: Float, speedAccuracy: Float) {}
    override fun recordDrop(count: Int) {}
    override fun stopSession() {}
}
