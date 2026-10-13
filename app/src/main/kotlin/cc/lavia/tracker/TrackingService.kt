package cc.lavia.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/** Servizio in primo piano: tiene vivo il GPS anche a schermo spento. */
class TrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private var updating = false
    private var lastNotificationMs = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            TrackRepository.addPoint(location)
            val now = System.currentTimeMillis()
            if (now - lastNotificationMs > 5_000) {
                lastNotificationMs = now
                refreshNotification()
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        TrackRepository.init(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android richiede di chiamare startForeground subito dopo startForegroundService.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> startUpdates()
            ACTION_PAUSE -> stopUpdates()
            ACTION_STOP -> {
                stopUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> stopUpdates()
        }
        refreshNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startUpdates() {
        if (updating) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            updating = true
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: IllegalArgumentException) {
            // GPS non disponibile sul dispositivo
            stopSelf()
        }
    }

    private fun stopUpdates() {
        if (!updating) return
        locationManager.removeUpdates(listener)
        updating = false
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val status = when (TrackRepository.state) {
            TrackRepository.State.RECORDING -> "In registrazione"
            TrackRepository.State.PAUSED -> "In pausa"
            TrackRepository.State.IDLE -> "Pronto"
        }
        val km = String.format(Locale.US, "%.2f km", TrackRepository.distanceM / 1000.0)
        val s = TrackRepository.elapsedMs() / 1000
        val time = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("LAVIA Tracker · $status")
            .setContentText("$km · $time")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "cc.lavia.tracker.START"
        const val ACTION_PAUSE = "cc.lavia.tracker.PAUSE"
        const val ACTION_RESUME = "cc.lavia.tracker.RESUME"
        const val ACTION_STOP = "cc.lavia.tracker.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
    }
}
