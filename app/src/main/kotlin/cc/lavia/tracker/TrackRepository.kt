package cc.lavia.tracker

import android.content.Context
import android.location.Location
import java.io.File

/**
 * Stato condiviso della registrazione. Va usato solo dal thread principale
 * (il servizio riceve i fix GPS sul main looper).
 *
 * I punti vengono anche scritti su file man mano, così se Android chiude il processo
 * la traccia non va persa: alla riapertura l'app la ritrova in pausa.
 */
object TrackRepository {

    enum class State { IDLE, RECORDING, PAUSED }

    fun interface Listener {
        fun onTrackChanged()
    }

    private const val FILE_NAME = "current_track.csv"
    private const val ELEVATION_THRESHOLD_M = 3.0
    private const val MIN_STEP_M = 2.0
    private const val MAX_ACCURACY_M = 30f

    private val _points = ArrayList<TrackPoint>()
    val points: List<TrackPoint> get() = _points

    var state: State = State.IDLE
        private set
    var distanceM: Double = 0.0
        private set
    var elevationGainM: Double = 0.0
        private set
    var speedMs: Float = 0f
        private set

    private var accumulatedMs = 0L
    private var resumedAtMs = 0L
    private var segment = 0
    private var refEle: Double? = null
    private var file: File? = null
    private var initialized = false
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.onTrackChanged() }
    }

    /** Da chiamare una volta (Application/Activity/Service): recupera un'eventuale traccia interrotta. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val f = File(context.filesDir, FILE_NAME)
        file = f
        if (!f.exists()) return

        var prev: TrackPoint? = null
        var elapsed = 0L
        f.forEachLine { line ->
            val parts = line.split(",")
            if (parts.size < 5) return@forEachLine
            val p = try {
                TrackPoint(
                    lat = parts[0].toDouble(),
                    lon = parts[1].toDouble(),
                    ele = parts[2].toDoubleOrNull(),
                    timeMs = parts[3].toLong(),
                    segment = parts[4].toInt(),
                )
            } catch (e: NumberFormatException) {
                return@forEachLine
            }
            if (prev != null && prev!!.segment == p.segment) {
                elapsed += (p.timeMs - prev!!.timeMs).coerceAtLeast(0)
            }
            applyStats(prev, p)
            _points.add(p)
            prev = p
        }
        if (_points.isNotEmpty()) {
            accumulatedMs = elapsed
            segment = _points.last().segment + 1
            state = State.PAUSED
        }
    }

    fun start() {
        if (state != State.IDLE) return
        resetStats()
        file?.delete()
        segment = 0
        resumedAtMs = System.currentTimeMillis()
        state = State.RECORDING
        notifyChanged()
    }

    fun pause() {
        if (state != State.RECORDING) return
        accumulatedMs += System.currentTimeMillis() - resumedAtMs
        state = State.PAUSED
        speedMs = 0f
        notifyChanged()
    }

    fun resume() {
        if (state != State.PAUSED) return
        segment++ // la ripresa apre un nuovo segmento: niente linee dritte attraverso la pausa
        resumedAtMs = System.currentTimeMillis()
        state = State.RECORDING
        notifyChanged()
    }

    /** Scarta tutto e torna a IDLE (dopo il salvataggio o se l'utente scarta la traccia). */
    fun clear() {
        resetStats()
        file?.delete()
        state = State.IDLE
        notifyChanged()
    }

    fun snapshot(): List<TrackPoint> = _points.toList()

    fun elapsedMs(): Long =
        if (state == State.RECORDING) accumulatedMs + (System.currentTimeMillis() - resumedAtMs)
        else accumulatedMs

    fun addPoint(loc: Location) {
        if (state != State.RECORDING) return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return

        val p = TrackPoint(
            lat = loc.latitude,
            lon = loc.longitude,
            ele = if (loc.hasAltitude()) loc.altitude else null,
            timeMs = if (loc.time > 0) loc.time else System.currentTimeMillis(),
            segment = segment,
        )
        val prev = _points.lastOrNull()
        if (prev != null && prev.segment == p.segment && Geo.distanceM(prev, p) < MIN_STEP_M) {
            return // fermo o rumore GPS
        }
        applyStats(prev, p)
        speedMs = if (loc.hasSpeed()) loc.speed else 0f
        _points.add(p)
        try {
            file?.appendText("${p.lat},${p.lon},${p.ele ?: ""},${p.timeMs},${p.segment}\n")
        } catch (_: Exception) {
            // la persistenza è best-effort
        }
        notifyChanged()
    }

    private fun applyStats(prev: TrackPoint?, p: TrackPoint) {
        if (prev != null && prev.segment == p.segment) {
            distanceM += Geo.distanceM(prev, p)
        }
        p.ele?.let { e ->
            val r = refEle
            if (r == null) {
                refEle = e
            } else if (e - r >= ELEVATION_THRESHOLD_M) {
                elevationGainM += e - r
                refEle = e
            } else if (r - e >= ELEVATION_THRESHOLD_M) {
                refEle = e
            }
        }
    }

    private fun resetStats() {
        _points.clear()
        distanceM = 0.0
        elevationGainM = 0.0
        speedMs = 0f
        accumulatedMs = 0L
        resumedAtMs = 0L
        segment = 0
        refEle = null
    }
}
