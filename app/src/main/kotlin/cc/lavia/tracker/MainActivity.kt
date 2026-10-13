package cc.lavia.tracker

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import cc.lavia.tracker.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TrackRepository.Listener {

    private lateinit var b: ActivityMainBinding

    private var styleReady = false
    private var following = true
    private var firstFixDone = false
    private var lastPuckPoint: Point? = null

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStats()
            ui.postDelayed(this, 1_000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                enableLocationPuck(reset = true)
            } else {
                Toast.makeText(this, "Senza permesso di posizione non posso registrare.", Toast.LENGTH_LONG).show()
            }
        }

    private val positionListener = OnIndicatorPositionChangedListener { point ->
        lastPuckPoint = point
        if (following) {
            val camera = CameraOptions.Builder().center(point)
            if (!firstFixDone) {
                camera.zoom(15.5)
                firstFixDone = true
            }
            b.mapView.mapboxMap.setCamera(camera.build())
        }
    }

    private val moveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            following = false
        }

        override fun onMove(detector: MoveGestureDetector): Boolean = false
        override fun onMoveEnd(detector: MoveGestureDetector) {}
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        TrackRepository.init(applicationContext)

        val token = resolveToken()
        if (token.isBlank()) {
            askToken(first = true)
            return
        }
        MapboxOptions.accessToken = token

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets()
        setupMap()
        setupButtons()

        if (!hasLocationPermission()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized) return
        TrackRepository.addListener(this)
        refreshStats()
        updateRoute()
        ui.post(tick)
    }

    override fun onStop() {
        if (::b.isInitialized) {
            TrackRepository.removeListener(this)
            ui.removeCallbacks(tick)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::b.isInitialized) {
            b.mapView.location.removeOnIndicatorPositionChangedListener(positionListener)
            b.mapView.gestures.removeOnMoveListener(moveListener)
        }
        super.onDestroy()
    }

    override fun onTrackChanged() {
        refreshStats()
        updateRoute()
    }

    // ---------------------------------------------------------------- token

    private fun resolveToken(): String {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, null)
        return saved?.takeIf { it.isNotBlank() } ?: getString(R.string.lavia_mapbox_token)
    }

    private fun askToken(first: Boolean) {
        val input = EditText(this).apply {
            hint = "pk.eyJ1..."
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, ""))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Token Mapbox")
            .setMessage(
                "Per mostrare la mappa serve un token pubblico gratuito di Mapbox " +
                    "(inizia con \"pk.\"). Lo crei in pochi minuti su account.mapbox.com."
            )
            .setView(container)
            .setCancelable(!first)
            .setPositiveButton("Salva") { _, _ ->
                val t = input.text.toString().trim()
                if (!t.startsWith("pk.")) {
                    Toast.makeText(this, "Il token pubblico deve iniziare con \"pk.\"", Toast.LENGTH_LONG).show()
                    askToken(first)
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, t).apply()
                    recreate()
                }
            }
            .setNeutralButton("Apri Mapbox") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.mapbox.com/access-tokens/")))
                askToken(first)
            }
        if (!first) builder.setNegativeButton("Annulla", null)
        builder.show()
    }

    // ---------------------------------------------------------------- map

    private fun setupMap() {
        val map = b.mapView.mapboxMap
        map.loadStyle(Style.OUTDOORS) { style ->
            style.addSource(
                geoJsonSource(ROUTE_SOURCE) {
                    featureCollection(FeatureCollection.fromFeatures(ArrayList<Feature>()))
                }
            )
            style.addLayer(
                lineLayer(ROUTE_LAYER, ROUTE_SOURCE) {
                    lineColor("#E8590C")
                    lineWidth(5.0)
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                }
            )
            styleReady = true
            updateRoute()
        }

        enableLocationPuck(reset = false)
        b.mapView.location.addOnIndicatorPositionChangedListener(positionListener)
        b.mapView.gestures.addOnMoveListener(moveListener)
    }

    private fun enableLocationPuck(reset: Boolean) {
        if (!::b.isInitialized) return
        if (reset) b.mapView.location.updateSettings { enabled = false }
        b.mapView.location.updateSettings {
            enabled = true
            pulsingEnabled = true
            locationPuck = createDefault2DPuck()
        }
    }

    private fun updateRoute() {
        if (!::b.isInitialized || !styleReady) return
        val source = b.mapView.mapboxMap.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return

        val features = ArrayList<Feature>()
        var current = ArrayList<Point>()
        var segment = Int.MIN_VALUE
        fun flush() {
            if (current.size >= 2) features.add(Feature.fromGeometry(LineString.fromLngLats(current)))
        }
        for (p in TrackRepository.points) {
            if (p.segment != segment) {
                flush()
                current = ArrayList()
                segment = p.segment
            }
            current.add(Point.fromLngLat(p.lon, p.lat))
        }
        flush()
        source.featureCollection(FeatureCollection.fromFeatures(features))
    }

    // ---------------------------------------------------------------- UI

    private fun applyInsets() {
        val margin = (12 * resources.displayMetrics.density).toInt()
        val bottomMargin = (16 * resources.displayMetrics.density).toInt()
        val fabMargin = (104 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.statsCard.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = margin + bars.top }
            b.bottomBar.updateLayoutParams<FrameLayout.LayoutParams> { this.bottomMargin = bottomMargin + bars.bottom }
            b.recenterButton.updateLayoutParams<FrameLayout.LayoutParams> { this.bottomMargin = fabMargin + bars.bottom }
            insets
        }
    }

    private fun setupButtons() {
        b.settingsButton.setOnClickListener { askToken(first = false) }

        b.recenterButton.setOnClickListener {
            following = true
            lastPuckPoint?.let {
                b.mapView.mapboxMap.setCamera(CameraOptions.Builder().center(it).zoom(15.5).build())
            }
        }

        b.mainButton.setOnClickListener {
            when (TrackRepository.state) {
                TrackRepository.State.IDLE -> ifReadyToRecord {
                    TrackRepository.start()
                    sendToService(TrackingService.ACTION_START)
                }
                TrackRepository.State.RECORDING -> {
                    TrackRepository.pause()
                    sendToService(TrackingService.ACTION_PAUSE)
                }
                TrackRepository.State.PAUSED -> ifReadyToRecord {
                    TrackRepository.resume()
                    sendToService(TrackingService.ACTION_RESUME)
                }
            }
        }

        b.stopButton.setOnClickListener { finishRecording() }
    }

    private fun refreshStats() {
        if (!::b.isInitialized) return
        val repo = TrackRepository
        b.distanceValue.text = String.format(Locale.US, "%.2f", repo.distanceM / 1000.0)
        b.timeValue.text = formatDuration(repo.elapsedMs())
        b.gainValue.text = repo.elevationGainM.toInt().toString()
        b.speedValue.text = String.format(Locale.US, "%.1f", repo.speedMs * 3.6f)

        when (repo.state) {
            TrackRepository.State.IDLE -> {
                b.statusText.text = "Pronto"
                b.mainButton.text = "Avvia"
                b.stopButton.visibility = android.view.View.GONE
            }
            TrackRepository.State.RECORDING -> {
                b.statusText.text = "● REC"
                b.mainButton.text = "Pausa"
                b.stopButton.visibility = android.view.View.VISIBLE
            }
            TrackRepository.State.PAUSED -> {
                b.statusText.text = "❚❚ In pausa"
                b.mainButton.text = "Riprendi"
                b.stopButton.visibility = android.view.View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- recording flow

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private fun ifReadyToRecord(action: () -> Unit) {
        if (!hasLocationPermission()) {
            requestPermissions()
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("GPS disattivato")
                .setMessage("Attiva la posizione del dispositivo per registrare l'escursione.")
                .setPositiveButton("Impostazioni") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Annulla", null)
                .show()
            return
        }
        action()
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun finishRecording() {
        if (TrackRepository.state == TrackRepository.State.RECORDING) {
            TrackRepository.pause()
            sendToService(TrackingService.ACTION_PAUSE)
        }

        if (TrackRepository.points.size < 2) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Traccia troppo corta")
                .setMessage("Non ci sono abbastanza punti per creare un GPX.")
                .setPositiveButton("Continua", null)
                .setNegativeButton("Scarta") { _, _ -> discardTrack() }
                .show()
            return
        }

        val input = EditText(this).apply {
            setSingleLine()
            setText("Escursione " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val summary = String.format(
            Locale.US, "%.2f km · %s · D+ %d m",
            TrackRepository.distanceM / 1000.0,
            formatDuration(TrackRepository.elapsedMs()),
            TrackRepository.elevationGainM.toInt(),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Termina escursione")
            .setMessage(summary)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Salva GPX") { _, _ -> saveGpx(input.text.toString(), summary) }
            .setNeutralButton("Riprendi") { _, _ ->
                ifReadyToRecord {
                    TrackRepository.resume()
                    sendToService(TrackingService.ACTION_RESUME)
                }
            }
            .setNegativeButton("Scarta") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Scartare la traccia?")
                    .setMessage("La registrazione verrà eliminata e non potrà essere recuperata.")
                    .setPositiveButton("Scarta") { _, _ -> discardTrack() }
                    .setNegativeButton("Indietro") { _, _ -> finishRecording() }
                    .show()
            }
            .show()
    }

    private fun discardTrack() {
        TrackRepository.clear()
        sendToService(TrackingService.ACTION_STOP)
    }

    private fun saveGpx(rawName: String, summary: String) {
        val name = rawName.trim().ifBlank { "Escursione " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()) }
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".gpx"
        val bytes = GpxWriter.build(name, TrackRepository.snapshot()).toByteArray(Charsets.UTF_8)

        var shareFile: File? = null
        try {
            val dir = File(cacheDir, "gpx").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            shareFile = File(dir, fileName).also { it.writeBytes(bytes) }
        } catch (_: Exception) {
        }
        val savedToDownloads = try {
            writeToDownloads(fileName, bytes)
        } catch (_: Exception) {
            false
        }

        if (shareFile == null && !savedToDownloads) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Salvataggio non riuscito")
                .setMessage("Non sono riuscito a scrivere il file. La traccia è ancora in memoria: riprova.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        TrackRepository.clear()
        sendToService(TrackingService.ACTION_STOP)

        val where = if (savedToDownloads) "Salvato in Download/LAVIA/$fileName" else "Non salvato in Download: usa Condividi per esportarlo."
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Escursione salvata")
            .setMessage("$summary\n\n$where")
            .setNegativeButton("Chiudi", null)
        if (shareFile != null) {
            dialog.setPositiveButton("Condividi") { _, _ -> shareGpx(shareFile) }
        }
        dialog.show()
    }

    private fun writeToDownloads(fileName: String, bytes: ByteArray): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LAVIA")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        return true
    }

    private fun shareGpx(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Condividi GPX"))
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    companion object {
        private const val PREFS = "lavia_tracker"
        private const val KEY_TOKEN = "mapbox_token"
        private const val ROUTE_SOURCE = "route-source"
        private const val ROUTE_LAYER = "route-layer"
    }
}
