package com.arisu.companion.overlay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.ui.platform.ComposeView
import com.arisu.companion.MainActivity
import com.arisu.companion.R
import com.arisu.companion.animation.PetState
import com.arisu.companion.chat.FloatingChatPanel
import com.arisu.companion.renderer.ArisuRenderer
import com.arisu.companion.ui.ArisuTheme
import kotlinx.coroutines.launch

/**
 * Keeps the floating 3D companion alive. Hosts two overlay windows:
 *  1. the square pet window (transparent GLB rendering + gestures), and
 *  2. the floating chat panel window (Compose).
 *
 * Touch safety: the pet window is FLAG_NOT_TOUCH_MODAL and its touch handler
 * only consumes touches that land near the character's circular body, so apps
 * underneath stay interactive. No AccessibilityService tricks anywhere.
 */
class FloatingPetService : Service() {

    private lateinit var container: com.arisu.companion.AppContainer
    private lateinit var windowManager: WindowManager

    private var petWindowParams: WindowManager.LayoutParams? = null
    private var petRoot: FrameLayout? = null
    private var petView: TextureView? = null
    private var renderer: ArisuRenderer? = null

    private var chatOwner: OverlayUiOwner? = null
    private var chatRoot: FrameLayout? = null
    private var chatOpen = false
    private var chatExpanded = false
    private var petHiddenByUser = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> renderer?.setPausedByPolicy(true)
                Intent.ACTION_SCREEN_ON -> renderer?.setPausedByPolicy(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? com.arisu.companion.ArisuApplication
        container = app?.container ?: com.arisu.companion.AppContainer(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; service idles until granted")
        }
        startInForeground()
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        addPetWindow()
        observeChatEngine()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hidePet()
            ACTION_SHOW -> showPet()
            ACTION_STOP -> stopSelfGracefully()
            ACTION_TOGGLE_CHAT -> if (chatOpen) closeChat() else openChat()
            ACTION_OPEN_SETTINGS -> {
                val i = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }
        return START_STICKY
    }

    private fun observeChatEngine() {
        container.applicationScope.launch {
            container.chatEngine.petStateFlow.collect { state ->
                if (!petHiddenByUser) renderer?.setPetState(state)
            }
        }
        container.applicationScope.launch {
            container.appState.petScale.collect { renderer?.setUserScale(it) }
        }
        container.applicationScope.launch {
            container.appState.fpsLimit.collect { renderer?.setFpsLimit(it) }
        }
    }

    private fun observeSettings() {
        // Placeholder for reactive pet traits; position/scale collectors live in observeChatEngine.
    }

    // ------------------------------------------------------------ pet window

    private fun addPetWindow() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (petRoot != null) return

        val sizePx = petSizePx()
        val metrics = resources.displayMetrics
        val (defaultX, defaultY) = OverlayGeometry.defaultPosition(sizePx, metrics.widthPixels, metrics.heightPixels)
        val saved = container.appState

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (saved.petX.value >= 0) saved.petX.value.toInt() else defaultX
            y = if (saved.petY.value >= 0) saved.petY.value.toInt() else defaultY
        }
        petWindowParams = params

        val root = FrameLayout(this)
        val texture = TextureView(this)
        root.addView(
            texture,
            FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            ),
        )
        petView = texture
        petRoot = root

        val gesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    return if (container.appState.tapOpensChat.value) {
                        openChat(); true
                    } else {
                        renderer?.setPetState(PetState.GREETING); true
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    renderer?.setPetState(PetState.HAPPY)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    showPetMenu(root, e.rawX.toInt(), e.rawY.toInt())
                }
            },
        )

        texture.setOnTouchListener { v, event ->
            handlePetTouch(event, gesture, v.width)
            true
        }

        windowManager.addView(root, params)

        // Restrict the overlay's touchable area to the character itself: the
        // WindowManager routes touches outside this region to the app below,
        // which a `return false` from the listener above cannot achieve.
        if (!OverlayTouchRegion.apply(root)) {
            Log.w(TAG, "pet window handles its full square bounds")
        }

        createRenderer()
    }

    private fun handlePetTouch(event: MotionEvent, gesture: GestureDetector, sizePx: Int) {
        try {
            gesture.onTouchEvent(event)
        } catch (_: Exception) {
        }
        // Renderer is not used for orbit; drag is handled here.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = petWindowParams?.x ?: 0
                dragStartY = petWindowParams?.y ?: 0
                dragStartTouchX = event.rawX
                dragStartTouchY = event.rawY
                dragStarted = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartTouchX
                val dy = event.rawY - dragStartTouchY
                if (!dragStarted && OverlayGeometry.isDrag(dx.toInt(), dy.toInt(), viewSlop())) {
                    dragStarted = true
                    renderer?.setPetState(PetState.GREETING)
                }
                if (dragStarted && petWindowParams != null) {
                    val metrics = resources.displayMetrics
                    val (x, y) = OverlayGeometry.clamp(
                        dragStartX + dx.toInt(),
                        dragStartY + dy.toInt(),
                        sizePx,
                        metrics.widthPixels,
                        metrics.heightPixels,
                    )
                    petWindowParams?.let {
                        it.x = x
                        it.y = y
                        windowManager.updateViewLayout(petRoot, it)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragStarted && petWindowParams != null) {
                    val metrics = resources.displayMetrics
                    petWindowParams?.let { p ->
                        if (container.appState.snapToEdge.value) {
                            p.x = OverlayGeometry.snapToEdge(p.x, sizePx, metrics.widthPixels, SNAP_THRESHOLD_PX)
                            windowManager.updateViewLayout(petRoot, p)
                        }
                        container.appState.savePetPosition(p.x.toFloat(), p.y.toFloat())
                    }
                }
                dragStarted = false
            }
        }
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartTouchX = 0f
    private var dragStartTouchY = 0f
    private var dragStarted = false

    private fun viewSlop(): Int =
        android.view.ViewConfiguration.get(this).scaledTouchSlop

    private fun createRenderer() {
        val texture = petView ?: return
        if (renderer != null) return
        val glb = loadGlbBytes() ?: run {
            Log.e(TAG, "GLB asset missing/unreadable")
            return
        }
        val r = ArisuRenderer(
            textureView = texture,
            onAssetReady = { clips ->
                Log.i(TAG, "GLB clips found: ${clips.joinToString()}")
            },
            onFatal = { msg -> Log.e(TAG, "renderer fatal: ${'$'}msg") },
        )
        renderer = r
        r.setFpsLimit(container.appState.fpsLimit.value)
        r.setUserScale(container.appState.petScale.value)
        r.setPetState(PetState.GREETING)
        runCatching { r.loadModel(glb) }
        r.setPetState(PetState.IDLE)
    }

    private fun destroyRenderer() {
        renderer?.destroy()
        renderer = null
        petView?.let { it.surfaceTextureListener = null }
    }

    private fun petSizePx(): Int =
        (resources.displayMetrics.density * BASE_PET_DP * container.appState.petScale.value).toInt()
            .coerceIn(140.dpToPx(), 420.dpToPx())

    private fun resizePet(newScale: Float) {
        container.appState.savePetScale(newScale)
        val params = petWindowParams ?: return
        val sizePx = petSizePx()
        params.width = sizePx
        params.height = sizePx
        val metrics = resources.displayMetrics
        val (x, y) = OverlayGeometry.clamp(params.x, params.y, sizePx, metrics.widthPixels, metrics.heightPixels)
        params.x = x
        params.y = y
        windowManager.updateViewLayout(petRoot, params)
        container.appState.savePetPosition(x.toFloat(), y.toFloat())
    }

    // ------------------------------------------------------------- chat window

    fun openChat() {
        if (chatOpen || !android.provider.Settings.canDrawOverlays(this)) return
        chatOpen = true
        val metrics = resources.displayMetrics
        val (x, y, w, h) = OverlayGeometry.chatPanelBounds(
            metrics.widthPixels, metrics.heightPixels, metrics.density, chatExpanded,
        )
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        val owner = OverlayUiOwner()
        chatOwner = owner
        val root = FrameLayout(this).apply {
            setOnTouchListener { _, _ -> false } // pass touches that miss the panel
        }
        val compose = ComposeView(this).apply {
            setContent {
                ArisuTheme {
                    FloatingChatPanel(
                        engine = container.chatEngine,
                        voice = container.voiceController,
                        expanded = chatExpanded,
                        onToggleExpand = {
                            chatExpanded = !chatExpanded
                            applyChatExpanded()
                        },
                        onClose = { closeChat() },
                        onComposerFocus = { setChatWindowFocusable(true) },
                        onComposerUnfocus = { setChatWindowFocusable(false) },
                    )
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            ),
        )
        owner.install(root)

        chatRoot = root
        windowManager.addView(root, params)
        renderer?.setPetState(PetState.GREETING)
    }

    private fun applyChatExpanded() {
        val root = chatRoot ?: return
        val metrics = resources.displayMetrics
        val (x, y, w, h) = OverlayGeometry.chatPanelBounds(
            metrics.widthPixels, metrics.heightPixels, metrics.density, chatExpanded,
        )
        (root.layoutParams as? WindowManager.LayoutParams)?.apply {
            this.x = x
            this.y = y
            width = w
            height = h
            windowManager.updateViewLayout(root, this)
        }
    }

    private fun setChatWindowFocusable(focusable: Boolean) {
        val root = chatRoot ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val wasFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        val wantFocusable = focusable
        if (wasFocusable == wantFocusable) return
        if (wantFocusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(root, params)
    }

    fun closeChat() {
        val root = chatRoot ?: return
        chatOpen = false
        chatOwner?.uninstall(root)
        windowManager.removeView(root)
        chatRoot = null
        chatOwner = null
        renderer?.setPetState(PetState.IDLE)
    }

    // -------------------------------------------------------------- hide/show

    private fun hidePet() {
        petHiddenByUser = true
        closeChat()
        destroyRenderer()
        petRoot?.let { windowManager.removeView(it) }
        petRoot = null
        petWindowParams = null
        updateNotification()
    }

    private fun showPet() {
        petHiddenByUser = false
        addPetWindow()
        updateNotification()
    }

    private fun stopSelfGracefully() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ menu

    private fun showPetMenu(anchor: View, rawX: Int, rawY: Int) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.view_pet_menu, null, false)
        val popup = PopupWindow(
            popupView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            false,
        ).apply { isOutsideTouchable = true }

        popupView.findViewById<View>(R.id.menu_bigger).setOnClickListener {
            resizePet((container.appState.petScale.value + 0.2f).coerceAtMost(2.2f))
        }
        popupView.findViewById<View>(R.id.menu_smaller).setOnClickListener {
            resizePet((container.appState.petScale.value - 0.2f).coerceAtLeast(0.4f))
        }
        popupView.findViewById<View>(R.id.menu_reset).setOnClickListener {
            container.appState.resetPetPosition()
            val metrics = resources.displayMetrics
            val size = petSizePx()
            val (x, y) = OverlayGeometry.defaultPosition(size, metrics.widthPixels, metrics.heightPixels)
            petWindowParams?.let {
                it.x = x; it.y = y
                petRoot?.let { r -> windowManager.updateViewLayout(r, it) }
                container.appState.savePetPosition(x.toFloat(), y.toFloat())
            }
        }
        popupView.findViewById<View>(R.id.menu_open_settings).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            popup.dismiss()
        }
        popupView.findViewById<View>(R.id.menu_hide).setOnClickListener {
            popup.dismiss()
            hidePet()
        }
        popupView.findViewById<View>(R.id.menu_close_chat).setOnClickListener {
            popup.dismiss()
            if (chatOpen) closeChat() else openChat()
        }
        popup.showAsDropDown(anchor, -60, 0)
    }

    // ---------------------------------------------------------- notifications

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(id: Int, label: String, intent: Intent): Notification.Action {
            val pi = PendingIntent.getService(
                this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null, label, pi).build()
        }
        val notificationsApi = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = Notification.Builder(this, FloatingPetService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(action(11, getString(R.string.notif_action_hide), newIntent(ACTION_HIDE)))
            .addAction(
                action(
                    12,
                    if (petHiddenByUser) getString(R.string.notif_action_show) else getString(R.string.notif_action_stop),
                    newIntent(if (petHiddenByUser) ACTION_SHOW else ACTION_STOP),
                ),
            )
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun newIntent(action: String): Intent = Intent(this, FloatingPetService::class.java).apply {
        this.action = action
    }

    // ------------------------------------------------------------------ misc

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = petRoot
        val params = petWindowParams ?: return
        if (root != null) {
            val metrics = resources.displayMetrics
            val sizePx = petSizePx()
            params.width = sizePx
            params.height = sizePx
            val (x, y) = OverlayGeometry.clamp(params.x, params.y, sizePx, metrics.widthPixels, metrics.heightPixels)
            params.x = x
            params.y = y
            windowManager.updateViewLayout(root, params)
            container.appState.savePetPosition(x.toFloat(), y.toFloat())
        }
    }

    override fun onDestroy() {
        runCatching {
            unregisterReceiver(screenStateReceiver)
        }
        closeChat()
        destroyRenderer()
        petRoot?.let {
            windowManager.removeView(it)
        }
        petRoot = null
        petWindowParams = null
        super.onDestroy()
    }

    private fun loadGlbBytes(): ByteArray? = try {
        assets.open(GLB_ASSET).use { it.readBytes() }
    } catch (e: Exception) {
        Log.e(TAG, "loadGlbBytes failed", e)
        null
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatPetService"
        private const val BASE_PET_DP = 300
        private const val SNAP_THRESHOLD_PX = 56
        private const val NOTIFICATION_ID = 2001
        const val NOTIFICATION_CHANNEL_ID = "floating_pet"
        const val ACTION_HIDE = "com.arisu.companion.action.HIDE_PET"
        const val ACTION_SHOW = "com.arisu.companion.action.SHOW_PET"
        const val ACTION_STOP = "com.arisu.companion.action.STOP_PET"
        const val ACTION_TOGGLE_CHAT = "com.arisu.companion.action.TOGGLE_CHAT"
        const val ACTION_OPEN_SETTINGS = "com.arisu.companion.action.OPEN_SETTINGS"
        const val GLB_ASSET = "Arisu (Maid).glb"

        fun intent(context: Context, action: String): Intent =
            Intent(context, FloatingPetService::class.java).apply { this.action = action }
    }
}
