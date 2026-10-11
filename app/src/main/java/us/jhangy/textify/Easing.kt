package us.jhangy.textify

import android.view.animation.PathInterpolator

/** Shared CSS / mockup easing curves (PathInterpolator is stateless, so one instance serves every animator). */
object Easing {
    val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)          // cubic-bezier(.2,0,0,1)
    val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)           // CSS ease-out
    val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)            // CSS ease-in
    val EASE_IN_OUT = PathInterpolator(0.42f, 0f, 0.58f, 1f)     // CSS ease-in-out
    val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)      // CSS ease
    val DECELERATE = PathInterpolator(0f, 0f, 0.2f, 1f)          // sheet/settings in
    val ACCELERATE = PathInterpolator(0.4f, 0f, 1f, 1f)          // sheet out
    val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)  // tide, shine, arrive
    val CLOSE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)          // a2 close / settings out
    val OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)         // X button, chip in
}
