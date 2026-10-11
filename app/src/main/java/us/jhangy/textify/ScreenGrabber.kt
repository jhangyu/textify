package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.DisplayMetrics
import android.view.WindowManager

/** Full display size including system bars. getRealMetrics is deprecated in 31 but present through 36: one path for 26-36. */
internal fun Context.realMetrics(): DisplayMetrics = DisplayMetrics().also {
    @Suppress("DEPRECATION")
    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
}

/**
 * One long-lived VirtualDisplay + ImageReader per MediaProjection.
 * Android 14+ throws SecurityException on a second virtual-display creation for the same projection
 * (developer.android.com/about/versions/14/behavior-changes-14), so the display is created exactly
 * once here and only resized + given a new Surface on rotation, as that page prescribes.
 * The caller must have called projection.registerCallback() before constructing this.
 */
class ScreenGrabber(private val ctx: Context, projection: MediaProjection, private val handler: Handler) {

    private var reader: ImageReader
    private val display: VirtualDisplay
    private var latest: Image? = null
    private var pending: ((Bitmap?) -> Unit)? = null
    private val timeout = Runnable { deliver() }

    init {
        val m = ctx.realMetrics()
        reader = newReader(m)
        display = projection.createVirtualDisplay(
            "textify-cap", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        ) ?: throw IllegalStateException("createVirtualDisplay returned null")
    }

    private fun newReader(m: DisplayMetrics): ImageReader =
        ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r -> onImage(r) }, handler)
        }

    // ponytail: wakes on every screen change for the service lifetime (acquire + close, no copy);
    // detach the listener between grabs if battery ever matters.
    private fun onImage(r: ImageReader) {
        if (r !== reader) return               // stale reader after a resize
        latest?.close()                        // close first: at most 1 of maxImages=2 acquired at any time
        latest = null
        latest = r.acquireLatestImage()
        if (pending != null && latest != null) deliver()
    }

    /**
     * Calls [onFrame] once on the handler thread with the first frame produced after this call,
     * or after GRAB_TIMEOUT_MS the newest held frame, or null if no frame was ever received.
     * The caller owns the returned Bitmap.
     */
    fun grab(onFrame: (Bitmap?) -> Unit) {
        val m = ctx.realMetrics()
        if (m.widthPixels != reader.width || m.heightPixels != reader.height) {
            val old = reader
            latest?.close()
            latest = null
            reader = newReader(m)
            display.resize(m.widthPixels, m.heightPixels, m.densityDpi)
            display.setSurface(reader.surface)
            old.close()
        }
        pending = onFrame
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, GRAB_TIMEOUT_MS)
    }

    private fun deliver() {
        val cb = pending ?: return
        pending = null
        handler.removeCallbacks(timeout)
        cb(latest?.let { runCatching { toBitmap(it) }.getOrNull() })
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowPadding = plane.rowStride - plane.pixelStride * w
        val buffer = plane.buffer
        buffer.rewind()                        // the same held image may be converted by two grabs
        val padded = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
    }

    /** Drops a pending grab without calling it and frees the capture pipeline. Call once. */
    fun release() {
        handler.removeCallbacks(timeout)
        pending = null
        latest?.close()
        latest = null
        display.release()
        reader.close()
    }

    private companion object {
        /** Wait cap for a fresh frame after the bubble fade-out (not a fade). Calibration knob. */
        const val GRAB_TIMEOUT_MS = 300L
    }
}
