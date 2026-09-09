package dev.nomadd3v.ocrkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the OCR server alive on a phone with no usable screen.
 *
 * Foreground service + a partial wake lock: this phone can sit with the
 * display off, and Android will otherwise doze the process and drop
 * sockets, which from the caller's side looks like the OCR service randomly
 * timing out.
 */
class OcrService : Service() {

    private var server: OcrServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val port = PortConfig.getPort(applicationContext)
        startForeground(NOTIF_ID, notification(port))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ocr-kit:server").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            server = OcrServer(applicationContext, port).also { it.start(0, true) }
            Log.i(OcrServer.TAG, "listening on 0.0.0.0:$port")
        } catch (t: Throwable) {
            Log.e(OcrServer.TAG, "failed to bind $port", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(port: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "OCR service", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("OCR Kit")
            .setContentText("Listening on :$port")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
    }

    companion object {
        private const val CHANNEL = "ocr-kit"
        private const val NOTIF_ID = 1
    }
}
