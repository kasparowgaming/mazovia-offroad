package pl.mazovia.offroad.ui.map.components

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import pl.mazovia.offroad.domain.model.GeoPoint
import kotlin.math.roundToInt

private const val TAG = "MapLibrePMTiles"

/** Planning: bottom padding as a fraction of the screen height (focal point above the bottom panels). */
internal const val PLANNING_BOTTOM_PADDING_FRACTION = 0.4
/** Riding: focal point at this fraction of the map view height below the top overlays (route ahead stays visible). */
internal const val RIDING_FOCAL_FRACTION = 0.6
/** Riding: minimum distance between the focal point and the top overlay / bottom view edge. */
internal val RIDING_FOCAL_CLEARANCE = 32.dp

internal data class MapPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Camera content padding for the map view.
 *
 * [safeTopInsetPx] null keeps the planning padding, derived from [screenHeightPx]. Otherwise (RIDING) the padding is
 * derived from the actual map view height: the camera target sits below the top overlays ending at [safeTopInsetPx]
 * (view coordinates), at least [clearancePx] away from them and from the bottom edge where the view allows it.
 */
internal fun cameraPadding(screenHeightPx: Int, viewHeightPx: Int, safeTopInsetPx: Int?, clearancePx: Int): MapPadding {
    if (safeTopInsetPx == null) return MapPadding(0, 0, 0, (screenHeightPx * PLANNING_BOTTOM_PADDING_FRACTION).toInt())
    if (viewHeightPx < 2) return MapPadding(0, 0, 0, 0)
    val top = safeTopInsetPx.coerceIn(0, viewHeightPx)
    val focal = (top + RIDING_FOCAL_FRACTION * (viewHeightPx - top)).roundToInt()
        .coerceAtMost(viewHeightPx - clearancePx)
        .coerceAtLeast(top + clearancePx)
        .coerceIn(1, viewHeightPx - 1)
    // MapLibre centres the target in the padded area: focal = top + (height - top - bottom) / 2.
    val offset = 2 * focal - viewHeightPx
    return if (offset >= 0) MapPadding(0, offset, 0, 0) else MapPadding(0, 0, 0, -offset)
}

/**
 * @param safeTopInsetPx RIDING only: bottom edge (px, map view coordinates) of the persistent overlays drawn over the
 * top of the map. Null keeps the planning camera padding.
 */
@Composable
fun MapLibrePMTilesPOCContainer(
    currentPosition: GeoPoint?,
    destination: GeoPoint?,
    routePoints: List<GeoPoint>,
    routeSegments: List<List<GeoPoint>>? = null,
    waypointPoints: List<GeoPoint> = emptyList(),
    centerRequest: Long,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    isFollowMode: Boolean = false,
    bearing: Double? = null,
    speed: Double? = null,
    zoomSteps: Int = 0,
    onUserPan: () -> Unit = {},
    safeTopInsetPx: Int? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Ensure MapLibre SDK initialization happens BEFORE the first MapView instance is constructed.
    val applicationContext = context.applicationContext
    remember {
        MapLibre.getInstance(applicationContext)
        true
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val mapLibreMapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    
    var lastCenterRequest: Long? by remember { mutableStateOf(null) }
    var lastCameraBearing: Double? by remember { mutableStateOf(null) }
    val markerMotion = remember { MarkerMotion() }
    
    // Explicit styleReady state to fix race conditions
    var styleReady by remember { mutableStateOf(false) }

    // Actual map view height; the riding padding is derived from it, not from the screen.
    var viewHeightPx by remember { mutableIntStateOf(0) }
    val clearancePx = with(LocalDensity.current) { RIDING_FOCAL_CLEARANCE.roundToPx() }
    val padding = cameraPadding(context.resources.displayMetrics.heightPixels, viewHeightPx, safeTopInsetPx, clearancePx)

    val zoomConsumer = remember { MapZoomConsumer() }
    LaunchedEffect(zoomSteps, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val map = mapLibreMapRef.value ?: return@LaunchedEffect
        zoomConsumer.apply(zoomSteps, map.cameraPosition.zoom, map.minZoomLevel, map.maxZoomLevel) {
            map.moveCamera(CameraUpdateFactory.zoomTo(it))
        }
    }

    val routeSourceId = "route-source"
    val routeCasingLayerId = "route-casing-layer"
    val routeCoreLayerId = "route-core-layer"
    
    val gpsSourceId = "gps-source"
    val gpsLayerId = "gps-layer"
    
    val destSourceId = "dest-source"
    val destLayerId = "dest-layer"
    val waypointSourceId = "gpx-waypoints"
    val waypointLayerId = "gpx-waypoint-layer"
    
    val pmtilesFile = java.io.File(context.getExternalFilesDir(null), "mazowieckie_offroad.pmtiles")
    val fileExists = pmtilesFile.exists()
    
    Log.d(TAG, "LOCAL_FILE_PATH=${pmtilesFile.absolutePath}")
    Log.d(TAG, "LOCAL_FILE_EXISTS=$fileExists")
    Log.d(TAG, "LOCAL_FILE_SIZE=${if(fileExists) pmtilesFile.length() else 0}")
    
    // Load style from assets
    val pmtilesStyleJson = remember(fileExists) {
        if (fileExists) {
            try {
                context.assets.open("mapstyles/mazovia_offroad_v1.json").bufferedReader().use {
                    it.readText().replace("{PMTILES_URI}", "pmtiles://file://${pmtilesFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load style", e)
                ""
            }
        } else {
            ""
        }
    }

    AndroidView(
        factory = { ctx ->
            val mapView = MapView(ctx)
            mapViewRef.value = mapView
            
            mapView.onCreate(null)
            
            mapView.getMapAsync { mapLibreMap ->
                mapLibreMapRef.value = mapLibreMap
                
                mapLibreMap.uiSettings.isAttributionEnabled = true
                mapLibreMap.uiSettings.isLogoEnabled = false
                mapLibreMap.uiSettings.isCompassEnabled = false
                
                mapLibreMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(52.2, 21.0)) // Warsaw approximate
                    .zoom(8.0)
                    .build()

                mapLibreMap.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                        onUserPan()
                    }
                    override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                    override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                })
                
                mapLibreMap.addOnMapLongClickListener { point ->
                    onLongPress(GeoPoint(point.latitude, point.longitude))
                    true
                }

                if (fileExists && pmtilesStyleJson.isNotEmpty()) {
                    mapLibreMap.setStyle(Style.Builder().fromJson(pmtilesStyleJson)) { style ->
                        Log.d(TAG, "SOURCE_ADDED=pmtiles_source")
                        Log.d(TAG, "MAP_RENDERED=true")
                        
                        style.addSource(GeoJsonSource(routeSourceId))
                        
                        val casingLayer = LineLayer(routeCasingLayerId, routeSourceId).apply {
                            setProperties(
                                lineColor(android.graphics.Color.parseColor("#0D47A1")),
                                lineWidth(9.5f),
                                lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                                lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                            )
                        }
                        style.addLayer(casingLayer)
                        
                        val coreLayer = LineLayer(routeCoreLayerId, routeSourceId).apply {
                            setProperties(
                                lineColor(android.graphics.Color.parseColor("#00BFFF")),
                                lineWidth(4.5f),
                                lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                                lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                            )
                        }
                        style.addLayerAbove(coreLayer, routeCasingLayerId)
                        
                        style.addSource(GeoJsonSource(gpsSourceId))
                        val gpsLayer = CircleLayer(gpsLayerId, gpsSourceId).apply {
                            setProperties(
                                circleColor(android.graphics.Color.parseColor("#00E5FF")),
                                circleRadius(8f),
                                circleStrokeColor(android.graphics.Color.WHITE),
                                circleStrokeWidth(2f)
                            )
                        }
                        style.addLayerAbove(gpsLayer, routeCoreLayerId)
                        
                        style.addSource(GeoJsonSource(destSourceId))
                        val destLayer = CircleLayer(destLayerId, destSourceId).apply {
                            setProperties(
                                circleColor(android.graphics.Color.parseColor("#FF1744")),
                                circleRadius(10f),
                                circleStrokeColor(android.graphics.Color.WHITE),
                                circleStrokeWidth(2f)
                            )
                        }
                        style.addLayerAbove(destLayer, routeCoreLayerId)
                        style.addSource(GeoJsonSource(waypointSourceId))
                        style.addLayerAbove(CircleLayer(waypointLayerId, waypointSourceId).apply {
                            setProperties(
                                circleColor(android.graphics.Color.parseColor("#FFB300")),
                                circleRadius(5f),
                                circleStrokeColor(android.graphics.Color.WHITE),
                                circleStrokeWidth(1.5f)
                            )
                        }, routeCoreLayerId)
                        
                        Log.d(TAG, "STYLE_READY=true")
                        styleReady = true
                    }
                } else {
                    // Fallback to blank if file missing
                    val blankStyle = """
                    {
                      "version": 8,
                      "sources": {},
                      "layers": [
                        {
                          "id": "background",
                          "type": "background",
                          "paint": {
                            "background-color": "#18181A"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                    mapLibreMap.setStyle(Style.Builder().fromJson(blankStyle)) {
                        Log.d(TAG, "STYLE_READY=false (FILE MISSING)")
                    }
                }
            }
            mapView
        },
        update = { mapView ->
            if (!styleReady) return@AndroidView
            
            val mapLibreMap = mapLibreMapRef.value ?: return@AndroidView
            mapLibreMap.getStyle { style ->
                val routeSource = style.getSource(routeSourceId) as? GeoJsonSource
                if (routeSource != null) {
                    if (routeSegments != null) {
                        val lines = routeSegments.filter { it.size > 1 }.map { segment ->
                            segment.map { Point.fromLngLat(it.longitude, it.latitude) }
                        }
                        if (lines.isEmpty()) routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                        else routeSource.setGeoJson(Feature.fromGeometry(MultiLineString.fromLngLats(lines)))
                    } else if (routePoints.size > 1) {
                        val points = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                        routeSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
                        Log.d(TAG, "ROUTE_POINTS=${points.size}")
                    } else {
                        routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                    }
                }
                
                // The position marker is drawn by the glide effect below, not here.

                val destSource = style.getSource(destSourceId) as? GeoJsonSource
                if (destSource != null) {
                    if (destination != null) {
                        destSource.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.longitude, destination.latitude)))
                    } else {
                        destSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                    }
                }
                val waypointSource = style.getSource(waypointSourceId) as? GeoJsonSource
                waypointSource?.setGeoJson(FeatureCollection.fromFeatures(waypointPoints.map {
                    Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
                }))
            }
            
            // MapLibrePMTilesPOCContainer padding for rider follow
            mapLibreMap.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        },
        modifier = modifier.onSizeChanged { viewHeightPx = it.height }
    )

    // Glide the position marker between fixes, one source update per frame, only while it moves.
    LaunchedEffect(currentPosition, styleReady) {
        markerMotion.update(currentPosition, SystemClock.uptimeMillis())
        if (!styleReady) return@LaunchedEffect
        val gpsSource = mapLibreMapRef.value?.style?.getSource(gpsSourceId) as? GeoJsonSource ?: return@LaunchedEffect
        fun draw(now: Long) {
            val shown = markerMotion.positionAt(now)
            if (shown == null) gpsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            else gpsSource.setGeoJson(Feature.fromGeometry(Point.fromLngLat(shown.longitude, shown.latitude)))
        }
        while (markerMotion.isGliding(SystemClock.uptimeMillis())) {
            withFrameNanos { }
            draw(SystemClock.uptimeMillis())
        }
        draw(SystemClock.uptimeMillis())
    }

    LaunchedEffect(centerRequest, currentPosition, bearing, isFollowMode, styleReady, padding) {
        if (!styleReady) return@LaunchedEffect

        val mapLibreMap = mapLibreMapRef.value ?: return@LaunchedEffect
        markerMotion.update(currentPosition, SystemClock.uptimeMillis())

        if (isFollowMode && currentPosition != null) {
            val centerChanged = lastCenterRequest != centerRequest
            
            // Calculate angularDiff handling nulls and 0/360 wrap
            var applyNewBearing = false
            val incomingBearing = bearing
            
            if (incomingBearing != null) {
                if (lastCameraBearing == null || centerChanged) {
                    applyNewBearing = true
                } else {
                    val diff = kotlin.math.abs(incomingBearing - lastCameraBearing!!)
                    val minDiff = kotlin.math.min(diff, 360.0 - diff)
                    if (minDiff >= 3.0) {
                        applyNewBearing = true
                    }
                }
            }

            // Padding travels with the camera update, so the target lands on the focal point even if setPadding lags.
            val builder = CameraPosition.Builder()
                .target(LatLng(currentPosition.latitude, currentPosition.longitude))
                .padding(padding.left.toDouble(), padding.top.toDouble(), padding.right.toDouble(), padding.bottom.toDouble())
                
            // Zoom logic: Zoom 16.0 only when centerRequest changes.
            if (centerChanged) {
                builder.zoom(16.0)
            } else {
                builder.zoom(mapLibreMap.cameraPosition.zoom)
            }

            // Bearing logic
            if (applyNewBearing && incomingBearing != null) {
                builder.bearing(incomingBearing)
                lastCameraBearing = incomingBearing
            } else if (lastCameraBearing != null) {
                builder.bearing(lastCameraBearing!!)
            }

            val update = CameraUpdateFactory.newCameraPosition(builder.build())
            val glideMs = markerMotion.remainingMs(SystemClock.uptimeMillis())
            if (centerChanged || glideMs <= 0L) {
                mapLibreMap.animateCamera(update, 1000)
            } else {
                // Same linear glide and end time as the marker, so the dot stays on the focal point.
                mapLibreMap.easeCamera(update, glideMs.toInt(), false)
            }

            lastCenterRequest = centerRequest
        } else if (!isFollowMode && currentPosition != null && lastCenterRequest != centerRequest) {
            mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(currentPosition.latitude, currentPosition.longitude), 16.0), 1000)
            lastCenterRequest = centerRequest
        }
    }

    LaunchedEffect(routePoints, styleReady) {
        if (!styleReady || routePoints.isEmpty() || isFollowMode) return@LaunchedEffect
        val mapLibreMap = mapLibreMapRef.value ?: return@LaunchedEffect
        
        if (routePoints.size > 1) {
            val builder = org.maplibre.android.geometry.LatLngBounds.Builder()
            routePoints.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            try {
                mapLibreMap.easeCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 150), 1000)
            } catch (e: Exception) {
                // Ignore if bounds are too small or invalid
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mapView = mapViewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.onDestroy()
        }
    }
}
