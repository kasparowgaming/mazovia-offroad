package pl.mazovia.offroad.domain.gpx

import pl.mazovia.offroad.domain.model.*
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Real GPX parser using SAX for memory efficiency with large files.
 */
class GpxParser {

    sealed class ParseResult {
        data class Success(val data: GpxData) : ParseResult()
        data class Error(val message: String, val exception: Exception? = null) : ParseResult()
    }

    fun parse(inputStream: InputStream, fileName: String? = null): ParseResult {
        return try {
            val handler = GpxHandler()
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newSAXParser()
            parser.parse(inputStream, handler)

            ParseResult.Success(
                GpxData(
                    name = handler.gpxName,
                    description = handler.gpxDescription,
                    tracks = handler.tracks,
                    waypoints = handler.waypoints,
                    sourceFileName = fileName
                )
            )
        } catch (e: Exception) {
            ParseResult.Error(
                message = "Błąd parsowania pliku GPX: ${e.message}",
                exception = e
            )
        }
    }

    private class GpxHandler : DefaultHandler() {
        var gpxName: String? = null
        var gpxDescription: String? = null
        val tracks = mutableListOf<GpxTrack>()
        val waypoints = mutableListOf<GpxWaypoint>()

        private var currentTrack: TrackBuilder? = null
        private var currentSegment: SegmentBuilder? = null
        private var currentPoint: PointBuilder? = null
        private var currentWaypoint: WaypointBuilder? = null
        private val textContent = StringBuilder()
        private var inMetadata = false
        private var elementStack = mutableListOf<String>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes?) {
            val name = (qName ?: localName ?: "").lowercase()
            elementStack.add(name)
            textContent.clear()

            when (name) {
                "metadata" -> inMetadata = true
                "trk" -> currentTrack = TrackBuilder()
                "trkseg" -> currentSegment = SegmentBuilder()
                "trkpt" -> {
                    val lat = attrs?.getValue("lat")?.toDoubleOrNull()
                    val lon = attrs?.getValue("lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        currentPoint = PointBuilder(lat, lon)
                    }
                }
                "wpt" -> {
                    val lat = attrs?.getValue("lat")?.toDoubleOrNull()
                    val lon = attrs?.getValue("lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        currentWaypoint = WaypointBuilder(lat, lon)
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (qName ?: localName ?: "").lowercase()
            val text = textContent.toString().trim()

            when (name) {
                "metadata" -> inMetadata = false
                "name" -> {
                    when {
                        currentWaypoint != null -> currentWaypoint?.name = text
                        currentTrack != null && currentSegment == null -> currentTrack?.name = text
                        inMetadata || (elementStack.size <= 2) -> {
                            if (gpxName == null) gpxName = text
                        }
                    }
                }
                "desc" -> {
                    when {
                        currentWaypoint != null -> currentWaypoint?.description = text
                        inMetadata -> gpxDescription = text
                    }
                }
                "ele" -> currentPoint?.elevation = text.toDoubleOrNull()
                "time" -> currentPoint?.timestamp = parseGpxTimestamp(text)
                "speed" -> currentPoint?.speed = text.toDoubleOrNull()
                "trkpt" -> {
                    currentPoint?.let { pt ->
                        currentSegment?.points?.add(
                            GpxTrackPoint(
                                point = GeoPoint(pt.lat, pt.lon, pt.elevation),
                                timestampMillis = pt.timestamp,
                                speedMps = pt.speed
                            )
                        )
                    }
                    currentPoint = null
                }
                "trkseg" -> {
                    currentSegment?.let { seg ->
                        if (seg.points.isNotEmpty()) {
                            currentTrack?.segments?.add(GpxSegment(seg.points.toList()))
                        }
                    }
                    currentSegment = null
                }
                "trk" -> {
                    currentTrack?.let { trk ->
                        tracks.add(GpxTrack(trk.name, trk.segments.toList()))
                    }
                    currentTrack = null
                }
                "wpt" -> {
                    currentWaypoint?.let { wpt ->
                        waypoints.add(
                            GpxWaypoint(
                                point = GeoPoint(wpt.lat, wpt.lon),
                                name = wpt.name,
                                description = wpt.description
                            )
                        )
                    }
                    currentWaypoint = null
                }
            }

            if (elementStack.isNotEmpty()) elementStack.removeAt(elementStack.lastIndex)
            textContent.clear()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            textContent.append(ch, start, length)
        }

        private fun parseGpxTimestamp(text: String): Long? {
            return try {
                java.time.Instant.parse(text).toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
    }

    private data class TrackBuilder(
        var name: String? = null,
        val segments: MutableList<GpxSegment> = mutableListOf()
    )

    private data class SegmentBuilder(
        val points: MutableList<GpxTrackPoint> = mutableListOf()
    )

    private data class PointBuilder(
        val lat: Double,
        val lon: Double,
        var elevation: Double? = null,
        var timestamp: Long? = null,
        var speed: Double? = null
    )

    private data class WaypointBuilder(
        val lat: Double,
        val lon: Double,
        var name: String? = null,
        var description: String? = null
    )
}
