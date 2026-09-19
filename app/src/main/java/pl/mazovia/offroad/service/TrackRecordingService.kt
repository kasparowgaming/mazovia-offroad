package pl.mazovia.offroad.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.mazovia.offroad.MazoviaOffroadApp
import pl.mazovia.offroad.R
import pl.mazovia.offroad.domain.model.*
import java.util.UUID

/**
 * Foreground service for GPS track recording.
 * Persists checkpoints and survives screen off / app switching.
 */
class TrackRecordingService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null

    private val _recordingState = MutableStateFlow(
        RecordingState(status = RecordingStatus.IDLE)
    )
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val trackPoints = mutableListOf<GpxTrackPoint>()
    private var trackId: String = ""
    private var startTimeMillis: Long = 0
    private var totalDistanceMeters: Double = 0.0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startRecording() {
        trackId = UUID.randomUUID().toString()
        startTimeMillis = System.currentTimeMillis()
        totalDistanceMeters = 0.0
        trackPoints.clear()

        _recordingState.value = RecordingState(
            status = RecordingStatus.RECORDING,
            trackId = trackId,
            startTimeMillis = startTimeMillis
        )

        startForegroundWithNotification()
        android.util.Log.d("RideLifecycle", "FOREGROUND_SERVICE_STARTED")
        startLocationUpdates()
    }

    private fun pauseRecording() {
        recordingJob?.cancel()
        _recordingState.value = _recordingState.value.copy(
            status = RecordingStatus.PAUSED
        )
        updateNotification("PAUZA")
    }

    private fun resumeRecording() {
        _recordingState.value = _recordingState.value.copy(
            status = RecordingStatus.RECORDING
        )
        startLocationUpdates()
        updateNotification("NAGRYWANIE")
    }

    private fun stopRecording() {
        android.util.Log.d("StopTrace", "TRACK_RECORDING_STOPPED=true")
        
        val app = application as pl.mazovia.offroad.MazoviaOffroadApp
        
        if (_recordingState.value.status != RecordingStatus.RECORDING) {
            android.util.Log.d("StopTrace", "TRACK_RECORDING_STOPPED_FAILED_NOT_RECORDING (status: ${_recordingState.value.status}) - FORCING CLEAR SESSION")
            serviceScope.launch {
                app.sessionRepository.clearRecordingSession()
                android.util.Log.d("StopTrace", "SESSION_FINALIZED=true")
            }
            stopLocationUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        
        android.util.Log.d("RideLifecycle", "RECORDING_FINALIZED")
        val ride = Ride(
            id = trackId ?: return,
            startTimeMillis = startTimeMillis,
            endTimeMillis = System.currentTimeMillis(),
            distanceMeters = totalDistanceMeters,
            durationSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000,
            trackPoints = trackPoints.toList()
        )

        serviceScope.launch {
            // Save ride
            app.rideRepository.saveRide(ride)
            app.rideRepository.saveTrackPoints(trackId, trackPoints)
            app.sessionRepository.clearRecordingSession()
            android.util.Log.d("RideLifecycle", "RIDE_PERSISTED")
            android.util.Log.d("StopTrace", "SESSION_FINALIZED=true")
        }

        _recordingState.value = RecordingState(
            status = RecordingStatus.STOPPED,
            trackId = trackId,
            startTimeMillis = startTimeMillis
        )
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }


    private fun stopLocationUpdates() {
        recordingJob?.cancel()
    }

    private fun startLocationUpdates() {
        val app = application as MazoviaOffroadApp
        recordingJob = serviceScope.launch {
            try {
                app.locationClient.getLocationUpdates(3000L).collect { locationUpdate ->
                    val currentState = _recordingState.value
                    if (currentState.status == RecordingStatus.RECORDING) {
                        val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000

                        val newPoint = GpxTrackPoint(
                            point = locationUpdate.point,
                            timestampMillis = System.currentTimeMillis(),
                            speedMps = locationUpdate.speedMps
                        )

                        if (trackPoints.isNotEmpty()) {
                            val lastPoint = trackPoints.last()
                            val dist = locationUpdate.point.distanceTo(lastPoint.point)
                            if (dist > 0.0) {
                                totalDistanceMeters += dist
                                android.util.Log.d("RideLifecycle", "RIDE_DISTANCE_UPDATED: $totalDistanceMeters")
                            }
                        }
                        
                        trackPoints.add(newPoint)
                        android.util.Log.d("RideLifecycle", "TRACK_POINT_RECORDED: lat=${newPoint.point.latitude}, lon=${newPoint.point.longitude}")

                        // Save checkpoint to session repository
                        app.sessionRepository.saveRecordingSession(
                            RecordingState(
                                status = RecordingStatus.RECORDING,
                                trackId = trackId,
                                startTimeMillis = startTimeMillis,
                                distanceMeters = totalDistanceMeters,
                                durationSeconds = elapsed,
                                pointCount = trackPoints.size
                            )
                        )

                        _recordingState.value = currentState.copy(
                            durationSeconds = elapsed,
                            distanceMeters = totalDistanceMeters,
                            pointCount = trackPoints.size
                        )

                        updateNotification("NAGRYWANIE ${elapsed / 60}min")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Handle permission or disabled GPS errors
                updateNotification("BŁĄD GPS: ${e.message}")
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("NAGRYWANIE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MazoviaOffroadApp.RECORDING_CHANNEL_ID)
            .setContentTitle("Mazovia Offroad")
            .setContentText("$statusText | ${String.format("%.1f", totalDistanceMeters / 1000)} km")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        android.util.Log.d("RideLifecycle", "TRACK_RECORDING_SERVICE_STOP")
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "pl.mazovia.offroad.START_RECORDING"
        const val ACTION_PAUSE = "pl.mazovia.offroad.PAUSE_RECORDING"
        const val ACTION_RESUME = "pl.mazovia.offroad.RESUME_RECORDING"
        const val ACTION_STOP = "pl.mazovia.offroad.STOP_RECORDING"
        const val NOTIFICATION_ID = 1001
    }
}
