package pl.mazovia.offroad.domain.gpx

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxParserTest {

    private val parser = GpxParser()

    @Test
    fun `parse valid GPX with track`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <metadata><name>Test Track</name></metadata>
              <trk>
                <name>Morning Ride</name>
                <trkseg>
                  <trkpt lat="52.2297" lon="21.0122"><ele>100</ele></trkpt>
                  <trkpt lat="52.2300" lon="21.0130"><ele>101</ele></trkpt>
                  <trkpt lat="52.2310" lon="21.0140"><ele>102</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = parser.parse(ByteArrayInputStream(gpx.toByteArray()), "test.gpx")
        assertTrue(result is GpxParser.ParseResult.Success)
        val data = (result as GpxParser.ParseResult.Success).data

        assertEquals("Test Track", data.name)
        assertEquals(1, data.tracks.size)
        assertEquals("Morning Ride", data.tracks[0].name)
        assertEquals(3, data.totalPoints)
        assertEquals(1, data.segmentCount)
        assertEquals("test.gpx", data.sourceFileName)
    }

    @Test
    fun `parse GPX with multiple segments`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="52.0" lon="21.0"/>
                  <trkpt lat="52.1" lon="21.1"/>
                </trkseg>
                <trkseg>
                  <trkpt lat="52.2" lon="21.2"/>
                  <trkpt lat="52.3" lon="21.3"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = parser.parse(ByteArrayInputStream(gpx.toByteArray()))
        assertTrue(result is GpxParser.ParseResult.Success)
        val data = (result as GpxParser.ParseResult.Success).data
        assertEquals(2, data.segmentCount)
        assertEquals(4, data.totalPoints)
    }

    @Test
    fun `parse GPX with waypoints`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <wpt lat="52.0" lon="21.0">
                <name>Start</name>
                <desc>Starting point</desc>
              </wpt>
              <wpt lat="52.5" lon="21.5">
                <name>End</name>
              </wpt>
            </gpx>
        """.trimIndent()

        val result = parser.parse(ByteArrayInputStream(gpx.toByteArray()))
        assertTrue(result is GpxParser.ParseResult.Success)
        val data = (result as GpxParser.ParseResult.Success).data
        assertEquals(2, data.waypoints.size)
        assertEquals("Start", data.waypoints[0].name)
    }

    @Test
    fun `parse corrupted GPX returns error`() {
        val gpx = "<gpx><not-valid-xml"
        val result = parser.parse(ByteArrayInputStream(gpx.toByteArray()))
        assertTrue("Should return error for corrupted GPX", result is GpxParser.ParseResult.Error)
    }

    @Test
    fun `parse empty GPX returns empty data`() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1"></gpx>"""
        val result = parser.parse(ByteArrayInputStream(gpx.toByteArray()))
        assertTrue(result is GpxParser.ParseResult.Success)
        val data = (result as GpxParser.ParseResult.Success).data
        assertEquals(0, data.tracks.size)
        assertEquals(0, data.totalPoints)
    }
}
