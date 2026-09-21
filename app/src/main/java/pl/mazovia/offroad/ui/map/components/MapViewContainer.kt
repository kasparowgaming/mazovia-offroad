package pl.mazovia.offroad.ui.map.components

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pl.mazovia.offroad.domain.model.GeoPoint
import androidx.compose.ui.platform.LocalContext
import android.view.MotionEvent
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import android.util.Log

@Composable
fun MapViewContainer(
    currentPosition: GeoPoint?,
    destination: GeoPoint?,
    routePoints: List<GeoPoint>,
    centerRequest: Long,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    isFollowMode: Boolean = false,
    bearing: Double? = null,
    speed: Double? = null,
    zoomSteps: Int = 0,
    onUserPan: () -> Unit = {}
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        Configuration.getInstance().userAgentValue = "MazoviaOffroad/1.0"
        onDispose { }
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val zoomConsumer = remember { MapZoomConsumer() }
    LaunchedEffect(zoomSteps, mapViewRef.value) {
        val map = mapViewRef.value ?: return@LaunchedEffect
        zoomConsumer.apply(zoomSteps, map.zoomLevelDouble, map.minZoomLevel, map.maxZoomLevel) {
            map.controller.setZoom(it)
        }
    }

    val currentPosMarker = remember { mutableStateOf<Marker?>(null) }
    val destMarker = remember { mutableStateOf<Marker?>(null) }
    val routeLineCasing = remember { mutableStateOf<Polyline?>(null) }
    val routeLineCore = remember { mutableStateOf<Polyline?>(null) }

    val animatedBearing = remember { androidx.compose.animation.core.Animatable(0f) }
    val lastValidBearing = remember { mutableFloatStateOf(0f) }

    // Smooth heading rotation interpolation
    LaunchedEffect(bearing, speed, isFollowMode) {
        if (!isFollowMode) {
            mapViewRef.value?.setMapOrientation(0f)
            return@LaunchedEffect
        }
        
        if (bearing != null) {
            val currentSpeed = speed ?: 0.0
            // Low speed stability: only update target bearing if speed > ~3 km/h (0.8 m/s)
            if (currentSpeed > 0.8) {
                lastValidBearing.floatValue = bearing.toFloat()
            }
            
            val target = lastValidBearing.floatValue
            val currentAnim = animatedBearing.value
            
            // Shortest path normalization (-180 to 180)
            val delta = ((target - currentAnim + 540) % 360) - 180
            val newTarget = currentAnim + delta
            
            // Simple deadband to avoid micro-jitters
            if (kotlin.math.abs(delta) > 1f) {
                animatedBearing.animateTo(
                    targetValue = newTarget,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 400, 
                        easing = androidx.compose.animation.core.LinearEasing
                    )
                ) {
                    mapViewRef.value?.setMapOrientation(360f - this.value)
                    mapViewRef.value?.invalidate()
                }
            } else {
                // If it's very close, just snap it without animating to save frames
                animatedBearing.snapTo(newTarget)
                mapViewRef.value?.setMapOrientation(360f - newTarget)
                mapViewRef.value?.invalidate()
            }
        }
    }

    LaunchedEffect(centerRequest) {
        if (centerRequest > 0L) {
            currentPosition?.let { pos ->
                if (isFollowMode) {
                    mapViewRef.value?.controller?.setZoom(17.5)
                }
                mapViewRef.value?.controller?.animateTo(OsmGeoPoint(pos.latitude, pos.longitude))
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)

                // Detect user pan
                setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_MOVE) {
                        onUserPan()
                    }
                    false
                }
                
                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean = false
                    override fun longPressHelper(p: OsmGeoPoint): Boolean {
                        onLongPress(GeoPoint(p.latitude, p.longitude))
                        return true
                    }
                })
                overlays.add(eventsOverlay)

                val cMarker = Marker(this).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Moja pozycja"
                    icon = androidx.core.content.ContextCompat.getDrawable(ctx, pl.mazovia.offroad.R.drawable.ic_current_position)
                }
                currentPosMarker.value = cMarker
                
                val dMarker = Marker(this).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Cel"
                }
                destMarker.value = dMarker

                // High-contrast route styling (casing + core)
                val casing = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor("#0D47A1") // Darker blue casing
                    outlinePaint.strokeWidth = 24f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                }
                routeLineCasing.value = casing

                val core = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor("#00BFFF") // Bright cyan/blue core
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                }
                routeLineCore.value = core

                overlays.add(casing)
                overlays.add(core)
                overlays.add(dMarker)
                overlays.add(cMarker)

                controller.setCenter(OsmGeoPoint(52.2297, 21.0122)) // Default
                mapViewRef.value = this
            }
        },
        update = { mapView ->
            // Apply follow mode camera constraints
            if (isFollowMode && currentPosition != null) {
                mapView.setMapCenterOffset(0, mapView.height / 4) // Offset rider to ~62.5% vertical
                
                // Only smoothly animate to new center on GPS ticks
                mapView.controller.animateTo(OsmGeoPoint(currentPosition.latitude, currentPosition.longitude))
                // Note: Orientation is managed by the LaunchedEffect animatedBearing
                // Note: Zoom is managed by the recenter button to allow pinch-zoom even while following
            } else {
                // If not in follow mode, reset offset
                mapView.setMapCenterOffset(0, 0)
                // Note: Orientation reset is also handled by LaunchedEffect
            }

            // Update Current Position Marker
            val cMarker = currentPosMarker.value
            if (cMarker != null) {
                if (currentPosition != null) {
                    cMarker.position = OsmGeoPoint(currentPosition.latitude, currentPosition.longitude)
                    cMarker.setAlpha(1f)
                } else {
                    cMarker.setAlpha(0f)
                }
            }

            // Update Destination Marker
            val dMarker = destMarker.value
            if (dMarker != null) {
                if (destination != null) {
                    dMarker.position = OsmGeoPoint(destination.latitude, destination.longitude)
                    dMarker.setAlpha(1f)
                } else {
                    dMarker.setAlpha(0f)
                }
            }

            // Update Route Line
            val casing = routeLineCasing.value
            val core = routeLineCore.value
            if (casing != null && core != null) {
                if (routePoints.isNotEmpty()) {
                    val pts = routePoints.map { OsmGeoPoint(it.latitude, it.longitude) }
                    casing.setPoints(pts)
                    core.setPoints(pts)
                    casing.isVisible = true
                    core.isVisible = true
                } else {
                    casing.isVisible = false
                    core.isVisible = false
                }
            }

            mapView.invalidate()
        },
        modifier = modifier
    )
}
