package pl.mazovia.offroad.domain.gpx

import pl.mazovia.offroad.domain.model.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * GPX file writer for exporting rides and routes.
 */
class GpxWriter {

    private val dateFormatter = DateTimeFormatter.ISO_INSTANT

    fun writeTrack(
        outputStream: OutputStream,
        trackName: String,
        points: List<GpxTrackPoint>,
        description: String? = null
    ) {
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Mazovia Offroad"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata>
    <name>${escapeXml(trackName)}</name>
${description?.let { "    <desc>${escapeXml(it)}</desc>\n" } ?: ""}    <time>${dateFormatter.format(Instant.now())}</time>
  </metadata>
  <trk>
    <name>${escapeXml(trackName)}</name>
    <trkseg>
""")
            for (point in points) {
                writer.write("      <trkpt lat=\"${point.point.latitude}\" lon=\"${point.point.longitude}\">\n")
                point.point.elevation?.let { writer.write("        <ele>$it</ele>\n") }
                point.timestampMillis?.let {
                    writer.write("        <time>${dateFormatter.format(Instant.ofEpochMilli(it))}</time>\n")
                }
                point.speedMps?.let { writer.write("        <speed>$it</speed>\n") }
                writer.write("      </trkpt>\n")
            }
            writer.write("""    </trkseg>
  </trk>
</gpx>
""")
        }
    }

    fun writeRoute(
        outputStream: OutputStream,
        route: Route,
        name: String = "Mazovia Offroad Route"
    ) {
        route.originalGpx?.let { writeOriginal(outputStream, it); return }
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Mazovia Offroad"
     xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>${escapeXml(name)}</name>
    <time>${dateFormatter.format(Instant.now())}</time>
  </metadata>
  <trk>
    <name>${escapeXml(name)}</name>
    <trkseg>
""")
            for (point in route.allPoints) {
                writer.write("      <trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">\n")
                point.elevation?.let { writer.write("        <ele>$it</ele>\n") }
                writer.write("      </trkpt>\n")
            }
            writer.write("""    </trkseg>
  </trk>
</gpx>
""")
        }
    }

    fun writeOriginal(outputStream: OutputStream, gpx: GpxData) {
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Mazovia Offroad\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            writer.write("  <metadata>\n")
            gpx.name?.let { writer.write("    <name>${escapeXml(it)}</name>\n") }
            gpx.description?.let { writer.write("    <desc>${escapeXml(it)}</desc>\n") }
            writer.write("  </metadata>\n")
            gpx.waypoints.forEach { waypoint ->
                writer.write("  <wpt lat=\"${waypoint.point.latitude}\" lon=\"${waypoint.point.longitude}\">\n")
                waypoint.name?.let { writer.write("    <name>${escapeXml(it)}</name>\n") }
                waypoint.description?.let { writer.write("    <desc>${escapeXml(it)}</desc>\n") }
                writer.write("  </wpt>\n")
            }
            gpx.tracks.forEach { track ->
                writer.write("  <trk>\n")
                track.name?.let { writer.write("    <name>${escapeXml(it)}</name>\n") }
                track.segments.forEach { segment ->
                    writer.write("    <trkseg>\n")
                    segment.points.forEach { point ->
                        writer.write("      <trkpt lat=\"${point.point.latitude}\" lon=\"${point.point.longitude}\">\n")
                        point.point.elevation?.let { writer.write("        <ele>$it</ele>\n") }
                        point.timestampMillis?.let { writer.write("        <time>${dateFormatter.format(Instant.ofEpochMilli(it))}</time>\n") }
                        point.speedMps?.let { writer.write("        <speed>$it</speed>\n") }
                        writer.write("      </trkpt>\n")
                    }
                    writer.write("    </trkseg>\n")
                }
                writer.write("  </trk>\n")
            }
            writer.write("</gpx>\n")
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
