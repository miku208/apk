package com.arisu.companion.overlay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry helpers for the floating pet window (unit tested):
 * clamping, default placement, snap-to-edge. Screen coordinates are in pixels.
 */
object OverlayGeometry {

    data class Bounds(val x: Int, val y: Int, val sizePx: Int, val screenW: Int, val screenH: Int) {
        fun maxX() = (screenW - sizePx).coerceAtLeast(0)
        fun maxY() = (screenH - sizePx).coerceAtLeast(0)
    }

    /** Clamps a proposed window origin so the pet stays fully on screen. */
    fun clamp(x: Int, y: Int, sizePx: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val maxX = (screenW - sizePx).coerceAtLeast(0)
        val maxY = (screenH - sizePx).coerceAtLeast(0)
        return min(x, maxX).coerceAtLeast(0) to min(y, maxY).coerceAtLeast(0)
    }

    /**
     * Default placement when nothing was persisted: right-center-ish,
     * two thirds down the screen so the pet doesn't cover app headers.
     */
    fun defaultPosition(sizePx: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        (screenW - sizePx - (screenW * 0.06f).toInt().coerceAtLeast(12)) to
            ((screenH - sizePx) * 0.55f).toInt().coerceAtLeast(0)

    /**
     * After a drag ends, optionally attract the pet to the nearest horizontal
     * screen edge when its center is close to the edge.
     */
    fun snapToEdge(x: Int, sizePx: Int, screenW: Int, thresholdPx: Int): Int {
        val maxX = (screenW - sizePx).coerceAtLeast(0)
        val clamped = x.coerceIn(0, maxX)
        return when {
            clamped <= thresholdPx -> 0
            screenW - (clamped + sizePx) <= thresholdPx -> maxX
            else -> clamped
        }
    }

    /** Simple pinch handler math: new size for a two-finger gesture. */
    fun pinchScale(baseSizePx: Int, gestureSpan: Int, baseSpan: Int): Int =
        max(120, min(920, (baseSizePx * (gestureSpan.toFloat() / baseSpan.toFloat())).toInt()))

    /** Distance vs drag-slop: was this tap a drag? */
    fun isDrag(dx: Int, dy: Int, slop: Int): Boolean = abs(dx) > slop || abs(dy) > slop

    /**
     * Touchable region of the otherwise square pet window, in view pixels,
     * returned as (left, top, right, bottom).
     *
     * A TYPE_APPLICATION_OVERLAY window receives touches across its whole
     * surface, so a 300dp square would swallow taps meant for the app the user
     * is actually using. Handing this rect to
     * [android.view.ViewTreeObserver.InternalInsetsInfo.setTouchableInsets]
     * makes the WindowManager itself route anything outside it to the window
     * below, which a `return false` from the touch listener cannot do.
     *
     * The rect follows the character: the GLB is centered and normalized, so
     * the body occupies the middle of the square, full height, and roughly the
     * middle 66% horizontally (the model also holds a prop off to one side).
     */
    fun touchableRegion(w: Int, h: Int): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(0, 0, 0, 0)
        val left = (w * 0.16f).toInt()
        val right = (w * 0.84f).toInt()
        val top = (h * 0.02f).toInt()
        val bottom = (h * 0.98f).toInt()
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * Chat window geometry: a rounded panel occupying the lower-left part of
     * the screen but anchored high enough that a soft keyboard usually does
     * not cover its composer. Returns (x, y, width, height).
     */
    fun chatPanelBounds(screenW: Int, screenH: Int, density: Float, expanded: Boolean): IntArray {
        val width = min((screenW * 0.92f).toInt(), (420f * density).toInt())
        val heightFrac = if (expanded) 0.62f else 0.42f
        val height = (screenH * heightFrac).toInt()
        val x = (screenW - width) / 2
        val y = (screenH * 0.34f).toInt().coerceAtMost(screenH - height)
        return intArrayOf(x, y, width, height)
    }
}
