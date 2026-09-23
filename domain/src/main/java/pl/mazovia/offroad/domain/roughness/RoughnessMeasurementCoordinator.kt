package pl.mazovia.offroad.domain.roughness

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.location.LocationUpdate
import kotlin.math.*

interface SensorSource {
    val orientationSourceName: String
    fun start(callback: (accelTimestamp: Long, accel: FloatArray, orientation: FloatArray?) -> Unit)
    fun stop()
}

enum class CalibrationState {
    IDLE, CALIBRATING, MEASURING, PAUSED
}

private data class PendingWindow(
    val feature: RoughnessFeature,
    val startNanos: Long,
    val endNanos: Long
)

class RoughnessMeasurementCoordinator(
    private val config: RoughnessAlgorithmConfig,
    private val locationClient: LocationClient,
    private val sensorSource: SensorSource,
    private val evaluator: MeasurementQualityEvaluator,
    private val persistence: RoughnessPersistence,
    private val debugRecorder: DebugRawRecorder? = null
) {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private var coordinatorScope: CoroutineScope? = null
    
    private var accumulator = RoughnessWindowAccumulator(config)
    private var filter = RoughnessFilter(
        config.accelerometerTargetRateHz.toDouble(),
        config.highPassHz,
        config.lowPassHz
    )
    private var projector: VerticalAccelerationProjector = RotationVectorProjector()

    private var sessionEpochMillis = 0L
    private var sessionElapsedRealtimeNanos = 0L

    private var rideId: String? = null

    // GPS tracking
    private var lastLocation: LocationUpdate? = null
    private val locationBuffer = mutableListOf<LocationUpdate>()
    private val pendingWindows = mutableListOf<PendingWindow>()

    // Calibration State
    private var calState = CalibrationState.IDLE
    private var calValidWindows = 0
    private var calValidDistance = 0.0
    private val calFeatures = mutableListOf<RoughnessFeature>()
    private var activeProfile: RideCalibrationProfile? = null

    fun start(rideId: String, isCalibration: Boolean = false) {
        if (_isActive.value) return
        this.rideId = rideId
        _isActive.value = true
        
        sessionEpochMillis = System.currentTimeMillis()
        sessionElapsedRealtimeNanos = System.nanoTime() 
        
        calState = if (isCalibration) CalibrationState.CALIBRATING else CalibrationState.MEASURING
        calValidWindows = 0
        calValidDistance = 0.0
        calFeatures.clear()
        
        accumulator.reset(sessionElapsedRealtimeNanos)
        filter.reset()
        locationBuffer.clear()
        pendingWindows.clear()

        debugRecorder?.startSession("rideId=$rideId,isCalibration=$isCalibration,algorithmVersion=${config.algorithmVersion}")

        coordinatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        // Load active profile
        coordinatorScope?.launch {
            activeProfile = persistence.getActiveCalibrationProfile()
            if (activeProfile?.algorithmVersion != config.algorithmVersion) {
                activeProfile = null // Incompatible version
            }
        }

        coordinatorScope?.launch {
            locationClient.getLocationUpdates(1000L).collect { loc ->
                handleLocationUpdate(loc)
            }
        }

        sensorSource.start { timestampNanos, accel, orientation ->
            handleSensorSample(timestampNanos, accel, orientation)
        }
    }

    fun pause() {
        sensorSource.stop()
        coordinatorScope?.cancel()
        coordinatorScope = null
        _isActive.value = false
        if (calState == CalibrationState.CALIBRATING || calState == CalibrationState.MEASURING) {
            calState = CalibrationState.PAUSED
        }
        pendingWindows.clear()
        debugRecorder?.stopSession()
    }

    fun resume() {
        if (_isActive.value) return
        _isActive.value = true
        
        if (calState == CalibrationState.PAUSED) {
            calState = if (rideId != null && rideId == "calibration") CalibrationState.CALIBRATING else CalibrationState.MEASURING // rough guess, better track intent
        }
        
        accumulator.reset(System.nanoTime())
        filter.reset()
        
        debugRecorder?.startSession("rideId=$rideId,resume=true")

        coordinatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        coordinatorScope?.launch {
            locationClient.getLocationUpdates(1000L).collect { loc ->
                handleLocationUpdate(loc)
            }
        }
        sensorSource.start { timestampNanos, accel, orientation ->
            handleSensorSample(timestampNanos, accel, orientation)
        }
    }

    fun stop() {
        pause()
        rideId = null
        calState = CalibrationState.IDLE
    }

    private fun handleLocationUpdate(loc: LocationUpdate) {
        lastLocation = loc
        if (loc.speedMps != null) {
            accumulator.updateSpeed(loc.speedMps)
        }
        
        val t = loc.elapsedRealtimeNanos ?: System.nanoTime()
        locationBuffer.add(loc)
        
        // Trim buffer to last 30s
        val cutoff = t - 30_000_000_000L
        locationBuffer.removeAll { (it.elapsedRealtimeNanos ?: 0L) < cutoff }
        
        debugRecorder?.recordLocation(
            t, loc.point.latitude, loc.point.longitude, 
            loc.speedMps?.toFloat() ?: 0f, 
            loc.accuracyMeters?.toFloat() ?: 0f, 
            loc.speedAccuracyMps?.toFloat() ?: 0f
        )

        resolvePendingWindows(t)
    }

    private var lastSensorTimestamp = 0L

    private fun handleSensorSample(timestampNanos: Long, accel: FloatArray, orientation: FloatArray?) {
        debugRecorder?.recordAccel(timestampNanos, accel[0], accel[1], accel[2])
        if (orientation != null) {
            debugRecorder?.recordOrientation(timestampNanos, sensorSource.orientationSourceName, orientation)
        }

        if (lastSensorTimestamp != 0L) {
            val gapMillis = (timestampNanos - lastSensorTimestamp) / 1_000_000L
            if (gapMillis > config.maxSensorGapMillis) {
                accumulator.reset(timestampNanos)
                filter.reset()
            }
        }
        lastSensorTimestamp = timestampNanos

        if (orientation == null) return

        val vert = projector.projectToVertical(accel[0], accel[1], accel[2], orientation)
        val filtered = filter.process(vert.toDouble())
        
        val feature = accumulator.process(filtered, timestampNanos)
        if (feature != null) {
            pendingWindows.add(PendingWindow(feature, accumulator.getWindowStartNanos(), accumulator.getWindowEndNanos()))
            resolvePendingWindows(System.nanoTime())
        }
    }

    private fun resolvePendingWindows(now: Long) {
        val iterator = pendingWindows.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val postWindowLoc = locationBuffer.find { (it.elapsedRealtimeNanos ?: 0L) >= pending.endNanos }
            
            if (postWindowLoc != null) {
                iterator.remove()
                processResolvedWindow(pending, isTimeout = false)
            } else if (now - pending.endNanos > 2_500_000_000L) {
                iterator.remove()
                processResolvedWindow(pending, isTimeout = true)
            }
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val dPhi = (lat2 - lat1) * PI / 180.0
        val dLambda = (lon2 - lon1) * PI / 180.0

        val a = sin(dPhi / 2) * sin(dPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun processResolvedWindow(pending: PendingWindow, isTimeout: Boolean) {
        val startBracket = locationBuffer.filter { (it.elapsedRealtimeNanos ?: 0L) <= pending.startNanos }
            .maxByOrNull { it.elapsedRealtimeNanos ?: 0L }
        val inside = locationBuffer.filter { 
            val t = it.elapsedRealtimeNanos ?: 0L
            t > pending.startNanos && t < pending.endNanos 
        }
        val endBracket = locationBuffer.filter { (it.elapsedRealtimeNanos ?: 0L) >= pending.endNanos }
            .minByOrNull { it.elapsedRealtimeNanos ?: 0L }
            
        val allValidLocs = listOfNotNull(startBracket) + inside + listOfNotNull(endBracket)
        val sortedLocs = allValidLocs.distinctBy { it.elapsedRealtimeNanos }.sortedBy { it.elapsedRealtimeNanos }

        var totalBracketDist = 0.0
        for (i in 0 until sortedLocs.size - 1) {
            totalBracketDist += haversine(
                sortedLocs[i].point.latitude, sortedLocs[i].point.longitude,
                sortedLocs[i+1].point.latitude, sortedLocs[i+1].point.longitude
            )
        }
        
        val bracketDuration = if (sortedLocs.size >= 2) {
            (sortedLocs.last().elapsedRealtimeNanos!! - sortedLocs.first().elapsedRealtimeNanos!!) / 1e9
        } else { 0.0 }
        
        val windowDurationSec = pending.feature.durationMillis / 1000.0
        var authDistance = if (bracketDuration > 0.0) {
            totalBracketDist * (windowDurationSec / bracketDuration)
        } else {
            0.0
        }

        val midLoc = sortedLocs.minByOrNull { Math.abs((it.elapsedRealtimeNanos ?: 0L) - (pending.startNanos + pending.endNanos)/2) } ?: lastLocation
        
        val observedRate = if (windowDurationSec > 0) pending.feature.sampleCount / windowDurationSec else 0.0
        val completeness = pending.feature.sampleCount.toDouble() / pending.feature.expectedSampleCount.coerceAtLeast(1)
        val staleGps = if (midLoc?.elapsedRealtimeNanos != null) {
            (System.nanoTime() - midLoc.elapsedRealtimeNanos) / 1_000_000_000.0
        } else {
            999.0
        }
        
        val isCalibrated = activeProfile != null

        // Check if max duration closed it and we fell short of distance
        var distanceReached = authDistance >= config.targetWindowDistanceMeters - 0.1
        if (pending.feature.durationMillis >= config.maxWindowDurationMillis - 100 && authDistance < config.targetWindowDistanceMeters) {
            distanceReached = false
        }
        
        val speed = midLoc?.speedMps ?: 0.0

        val quality = evaluator.evaluate(
            isCalibrated = isCalibrated,
            isCalibrationMode = (calState == CalibrationState.CALIBRATING),
            speedMps = speed,
            gpsStaleSeconds = if (isTimeout) 999.0 else staleGps,
            gpsAccuracyMeters = midLoc?.accuracyMeters,
            sampleCompleteness = completeness,
            hasSensorGap = false,
            orientationStale = false,
            observedSampleRateHz = observedRate,
            bufferOverflow = pending.feature.sampleCount > (config.maxWindowDurationMillis * config.accelerometerTargetRateHz / 1000) * 2,
            inFreeFall = false,
            longitudinalAccelMps2 = 0.0,
            phoneHandlingDetected = false,
            nonMonotonicTimestamp = false,
            gpsJumpDetected = false,
            distanceReached = distanceReached,
            sensorClipping = false
        )

        if (calState == CalibrationState.CALIBRATING && quality.state == QualityState.VALID) {
            calValidWindows++
            calValidDistance += authDistance
            calFeatures.add(pending.feature)
            if (calValidWindows >= config.calibrationRequiredValidWindows && calValidDistance >= config.calibrationRequiredDistanceMeters) {
                completeCalibration()
            }
        }

        if (quality.state != QualityState.REJECTED) {
            val sample = RoughnessSample(
                id = java.util.UUID.randomUUID().toString(),
                rideId = rideId ?: "calibration",
                startElapsedRealtimeNanos = pending.startNanos,
                endElapsedRealtimeNanos = pending.endNanos,
                midpointElapsedRealtimeNanos = pending.startNanos + pending.feature.durationMillis * 1_000_000L / 2,
                epochMillis = sessionEpochMillis + ((pending.startNanos - sessionElapsedRealtimeNanos) / 1_000_000L),
                latitude = midLoc?.point?.latitude ?: 0.0,
                longitude = midLoc?.point?.longitude ?: 0.0,
                meanSpeedMps = speed,
                speedDeltaMps = null,
                gpsAccuracyMeters = midLoc?.accuracyMeters ?: 0.0,
                speedAccuracyMps = midLoc?.speedAccuracyMps,
                windowDurationMillis = pending.feature.durationMillis,
                windowDistanceMeters = authDistance,
                sampleCount = pending.feature.sampleCount,
                expectedSampleCount = pending.feature.expectedSampleCount,
                verticalRms = pending.feature.verticalRms,
                p95AbsVerticalAccel = pending.feature.p95AbsVerticalAccel,
                peakAbsVerticalAccel = pending.feature.peakAbsVerticalAccel,
                baselineRatio = if (activeProfile != null) pending.feature.verticalRms / activeProfile!!.baselineVerticalRms else null,
                qualityStateCode = quality.state.code,
                qualityReasonFlags = quality.flags,
                calibrationProfileId = activeProfile?.id ?: "uncalibrated",
                algorithmVersion = config.algorithmVersion,
                speedModelVersion = config.speedModelVersion
            )
            coordinatorScope?.launch {
                persistence.saveSample(sample)
            }
        }
    }

    private fun completeCalibration() {
        if (calFeatures.isEmpty()) return
        
        val sortedRms = calFeatures.map { it.verticalRms }.sorted()
        val medianRms = sortedRms[sortedRms.size / 2]
        
        val sortedP95 = calFeatures.map { it.p95AbsVerticalAccel }.sorted()
        val medianP95 = sortedP95[sortedP95.size / 2]
        
        val iqr = if (sortedRms.size >= 4) {
            val q3 = sortedRms[(sortedRms.size * 0.75).toInt()]
            val q1 = sortedRms[(sortedRms.size * 0.25).toInt()]
            q3 - q1
        } else { 0.0 }
        
        val iqrRatio = if (medianRms > 0) iqr / medianRms else null

        val profile = RideCalibrationProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = "Auto Calibration",
            bikeLabel = null,
            mountLabel = null,
            deviceModel = android.os.Build.MODEL ?: "Unknown",
            baselineVerticalRms = medianRms,
            baselineP95 = medianP95,
            baselineIqrRatio = iqrRatio,
            baselineSpeedMps = calValidDistance / (calFeatures.sumOf { it.durationMillis } / 1000.0),
            acceptedWindowCount = calValidWindows,
            calibrationDistanceMeters = calValidDistance,
            algorithmVersion = config.algorithmVersion,
            speedModelVersion = config.speedModelVersion,
            createdAtMillis = System.currentTimeMillis(),
            isActive = true
        )
        
        activeProfile = profile
        calState = CalibrationState.MEASURING
        
        coordinatorScope?.launch {
            persistence.saveCalibrationProfile(profile)
        }
    }
}
