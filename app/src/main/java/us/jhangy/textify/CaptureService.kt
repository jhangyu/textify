package us.jhangy.textify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the MediaProjection, one long-lived capture pipeline
 * ([ScreenGrabber]) and the floating [Bubble]. A bubble tap fades the bubble out, grabs the
 * next frame and opens [SessionActivity]; the bubble fades back in on [onSessionEnded].
 * Dragging the bubble onto the bottom target stops the service.
 * While [SettingsActivity] is visible the bubble stays hidden ([setSettingsVisible]).
 */
class CaptureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private var bubble: Bubble? = null
    private var busy = false   // true from bubble tap until the session ends (or the grab fails)
    private var settingsVisible = false
    private val showBubble = Runnable { if (!settingsVisible && !busy) bubble?.fadeIn() }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // On Android 14+ the FGS (type mediaProjection) must be running before
        // getMediaProjection() is called.
        startForeground(NOTIF_ID, buildNotification())
        if (projection != null) return START_NOT_STICKY   // already running: keep the current session

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code == 0 || data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) { stopSelf(); return START_NOT_STICKY }
        // Must precede the VirtualDisplay creation inside ScreenGrabber, else IllegalStateException.
        proj.registerCallback(projectionCallback, main)
        projection = proj
        grabber = runCatching { ScreenGrabber(this, proj, main) }.getOrElse {
            stopSelf(); return START_NOT_STICKY
        }
        bubble = Bubble(this, onTap = { capture() }, onDismiss = { stopSelf() }).also { it.show() }
        // A projection cannot be restored after a process restart, so never ask to be restarted.
        return START_NOT_STICKY
    }

    private fun capture() {
        if (busy || settingsVisible) return
        val g = grabber ?: return
        val b = bubble ?: return
        busy = true
        b.fadeOut {
            g.grab { shot ->
                if (shot == null) {
                    // spec: no frame -> bubble fades back, no toast; user retries
                    busy = false
                    b.fadeIn()
                } else {
                    SessionActivity.start(this, shot)
                }
            }
        }
    }

    private fun sessionEnded() {
        busy = false
        if (!settingsVisible) bubble?.fadeIn()
    }

    private fun applySettingsVisible(visible: Boolean) {
        settingsVisible = visible
        main.removeCallbacks(showBubble)
        if (visible) {
            // busy: the bubble is already hidden; a second fadeOut would cancel capture()'s pending grab
            if (!busy) bubble?.fadeOut {}
            return
        }
        if (busy) return                       // the session end fades the bubble in
        if (Settings.canDrawOverlays(this)) {
            main.postDelayed(showBubble, BUBBLE_DELAY)   // bubbleDelay 120, then bubbleIn 300 (Bubble.fadeIn)
        } else {
            Toast.makeText(this, R.string.toast_bubble_hidden, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Textify", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_textify)
            .setColor(0xFF7C3AED.toInt())
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        instance = null   // first: the finishing session's onSessionEnded() becomes a no-op
        main.removeCallbacks(showBubble)
        SessionActivity.finishActive()
        bubble?.remove()
        bubble = null
        grabber?.release()
        grabber = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        HyMt.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "textify"
        private const val NOTIF_ID = 1
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "us.jhangy.textify.STOP"
        private const val BUBBLE_DELAY = 120L

        // Set in onCreate, cleared in onDestroy: never outlives the service.
        @SuppressLint("StaticFieldLeak")
        private var instance: CaptureService? = null

        /** True while the service instance exists. */
        val isRunning: Boolean get() = instance != null

        /** SettingsActivity / SessionActivity (main thread): hide the bubble while settings is visible. No-op if not running. */
        fun setSettingsVisible(visible: Boolean) {
            instance?.applySettingsVisible(visible)
        }

        /** Called by SessionActivity (main thread) when its session finishes. No-op if the service is not running. */
        fun onSessionEnded() {
            instance?.sessionEnded()
        }

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            )
        }
    }
}
