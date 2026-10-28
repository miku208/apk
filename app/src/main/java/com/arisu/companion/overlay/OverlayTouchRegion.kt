package com.arisu.companion.overlay

import android.graphics.Rect
import android.util.Log
import android.view.View
import java.lang.reflect.Proxy

/**
 * Restricts the pet overlay's touchable area to the character's bounding box.
 *
 * A `TYPE_APPLICATION_OVERLAY` window receives every touch that lands inside
 * its surface, so a 300dp square swallows taps meant for the app underneath.
 * Returning `false` from a touch listener does **not** help: the WindowManager
 * already picked this window as the target, and the event is simply dropped.
 * The only way to have the system route those touches to the app below is to
 * publish a touchable region for the window.
 *
 * Android exposes that through `ViewTreeObserver.OnComputeInternalInsetsListener`
 * and `InternalInsetsInfo.TOUCHABLE_INSETS_REGION`, which are framework-internal
 * (`@hide`) and therefore not in the public SDK stubs. We reach them
 * reflectively, tolerate every failure, and fall back to the plain square
 * window — the pet keeps working, it just handles its full bounds.
 */
object OverlayTouchRegion {

    private const val TAG = "OverlayTouchRegion"

    /** `ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION`. */
    private const val TOUCHABLE_INSETS_REGION = 3

    /**
     * Installs a compute-internal-insets listener that reports the character's
     * region. Returns true when the platform accepted it.
     */
    fun apply(view: View): Boolean = runCatching {
        val observer = view.viewTreeObserver
        val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val setTouchableInsets = infoClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
        val regionField = infoClass.getField("touchableRegion")

        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            if (method?.name == "onComputeInternalInsets" && args?.size == 1) {
                val info = args[0]
                val rect = regionField.get(info) as Rect
                val region = OverlayGeometry.touchableRegion(view.width, view.height)
                rect.set(region[0], region[1], region[2], region[3])
                setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION)
            }
            null
        }

        observer.javaClass
            .getMethod("addOnComputeInternalInsetsListener", listenerClass)
            .invoke(observer, listener)
        true
    }.getOrElse { error ->
        Log.w(TAG, "touchable-region refinement unavailable: ${error.message}")
        false
    }
}
