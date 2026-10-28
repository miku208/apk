package com.arisu.companion.overlay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose normally works inside Activities. In an overlay window we provide a
 * minimal [LifecycleOwner]/[ViewModelStoreOwner]/[SavedStateRegistryOwner] and
 * attach it to the view tree through the ViewTree* helpers, which is exactly
 * what ComposeView needs to start a recomposer.
 */
class OverlayUiOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Must run BEFORE the ComposeView is attached to a window. */
    fun install(view: View) {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        dispatch(Lifecycle.Event.ON_START)
        dispatch(Lifecycle.Event.ON_RESUME)
    }

    private fun dispatch(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)

    fun uninstall(view: View) {
        dispatch(Lifecycle.Event.ON_PAUSE)
        dispatch(Lifecycle.Event.ON_STOP)
        dispatch(Lifecycle.Event.ON_DESTROY)
        view.setViewTreeLifecycleOwner(null)
        view.setViewTreeViewModelStoreOwner(null)
        view.setViewTreeSavedStateRegistryOwner(null)
        store.clear()
    }
}
