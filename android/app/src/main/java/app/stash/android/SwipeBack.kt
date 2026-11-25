// Path: android/app/src/main/java/app/stash/android/SwipeBack.kt
package app.stash.android

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps a screen's content so that a swipe from the left edge to the right drags the screen away with the finger and
 * closes the activity (the screen underneath shows through: the activity must use Theme.Stash.Slide). Past a third of
 * the width, or a quick flick, it leaves; otherwise it springs back. Needs no touch listener on the content itself.
 */
class SwipeBackLayout(private val act: Activity, private val content: View) : FrameLayout(act) {
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private val edge = act.dp(40)
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        if (content.background == null) content.setBackgroundColor(Ui.themeBackground(act)) // the window itself is see-through
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun end() {
        tracking = false
        dragging = false
        vt?.recycle()
        vt = null
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                end()
                tracking = e.x <= edge
                if (tracking) {
                    downX = e.x
                    downY = e.y
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (tracking && !dragging) {
                vt?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx)) tracking = false // it is a scroll
                else if (dx > slop) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> content.translationX = (e.x - downX).coerceIn(0f, width.toFloat())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = e.x - downX
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val leave = e.actionMasked == MotionEvent.ACTION_UP &&
                    (dx > width * 0.35f || (dx > context.dp(24) && vx > context.dp(400)))
                end()
                settle(leave)
            }
        }
        return true
    }

    private fun settle(leave: Boolean) {
        val w = width.toFloat().coerceAtLeast(1f)
        val left = (if (leave) w - content.translationX else content.translationX) / w
        val ms = (220 * left.coerceIn(0.35f, 1f)).toLong()
        content.animate().translationX(if (leave) w else 0f).setDuration(ms)
            .setInterpolator(DecelerateInterpolator(1.4f)).withLayer()
            .withEndAction {
                if (leave) {
                    act.finish()
                    // the screen is already off to the right: no second slide
                    if (Build.VERSION.SDK_INT >= 34) act.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    else @Suppress("DEPRECATION") act.overridePendingTransition(0, 0)
                }
            }.start()
    }
}
