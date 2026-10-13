package cc.lavia.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxWriterTest {

    private val t0 = 1_760_380_800_000L // 2025-10-13T18:40:00Z

    @Test
    fun `writes one trkseg per segment with ISO times`() {
        val points = listOf(
            TrackPoint(45.4642, 9.19, 120.0, t0, 0),
            TrackPoint(45.4650, 9.1910, 121.5, t0 + 5_000, 0),
            TrackPoint(45.4700, 9.2000, null, t0 + 600_000, 1),
        )
        val gpx = GpxWriter.build("Giro di prova", points)

        assertTrue(gpx.startsWith("<?xml"))
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(3, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("lat=\"45.464200\" lon=\"9.190000\""))
        assertTrue(gpx.contains("<ele>121.5</ele>"))
        assertTrue(gpx.contains("<time>2025-10-13T18:40:00Z</time>"))
    }

    @Test
    fun `escapes xml special characters in the name`() {
        val gpx = GpxWriter.build("Cima & <Rifugio>", listOf(TrackPoint(1.0, 2.0, null, t0, 0)))
        assertTrue(gpx.contains("Cima &amp; &lt;Rifugio&gt;"))
    }

    @Test
    fun `distance between two known points is plausible`() {
        // Milano Duomo -> Milano Centrale: circa 2.5-3 km
        val a = TrackPoint(45.4641, 9.1919, null, 0, 0)
        val b = TrackPoint(45.4863, 9.2045, null, 0, 0)
        val d = Geo.distanceM(a, b)
        assertTrue("distanza $d", d in 2_300.0..3_000.0)
    }
}
