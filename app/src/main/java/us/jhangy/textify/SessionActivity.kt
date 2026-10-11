package us.jhangy.textify

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.animation.PathInterpolator
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import us.jhangy.textify.databinding.ActivitySessionBinding
import java.lang.ref.WeakReference

/**
 * Frozen-screen capture session. Shows the screenshot 1:1 under hidden system bars;
 * X or back fades the session out (closeOut) and tells CaptureService to bring the bubble back.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private var shot: Bitmap? = null
    private val origin = IntArray(2)          // window position on screen; screenshot px - origin = view px
    private var startOrientation = Configuration.ORIENTATION_UNDEFINED
    private var closing = false
    private var ended = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                    // decor does not fit system windows + cutout mode (library handles API levels)
        val bmp = pending
        pending = null
        if (bmp == null) { finish(); return } // nothing to show; onDestroy still ends the session
        shot = bmp
        active = WeakReference(this)
        startOrientation = resources.configuration.orientation

        b = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        b.shot.setImageBitmap(bmp)
        b.root.doOnLayout { root ->
            root.getLocationOnScreen(origin)
            b.shot.imageMatrix = Matrix().apply { setTranslate(-origin[0].toFloat(), -origin[1].toFloat()) }
        }

        b.close.setOnClickListener { close() }
        onBackPressedDispatcher.addCallback(this) { close() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != startOrientation) finish()   // screenshot no longer maps 1:1
    }

    private fun close() {
        if (closing) return
        closing = true
        b.close.animate().scaleX(0.6f).scaleY(0.6f).rotation(90f)
            .setDuration(CLOSE_OUT).setInterpolator(X_OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(CLOSE_OUT).setInterpolator(CLOSE_CURVE)
            .withEndAction { endSession(); finish() }
    }

    /** Exactly one session-ended callback to the service per activity instance. */
    private fun endSession() {
        if (ended) return
        ended = true
        CaptureService.onSessionEnded()
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        shot?.let { bmp ->
            b.shot.setImageDrawable(null)
            bmp.recycle()
        }
        shot = null
        endSession()
        super.onDestroy()
    }

    companion object {
        private const val CLOSE_OUT = 300L
        private val X_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)     // a2 .xbtn transform curve
        private val CLOSE_CURVE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)  // a2 close() opacity curve

        private var pending: Bitmap? = null
        private var active: WeakReference<SessionActivity>? = null

        /** Main thread only. Takes ownership of [shot]; the session recycles it. */
        fun start(ctx: Context, shot: Bitmap) {
            pending?.recycle()
            pending = shot
            ctx.startActivity(
                Intent(ctx, SessionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }

        /** Projection stopped: drop any unconsumed shot and close the open session without animation. */
        fun finishActive() {
            pending?.recycle()
            pending = null
            active?.get()?.finish()
        }
    }
}
