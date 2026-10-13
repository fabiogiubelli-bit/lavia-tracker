package cc.lavia.tracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxWriter {

    fun build(name: String, points: List<TrackPoint>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        fun time(ms: Long) = iso.format(Date(ms))

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"LAVIA Tracker\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"
        )
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        points.firstOrNull()?.let { sb.append("    <time>").append(time(it.timeMs)).append("</time>\n") }
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")

        var currentSegment: Int? = null
        for (p in points) {
            if (p.segment != currentSegment) {
                if (currentSegment != null) sb.append("    </trkseg>\n")
                sb.append("    <trkseg>\n")
                currentSegment = p.segment
            }
            sb.append("      <trkpt lat=\"")
                .append(String.format(Locale.US, "%.6f", p.lat))
                .append("\" lon=\"")
                .append(String.format(Locale.US, "%.6f", p.lon))
                .append("\">\n")
            p.ele?.let {
                sb.append("        <ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>\n")
            }
            sb.append("        <time>").append(time(p.timeMs)).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        if (currentSegment != null) sb.append("    </trkseg>\n")

        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
