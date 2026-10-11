package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.ImageView
import kotlin.math.hypot

/**
 * Floating bubble + Messenger-style bottom dismiss target, two TYPE_APPLICATION_OVERLAY windows.
 * All motion values come from docs/design/mockups/a2-halo.html (header comment, bubble CSS and JS).
 * Hidden windows keep their root INVISIBLE so pass-through touches are not blocked on Android 12+.
 */
@SuppressLint("InflateParams", "ClickableViewAccessibility")
class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onDismiss: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val bubble: View = LayoutInflater.from(ctx).inflate(R.layout.bubble, null)
    private val icon: ImageView = bubble.findViewById(R.id.bubbleIcon)
    private val zone: View = LayoutInflater.from(ctx).inflate(R.layout.dismiss_target, null)
    private val target: View = zone.findViewById(R.id.dismissTarget)
    private val glow: View = zone.findViewById(R.id.dismissGlow)

    private val bubbleLp = overlayParams(WRAP_CONTENT, WRAP_CONTENT, FLAG_NOT_FOCUSABLE)
        .apply { gravity = Gravity.TOP or Gravity.START }
    private val zoneLp = overlayParams(MATCH_PARENT, px(ZONE_DP), FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE)
        .apply { gravity = Gravity.BOTTOM }

    private var added = false
    private var hidden = false
    private var moveAnim: ValueAnimator? = null

    // drag state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startCx = 0f
    private var startCy = 0f
    private var moved = false
    private var magnet = false

    init {
        icon.setOnClickListener { if (!hidden) onTap() }   // tap and accessibility click share one path
        icon.setOnTouchListener { _, e -> onTouch(e); true }
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    /** Adds both windows and fades the bubble in at its home position. Idempotent. */
    fun show() {
        if (added) return
        val m = ctx.realMetrics()
        bubbleLp.x = m.widthPixels - px(BUBBLE_DP + EDGE_DP + PAD_DP)
        bubbleLp.y = px(HOME_Y_DP - PAD_DP)
        zone.visibility = View.INVISIBLE
        wm.addView(zone, zoneLp)
        wm.addView(bubble, bubbleLp)
        added = true
        icon.alpha = 0f
        icon.scaleX = HIDDEN_SCALE
        icon.scaleY = HIDDEN_SCALE
        fadeIn()
    }

    /** bubbleOut 180: untouchable at once, then alpha 0 / scale .7, then [onHidden]. */
    fun fadeOut(onHidden: () -> Unit) {
        hidden = true
        setTouchable(false)
        icon.animate().alpha(0f).scaleX(HIDDEN_SCALE).scaleY(HIDDEN_SCALE)
            .setDuration(BUBBLE_OUT).setInterpolator(EMPHASIZED)
            .withEndAction {
                bubble.visibility = View.INVISIBLE
                onHidden()
            }.start()
    }

    /** bubbleIn 300: visible and touchable, alpha 1 / scale 1; replays the Lumen Pill glyph entrance (avd_scan_in). */
    fun fadeIn() {
        hidden = false
        bubble.visibility = View.VISIBLE
        setTouchable(true)
        (icon.drawable as AnimatedVectorDrawable).run { reset(); start() }
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(BUBBLE_IN).setInterpolator(EMPHASIZED).start()
    }

    /** Removes both windows immediately. Cancelled animations do not run their end actions. Idempotent. */
    fun remove() {
        if (!added) return
        added = false
        moveAnim?.cancel()
        icon.animate().cancel()
        zone.animate().cancel()
        target.animate().cancel()
        glow.animate().cancel()
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(zone) }
    }

    private fun setTouchable(on: Boolean) {
        bubbleLp.flags = if (on) bubbleLp.flags and FLAG_NOT_TOUCHABLE.inv() else bubbleLp.flags or FLAG_NOT_TOUCHABLE
        if (added) wm.updateViewLayout(bubble, bubbleLp)
    }

    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveAnim?.cancel()
                downX = e.rawX
                downY = e.rawY
                startX = bubbleLp.x
                startY = bubbleLp.y
                val loc = IntArray(2).also(icon::getLocationOnScreen)
                startCx = loc[0] + icon.width / 2f
                startCy = loc[1] + icon.height / 2f
                moved = false
                magnet = false
                icon.animate().scaleX(HELD_SCALE).scaleY(HELD_SCALE)
                    .setDuration(HELD).setInterpolator(EMPHASIZED).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > DRAG_SLOP_DP * dp) {
                    moved = true
                    showZone()
                }
                if (!moved) return
                val (tx, ty) = targetCentre()
                val near = hypot(startCx + dx - tx, startCy + dy - ty) < MAGNET_DP * dp
                if (near) {
                    if (!magnet) {
                        magnet = true
                        setHot(true)
                        moveTo(startX + (tx - startCx).toInt(), startY + (ty - startCy).toInt(), MAGNET, EMPHASIZED)
                    }
                } else {
                    if (magnet) {
                        magnet = false
                        setHot(false)
                    }
                    moveAnim?.cancel()
                    place(startX + dx.toInt(), startY + dy.toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(HELD).setInterpolator(EMPHASIZED).start()
                when {
                    !moved -> if (e.actionMasked == MotionEvent.ACTION_UP) icon.performClick()
                    magnet -> dismiss()
                    else -> {
                        hideZone()
                        snapToEdge()
                    }
                }
            }
        }
    }

    /** Target centre on screen from the untransformed layout, so the target's own animation does not move it. */
    private fun targetCentre(): Pair<Float, Float> {
        val loc = IntArray(2).also(zone::getLocationOnScreen)
        return (loc[0] + target.left + target.width / 2f) to (loc[1] + target.top + target.height / 2f)
    }

    private fun showZone() {
        zone.animate().cancel()
        target.animate().cancel()
        glow.animate().cancel()
        zone.alpha = 0f
        zone.visibility = View.VISIBLE
        target.alpha = 1f
        target.translationY = DZ_RISE_DP * dp
        target.scaleX = DZ_START_SCALE
        target.scaleY = DZ_START_SCALE
        glow.alpha = 0f
        zone.animate().alpha(1f).setDuration(DZ_IN).setInterpolator(EASE_OUT).start()
        target.animate().translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(DZ_IN).setInterpolator(EMPHASIZED).start()
    }

    private fun hideZone() {
        zone.animate().alpha(0f).setDuration(DZ_IN).setInterpolator(EASE_OUT)
            .withEndAction { zone.visibility = View.INVISIBLE }.start()
    }

    private fun setHot(on: Boolean) {
        val s = if (on) HOT_SCALE else 1f
        target.animate().scaleX(s).scaleY(s).setDuration(MAGNET).setInterpolator(EMPHASIZED).start()
        glow.animate().alpha(if (on) 1f else 0f).setDuration(MAGNET).setInterpolator(EMPHASIZED).start()
    }

    /** Release on target: bubble alpha 0 / scale .3, target alpha 0 / scale .4 (dismiss 280), then [onDismiss]. */
    private fun dismiss() {
        hidden = true
        setTouchable(false)
        icon.animate().alpha(0f).scaleX(GONE_SCALE).scaleY(GONE_SCALE)
            .setDuration(DISMISS).setInterpolator(EMPHASIZED).start()
        glow.animate().alpha(0f).setDuration(DISMISS).start()
        target.animate().alpha(0f).scaleX(TARGET_GONE_SCALE).scaleY(TARGET_GONE_SCALE)
            .setDuration(DISMISS).setInterpolator(EMPHASIZED)
            .withEndAction { onDismiss() }.start()
    }

    /** Release elsewhere: snap 360 with overshoot to the nearer side edge, y clamped. */
    private fun snapToEdge() {
        val m = ctx.realMetrics()
        val frame = bubble.width
        val edge = px(EDGE_DP - PAD_DP)
        val x = if (bubbleLp.x + frame / 2 < m.widthPixels / 2) edge else m.widthPixels - frame - edge
        val y = bubbleLp.y.coerceIn(px(TOP_MIN_DP - PAD_DP), m.heightPixels - px(BOTTOM_GAP_DP + PAD_DP))
        moveTo(x, y, SNAP, OVERSHOOT)
    }

    private fun moveTo(x: Int, y: Int, ms: Long, curve: TimeInterpolator) {
        moveAnim?.cancel()
        val x0 = bubbleLp.x
        val y0 = bubbleLp.y
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                place(x0 + ((x - x0) * f).toInt(), y0 + ((y - y0) * f).toInt())
            }
            start()
        }
    }

    private fun place(x: Int, y: Int) {
        bubbleLp.x = x
        bubbleLp.y = y
        if (added) wm.updateViewLayout(bubble, bubbleLp)
    }

    private companion object {
        // a2-halo.html timings (ms)
        const val BUBBLE_OUT = 180L
        const val BUBBLE_IN = 300L
        const val DZ_IN = 220L
        const val MAGNET = 160L
        const val SNAP = 360L
        const val DISMISS = 280L
        const val HELD = 120L
        // a2-halo.html sizes (CSS px == dp) and scales
        const val MAGNET_DP = 104f
        const val DRAG_SLOP_DP = 6f
        const val DZ_RISE_DP = 28f
        const val BUBBLE_DP = 56
        const val PAD_DP = 12          // shadow margin in layout/bubble.xml
        const val EDGE_DP = 10
        const val HOME_Y_DP = 330
        const val TOP_MIN_DP = 60
        const val BOTTOM_GAP_DP = 200
        const val ZONE_DP = 260
        const val HELD_SCALE = .92f
        const val HIDDEN_SCALE = .7f
        const val GONE_SCALE = .3f
        const val TARGET_GONE_SCALE = .4f
        const val DZ_START_SCALE = .9f
        const val HOT_SCALE = 1.2f
        // a2-halo.html easing curves
        val EMPHASIZED = PathInterpolator(.2f, 0f, 0f, 1f)      // cubic-bezier(.2,0,0,1)
        val OVERSHOOT = PathInterpolator(.34f, 1.56f, .64f, 1f) // cubic-bezier(.34,1.56,.64,1)
        val EASE_OUT = PathInterpolator(0f, 0f, .58f, 1f)       // CSS ease-out
    }
}
