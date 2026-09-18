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
    
    val pmtilesFile = java.io.File(context.getExternalFilesDir(null), "test.pmtiles")
    val fileExists = pmtilesFile.exists()
    
    Log.d(TAG, "FILE_PATH=${pmtilesFile.absolutePath}")
    Log.d(TAG, "FILE_EXISTS=$fileExists")
    if (fileExists) {
        Log.d(TAG, "FILE_SIZE=${pmtilesFile.length()}")
    }
    
    // PMTiles POC vector style
    val pmtilesStyleJson = """
    {
      "version": 8,
      "sources": {
        "pmtiles_source": {
          "type": "vector",
          "url": "pmtiles://file://${pmtilesFile.absolutePath}",
          "attribution": "Protomaps / PMTiles POC"
        }
      },
      "layers": [
        {
          "id": "background",
          "type": "background",
          "paint": {
            "background-color": "#EFEFEF"
          }
        },
        {
          "id": "earth",
          "type": "fill",
          "source": "pmtiles_source",
          "source-layer": "earth",
          "paint": {
            "fill-color": "#D7D7D7"
          }
        },
        {
          "id": "water",
          "type": "fill",
          "source": "pmtiles_source",
          "source-layer": "water",
          "paint": {
            "fill-color": "#81D4FA"
          }
        },
        {
          "id": "roads",
          "type": "line",
          "source": "pmtiles_source",
          "source-layer": "roads",
          "paint": {
            "line-color": "#9E9E9E",
            "line-width": 2
          }
        },
        {
          "id": "buildings",
          "type": "fill",
          "source": "pmtiles_source",
          "source-layer": "buildings",
          "paint": {
            "fill-color": "#BDBDBD"
          }
        }
      ]
    }
    """.trimIndent()

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
                    .target(LatLng(43.77, 11.25)) // Firenze POC
                    .zoom(13.0)
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

                if (fileExists) {
                    mapLibreMap.setStyle(Style.Builder().fromJson(pmtilesStyleJson)) { style ->
                        Log.d(TAG, "SOURCE_ADDED=pmtiles_source")
                        Log.d(TAG, "SOURCE_LAYER=earth,water,roads,buildings")
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
            
            // MapLibrePMTilesPOCContainer does not use top padding for rider follow
            mapLibreMap.setPadding(0, 0, 0, 0)
        },
        modifier = modifier
    )

    LaunchedEffect(centerRequest, currentPosition, bearing, isFollowMode, styleReady) {
        if (!styleReady) return@LaunchedEffect
        // INTENTIONALLY DISABLED FOR POC-B: 
        // We do not want the GPS position to override the camera while verifying Firenze vector tiles.
        // The GPS marker will still update on the map, but the camera will remain free.
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
