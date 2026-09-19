package pl.mazovia.offroad.ui.map.components

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import org.maplibre.geojson.Point
import pl.mazovia.offroad.domain.model.GeoPoint

private const val TAG = "MapLibrePMTiles"

@Composable
fun MapLibrePMTilesPOCContainer(
    currentPosition: GeoPoint?,
    destination: GeoPoint?,
    routePoints: List<GeoPoint>,
    centerRequest: Long,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    isFollowMode: Boolean = false,
    bearing: Double? = null,
    speed: Double? = null,
    onUserPan: () -> Unit = {}
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
    
    // Explicit styleReady state to fix race conditions
    var styleReady by remember { mutableStateOf(false) }

    val routeSourceId = "route-source"
    val routeCasingLayerId = "route-casing-layer"
    val routeCoreLayerId = "route-core-layer"
    
    val gpsSourceId = "gps-source"
    val gpsLayerId = "gps-layer"
    
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
                                lineWidth(12f),
                                lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                                lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                            )
                        }
                        style.addLayer(casingLayer)
                        
                        val coreLayer = LineLayer(routeCoreLayerId, routeSourceId).apply {
                            setProperties(
                                lineColor(android.graphics.Color.parseColor("#00BFFF")),
                                lineWidth(6f),
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
                            "background-color": "#FFCDD2"
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
                    if (routePoints.size > 1) {
                        val points = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                        routeSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
                        Log.d(TAG, "ROUTE_POINTS=${points.size}")
                    } else {
                        routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                    }
                }
                
                val gpsSource = style.getSource(gpsSourceId) as? GeoJsonSource
                if (gpsSource != null) {
                    if (currentPosition != null) {
                        gpsSource.setGeoJson(Feature.fromGeometry(Point.fromLngLat(currentPosition.longitude, currentPosition.latitude)))
                        Log.d(TAG, "CURRENT_POSITION_APPLIED")
                    } else {
                        gpsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                    }
                }
            }
            
            // MapLibrePMTilesPOCContainer padding for rider follow
            val displayMetrics = mapView.context.resources.displayMetrics
            val bottomPaddingPx = (displayMetrics.heightPixels * 0.4).toInt()
            mapLibreMap.setPadding(0, 0, 0, bottomPaddingPx)
        },
        modifier = modifier
    )

    LaunchedEffect(centerRequest, currentPosition, bearing, isFollowMode, styleReady) {
        if (!styleReady) return@LaunchedEffect
        
        val mapLibreMap = mapLibreMapRef.value ?: return@LaunchedEffect
        
        if (isFollowMode && currentPosition != null) {
            val cameraBuilder = CameraPosition.Builder()
                .target(LatLng(currentPosition.latitude, currentPosition.longitude))
                .zoom(16.0)
            
            if (bearing != null) {
                cameraBuilder.bearing(bearing.toDouble())
            }
            
            mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraBuilder.build()), 1000)
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
