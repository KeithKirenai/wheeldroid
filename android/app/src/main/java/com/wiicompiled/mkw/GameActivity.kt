package com.wiicompiled.mkw

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.libsdl.app.SDL
import org.libsdl.app.SDLControllerManager

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback, SensorEventListener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var btnGas: Button
    private lateinit var btnDrift: Button
    private lateinit var btnItem: Button
    private lateinit var btnPause: Button
    private lateinit var steeringArea: View

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Dolphin-style in-game settings menu (opened with the Back button)
    private lateinit var ingameMenu: View
    private lateinit var menuPause: Button
    private lateinit var menuTouchControls: Button
    private lateinit var menuTiltControls: Button
    private lateinit var menuNotch: Button
    private lateinit var menuShowFps: Button
    private lateinit var menuExit: Button
    private lateinit var menuEditLayout: Button
    private lateinit var menuResetLayout: Button
    private lateinit var touchOverlayContainer: FrameLayout
    private lateinit var actionBtnContainer: View
    private var touchControlsEnabled = true
    private var tiltControlsEnabled = true
    private var extendToNotchEnabled = true
    private var showFpsEnabled = false
    private var menuOpen = false
    private var gamePaused = false

    /** Launches TouchControlsEditorActivity and re-applies layout when it returns. */
    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // User saved a new layout — apply it immediately to the live overlay
            touchOverlayContainer.post { applyCustomLayout() }
        }
    }

    companion object {
        const val BTN_A = 0
        const val BTN_B = 1
        const val BTN_L = 2
        const val BTN_START = 3
        const val BTN_X = 4
        const val BTN_Y = 5
        const val BTN_R = 6
        const val BTN_ZL = 7
        const val BTN_ZR = 8
        const val BTN_SELECT = 9
        const val BTN_DPAD_UP = 10
        const val BTN_DPAD_DOWN = 11
        const val BTN_DPAD_LEFT = 12
        const val BTN_DPAD_RIGHT = 13

        init {
            System.loadLibrary("mkw_android")
            try {
                SDL.initialize()
            } catch (e: Throwable) {
                android.util.Log.w("WiiCompiled", "SDL.initialize error: ${e.message}")
            }
        }
    }

    private external fun nativeInit(internalPath: String, resMultiplier: Float)
    private external fun nativeSurfaceCreated(surface: Any)
    private external fun nativeSurfaceDestroyed()
    private external fun nativeOnAppPause()
    private external fun nativeOnAppResume()
    private external fun nativeSetButton(buttonId: Int, isPressed: Boolean)
    private external fun nativeSetStick(stickX: Float, stickY: Float)
    private external fun nativeTiltEvent(angle: Float)
    private external fun nativeTouchEvent(action: Int, x: Float, y: Float, pointerId: Int)
    /** Called only when the Activity is truly being destroyed (user exiting the app). */
    private external fun nativeDestroy()
    private external fun nativeSetShowFps(enabled: Boolean)
    private external fun nativeIsShowFps(): Boolean

    private var activeResMultiplier: Float = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            SDL.setContext(this)
            SDL.setupJNI()
            SDLControllerManager.initialize()
            android.util.Log.i("WiiCompiled", "SDL.setContext, setupJNI and SDLControllerManager.initialize completed successfully")
        } catch (e: Throwable) {
            android.util.Log.e("WiiCompiled", "SDL setup failed: ${e.message}", e)
        }

        if (intent.getBooleanExtra("SUSTAINED_PERF", true)) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    val pManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    if (pManager?.isSustainedPerformanceModeSupported == true) {
                        window.setSustainedPerformanceMode(true)
                        android.util.Log.i("WiiCompiled", "Sustained performance mode ENABLED")
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("WiiCompiled", "Could not set sustained performance mode: ${e.message}")
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val gameManager = getSystemService(android.app.GameManager::class.java)
                gameManager?.setGameState(android.app.GameState(false, android.app.GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE))
            } catch (e: Throwable) {
                android.util.Log.w("WiiCompiled", "GameManager setGameState error: ${e.message}")
            }
        }

        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        extendToNotchEnabled = intent.getBooleanExtra("EXTEND_TO_NOTCH", prefs.getBoolean("extend_to_notch", true))

        // Fullscreen immersive mode & Display Cutout / Notch extension
        applyCutoutMode(extendToNotchEnabled)
        hideSystemBars()

        // Ensure status and navigation bars re-hide immediately if exposed by lock screen or notifications
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val isVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (isVisible) {
                window.decorView.post(rehideSystemUiRunnable)
                window.decorView.postDelayed(rehideSystemUiRunnable, 300)
            }
            insets
        }

        // Keep the screen on while racing; manual power-button locks still
        // exercise the pause path.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_game)

        surfaceView = findViewById(R.id.gameSurfaceView)

        btnGas = findViewById(R.id.btnGas)
        btnDrift = findViewById(R.id.btnDrift)
        btnItem = findViewById(R.id.btnItem)
        btnPause = findViewById(R.id.btnPause)
        steeringArea = findViewById(R.id.steeringArea)
        actionBtnContainer = findViewById(R.id.actionBtnContainer)

        touchOverlayContainer = findViewById(R.id.touchOverlayContainer)

        // Optional low-end direct scaling (old SurfaceFlinger path):
        // Configures the SurfaceView buffer to a small fixed size matching the internal render
        // resolution, so the device's hardware display processor (DPU) stretches it to the panel
        // with zero GPU fill-rate in the game process. Shader upscalers are bypassed entirely.
        val lowEndPresent = prefs.getBoolean("low_end_present", false)
        val resIdx = prefs.getInt("resolution_idx", 2)
        touchControlsEnabled = prefs.getBoolean("touch_controls", true)
        tiltControlsEnabled = prefs.getBoolean("tilt_controls", true)

        activeResMultiplier = when (resIdx) {
            0 -> 0.5f
            1 -> 0.75f
            2 -> 1.0f
            3 -> 1.5f
            4 -> 2.0f
            5 -> 3.0f
            else -> 1.0f
        }

        touchOverlayContainer.visibility = if (touchControlsEnabled) View.VISIBLE else View.GONE
        android.util.Log.i("WiiCompiled", "Controls configured: touch=$touchControlsEnabled, tilt=$tiltControlsEnabled")

        customKeyMap = ControllerConfig.getMapping(this)
        android.util.Log.i("WiiCompiled", "Loaded ${customKeyMap.size} custom controller mappings")

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val aspect = if (screenH > 0) screenW.toFloat() / screenH.toFloat() else (16f / 9f)

        if (lowEndPresent) {
            // Low-end direct scaling: render into a small buffer (matching the internal resolution)
            // and let SurfaceFlinger bilinearly stretch it to the full panel. No shader upscaler is
            // used, so fill rate stays at the internal resolution instead of the panel resolution.
            val targetH = when (resIdx) {
                0 -> 264   // 0.5x (240p/264p)
                1 -> 396   // 0.75x (360p/396p)
                2 -> 528   // 1.0x (480p/528p Native)
                3 -> 792   // 1.5x (720p/792p HD)
                4 -> 1056  // 2.0x (960p/1056p FHD)
                5 -> 1584  // 3.0x (1440p/1584p QHD)
                else -> 528
            }
            val targetW = (targetH * aspect).toInt().coerceAtLeast(1)
            surfaceView.holder.setFixedSize(targetW, targetH)
            android.util.Log.i("WiiCompiled", "Low-end hardware scaling configured: ${targetW}x${targetH} (SurfaceFlinger scales to ${screenW}x${screenH})")
        } else {
            // For shader upscaling (FSR 1.0, Bicubic, Nearest Neighbor) and native rendering,
            // the SurfaceView buffer must match the physical display resolution (setSizeFromLayout).
            // Aurora renders internally at the chosen resolution multiplier (0.5x, 0.75x, 1.0x, etc.)
            // and then its presentation shader upscales directly to the full-resolution swapchain.
            // If setFixedSize was used, Android's SurfaceFlinger hardware composer would bilinearly stretch
            // the low-res surface across the screen, bypassing and blurring the shader upscaler!
            surfaceView.holder.setSizeFromLayout()
            android.util.Log.i("WiiCompiled", "SurfaceView configured for full panel resolution: ${screenW}x${screenH} (resIdx: $resIdx, mult: $activeResMultiplier)")
        }

        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener(null)
        // Always wire the touch targets; visibility is managed by touchOverlayContainer
        // so the in-game menu can toggle touch controls at runtime.
        setupButtonTouch(btnGas, BTN_A)
        setupButtonTouch(btnDrift, BTN_B)
        setupButtonTouch(btnItem, BTN_L)
        setupButtonTouch(btnPause, BTN_START)
        setupSteeringTouch(steeringArea)

        snapshotDefaultControlLayout()

        // Apply saved custom touch control positions after the first layout pass
        if (TouchControlsLayout.hasCustomLayout(this)) {
            touchOverlayContainer.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        touchOverlayContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        applyCustomLayout()
                    }
                }
            )
        }

        // Wire up the Dolphin-style in-game settings menu
        ingameMenu = findViewById(R.id.ingameMenu)
        menuPause = findViewById(R.id.menuPause)
        menuTouchControls = findViewById(R.id.menuTouchControls)
        menuTiltControls = findViewById(R.id.menuTiltControls)
        menuNotch = findViewById(R.id.menuNotch)
        menuShowFps = findViewById(R.id.menuShowFps)
        menuExit = findViewById(R.id.menuExit)
        menuEditLayout = findViewById(R.id.menuEditLayout)
        menuResetLayout = findViewById(R.id.menuResetLayout)
        menuPause.setOnClickListener { togglePause() }
        menuTouchControls.setOnClickListener { toggleTouchControls() }
        menuTiltControls.setOnClickListener { toggleTiltControls() }
        menuNotch.setOnClickListener { toggleNotchExtension() }
        menuShowFps.setOnClickListener { toggleFpsOverlay() }
        menuExit.setOnClickListener { exitToLauncher() }
        menuEditLayout.setOnClickListener { openLayoutEditor() }
        menuResetLayout.setOnClickListener { confirmResetLayout() }
        menuTouchControls.text = if (touchControlsEnabled) "Touch Controls: On" else "Touch Controls: Off"
        menuTiltControls.text = if (tiltControlsEnabled) "Tilt Steering: On" else "Tilt Steering: Off"
        menuNotch.text = if (extendToNotchEnabled) "Notch Area: Extend" else "Notch Area: Inset"
        showFpsEnabled = prefs.getBoolean("show_fps", false)
        menuShowFps.text = if (showFpsEnabled) "FPS Overlay: On" else "FPS Overlay: Off"

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBackPressed()
            }
        })

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (tiltControlsEnabled) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            accelerometer = null
        }

        // Seed dsp_coef.bin from assets
        try {
            val dspBytes = assets.open("dsp_coef.bin").use { it.readBytes() }
            val targets = listOf(
                java.io.File(filesDir, "dsp_coef.bin"),
                java.io.File(java.io.File(filesDir, "WiiCompiled"), "dsp_coef.bin"),
                java.io.File(java.io.File(filesDir, "game_data"), "dsp_coef.bin"),
                java.io.File("/sdcard/Download/wiicompiled_data/dsp_coef.bin")
            )
            for (target in targets) {
                try {
                    val parent = target.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    if (parent != null && parent.exists() && (!target.exists() || target.length() < 1000L)) {
                        target.writeBytes(dspBytes)
                        android.util.Log.i("WiiCompiled", "Seeded dsp_coef.bin to ${target.absolutePath}")
                    }
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            android.util.Log.w("WiiCompiled", "Could not seed dsp_coef.bin from assets: ${e.message}")
        }

        // Seed pre-warmed shader pipeline cache from assets.
        //
        // The runtime imports the bundled seed (initial_pipeline_cache.db) into the live cache
        // on every start and prunes rows whose config_version is stale, so a bumped config
        // version self-heals. Always refresh that seed file from the APK so the runtime merge
        // sees the current build's rows; only bootstrap the live DB here when it is absent or
        // implausibly small, since the runtime merge handles first-boot and upgrade cases.
        try {
            val cacheDir = java.io.File(filesDir, "WiiCompiled/Cache")
            cacheDir.mkdirs()

            val seedFile = java.io.File(cacheDir, "initial_pipeline_cache.db")
            assets.open("initial_pipeline_cache.db").use { input ->
                java.io.FileOutputStream(seedFile).use { output ->
                    input.copyTo(output)
                }
            }

            val targetDb = java.io.File(cacheDir, "pipeline_cache.db")
            if (!targetDb.exists() || targetDb.length() < 100000L) {
                assets.open("initial_pipeline_cache.db").use { input ->
                    java.io.FileOutputStream(targetDb).use { output ->
                        input.copyTo(output)
                    }
                }
                // The runtime opens the live DB in WAL mode; stale sidecar files from a
                // previous session would otherwise be applied against the freshly seeded file.
                java.io.File(cacheDir, "pipeline_cache.db-wal").delete()
                java.io.File(cacheDir, "pipeline_cache.db-shm").delete()
                android.util.Log.i("WiiCompiled", "Seeded pre-warmed pipeline cache (${targetDb.length()} bytes)")
            }
        } catch (e: Throwable) {
            android.util.Log.w("WiiCompiled", "Could not seed pipeline cache from assets: ${e.message}")
        }

        // Seed wii_bootstrap assets
        try {
            val bootstrapDir = java.io.File(filesDir, "WiiCompiled/wii_bootstrap")
            if (!java.io.File(bootstrapDir, "shared2/wc24").exists()) {
                copyAssetFolder("wii_bootstrap", bootstrapDir)
                android.util.Log.i("WiiCompiled", "Extracted wii_bootstrap assets to ${bootstrapDir.absolutePath}")
            }
        } catch (e: Throwable) {
            android.util.Log.w("WiiCompiled", "Could not extract wii_bootstrap assets: ${e.message}")
        }

        // Check for required Mario Kart Wii game assets
        val hasSdcard = java.io.File("/sdcard/Download/wiicompiled_data/files").isDirectory &&
                        java.io.File("/sdcard/Download/wiicompiled_data/sys/main.dol").exists()
        val hasInternal = java.io.File(filesDir, "game_data/files").isDirectory &&
                          java.io.File(filesDir, "game_data/sys/main.dol").exists()

        if (!hasSdcard && !hasInternal) {
            android.util.Log.e("WiiCompiled", "No game data found on device!")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Game Assets Missing")
                .setMessage("Mario Kart Wii (RMCP01) game files were not found on this device.\n\nTo play:\n1. Place your extracted game assets into:\n   /sdcard/Download/wiicompiled_data/\n   (with 'files' and 'sys' folders)\n\nOR\n\n2. Return to the Launcher and select your .iso or .wbfs disc image.")
                .setPositiveButton("Open Launcher") { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setNegativeButton("Exit") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
            return
        }

        nativeInit(filesDir.absolutePath, activeResMultiplier)
    }

    private val defaultControlParams = mutableMapOf<View, FrameLayout.LayoutParams>()

    private fun snapshotDefaultControlLayout() {
        if (defaultControlParams.isNotEmpty()) return
        listOf(btnItem, btnPause, steeringArea, actionBtnContainer).forEach { view ->
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            defaultControlParams[view] = FrameLayout.LayoutParams(lp)
        }
    }

    private fun applyCustomLayout() {
        val cW = touchOverlayContainer.width
        val cH = touchOverlayContainer.height
        if (cW <= 0 || cH <= 0) return
        snapshotDefaultControlLayout()
        val views = mapOf(
            TouchControlsLayout.CTRL_ITEM to btnItem,
            TouchControlsLayout.CTRL_PAUSE to btnPause,
            TouchControlsLayout.CTRL_STEER to steeringArea,
            TouchControlsLayout.CTRL_ACTIONS to actionBtnContainer
        )
        for ((id, view) in views) {
            TouchControlsLayout.applyToView(view, TouchControlsLayout.load(this, id), cW, cH)
        }
    }

    private fun restoreDefaultControlLayout() {
        snapshotDefaultControlLayout()
        defaultControlParams.forEach { (view, lp) ->
            view.layoutParams = FrameLayout.LayoutParams(lp)
            view.translationX = 0f
            view.translationY = 0f
            view.requestLayout()
        }
    }

    private fun openLayoutEditor() {
        if (menuOpen) {
            menuOpen = false
            ingameMenu.visibility = View.GONE
        }
        editorLauncher.launch(Intent(this, TouchControlsEditorActivity::class.java))
    }

    private fun confirmResetLayout() {
        AlertDialog.Builder(this)
            .setTitle("Reset Layout")
            .setMessage("Restore on-screen controls to their default positions and sizes?")
            .setPositiveButton("Reset") { _, _ ->
                TouchControlsLayout.resetAll(this)
                restoreDefaultControlLayout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupButtonTouch(btn: Button, buttonId: Int) {
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.88f).scaleY(0.88f).alpha(0.9f).setDuration(50).start()
                    nativeSetButton(buttonId, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(50).start()
                    nativeSetButton(buttonId, false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSteeringTouch(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val halfW = v.width / 2.0f
                    val halfH = v.height / 2.0f
                    val normX = ((event.x - halfW) / halfW).coerceIn(-1.0f, 1.0f)
                    val normY = -((event.y - halfH) / halfH).coerceIn(-1.0f, 1.0f)
                    nativeSetStick(normX, normY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    nativeSetStick(0.0f, 0.0f)
                    true
                }
                else -> false
            }
        }
    }

    private val rehideSystemUiRunnable = Runnable {
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        window.decorView.post(rehideSystemUiRunnable)
        window.decorView.postDelayed(rehideSystemUiRunnable, 300)
        window.decorView.postDelayed(rehideSystemUiRunnable, 1000)
        if (!isFinishing) {
            nativeOnAppResume()
        }
        customKeyMap = ControllerConfig.getMapping(this)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            window.decorView.post(rehideSystemUiRunnable)
            window.decorView.postDelayed(rehideSystemUiRunnable, 300)
        }
    }

    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)?.let { controller ->
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        // Backgrounded (HOME, lock, task switch): freeze the guest clock and
        // release all held inputs so the game genuinely pauses while hidden.
        if (!isFinishing) {
            nativeOnAppPause()
        }
    }

    override fun onDestroy() {
        // Cleanly stop the game/render thread only when the user is truly exiting.
        // Do NOT call this on backgrounding — the thread must survive to allow seamless resume.
        if (isFinishing) {
            nativeDestroy()
        }
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        nativeSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        nativeSurfaceDestroyed()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Steering tilt calculated from Y/X axis in landscape
            val tiltAngle = (event.values[1] / 9.8f).coerceIn(-1.0f, 1.0f)
            nativeTiltEvent(tiltAngle)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- In-game settings menu ----
    // Pattern adapted from the Dolphin Emulator Android port, EmulationActivity.kt
    // (Back button toggles the menu, long-press Back exits emulation), distributed
    // under GPL-2.0-or-later. Source: https://github.com/dolphin-emu/dolphin
    // See THIRD-PARTY-NOTICES.md for full attribution.

    override fun onBackPressed() {
        menuOpen = !menuOpen
        ingameMenu.visibility = if (menuOpen) View.VISIBLE else View.GONE
    }

    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Long-pressing Back exits live to the launcher (Dolphin: stop emulation).
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            exitToLauncher()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun togglePause() {
        if (!menuOpen) return
        gamePaused = !gamePaused
        if (gamePaused) {
            nativeOnAppPause()
            menuPause.text = "Resume"
        } else {
            nativeOnAppResume()
            menuPause.text = "Pause"
        }
    }

    private fun toggleTouchControls() {
        if (!menuOpen) return
        touchControlsEnabled = !touchControlsEnabled
        val prefs = getSharedPreferences("wiicompiled_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("touch_controls", touchControlsEnabled).apply()
        touchOverlayContainer.visibility = if (touchControlsEnabled) View.VISIBLE else View.GONE
        menuTouchControls.text = if (touchControlsEnabled) "Touch Controls: On" else "Touch Controls: Off"
        android.util.Log.i("WiiCompiled", "In-game touch controls: $touchControlsEnabled")
    }

    private fun toggleTiltControls() {
        if (!menuOpen) return
        tiltControlsEnabled = !tiltControlsEnabled
        val prefs = getSharedPreferences("wiicompiled_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tilt_controls", tiltControlsEnabled).apply()
        if (tiltControlsEnabled) {
            if (accelerometer == null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            }
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            sensorManager.unregisterListener(this)
            accelerometer = null
        }
        menuTiltControls.text = if (tiltControlsEnabled) "Tilt Steering: On" else "Tilt Steering: Off"
        android.util.Log.i("WiiCompiled", "In-game tilt steering: $tiltControlsEnabled")
    }

    private fun toggleNotchExtension() {
        if (!menuOpen) return
        extendToNotchEnabled = !extendToNotchEnabled
        val prefs = getSharedPreferences("wiicompiled_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("extend_to_notch", extendToNotchEnabled).apply()

        applyCutoutMode(extendToNotchEnabled)
        menuNotch.text = if (extendToNotchEnabled) "Notch Area: Extend" else "Notch Area: Inset"
        android.util.Log.i("WiiCompiled", "In-game extend into display cutout: $extendToNotchEnabled")
    }

    private fun toggleFpsOverlay() {
        if (!menuOpen) return
        showFpsEnabled = !showFpsEnabled
        val prefs = getSharedPreferences("wiicompiled_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_fps", showFpsEnabled).apply()

        try {
            nativeSetShowFps(showFpsEnabled)
        } catch (_: Throwable) {}

        menuShowFps.text = if (showFpsEnabled) "FPS Overlay: On" else "FPS Overlay: Off"
        android.util.Log.i("WiiCompiled", "In-game FPS overlay: $showFpsEnabled")
    }

    private fun applyCutoutMode(extend: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (extend) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
            window.attributes = lp
        }
    }

    private fun exitToLauncher() {
        android.util.Log.i("WiiCompiled", "Exiting to launcher cleanly...")
        try {
            nativeOnAppPause()
        } catch (_: Throwable) {}

        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finishAndRemoveTask()

        // Clean up the isolated :game process so audio devices and guest execution threads terminate immediately
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 150)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event != null && (event.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            event.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK)) {
            val mapped = mapKeyCodeToButton(keyCode)
            if (mapped != null) {
                nativeSetButton(mapped, true)
                return true
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event != null && (event.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            event.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK)) {
            val mapped = mapKeyCodeToButton(keyCode)
            if (mapped != null) {
                nativeSetButton(mapped, false)
                return true
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event != null && !event.isCanceled) {
                onBackPressed()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && (event.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            event.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD)) {
            // Left Stick (steering and acceleration)
            var stickX = event.getAxisValue(MotionEvent.AXIS_X)
            var stickY = -event.getAxisValue(MotionEvent.AXIS_Y)

            // D-Pad from Hat axes (common on many Bluetooth / Xbox controllers)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            if (Math.abs(hatX) > 0.5f) {
                nativeSetButton(if (hatX > 0) BTN_DPAD_RIGHT else BTN_DPAD_LEFT, true)
            } else {
                nativeSetButton(BTN_DPAD_LEFT, false)
                nativeSetButton(BTN_DPAD_RIGHT, false)
            }

            if (Math.abs(hatY) > 0.5f) {
                nativeSetButton(if (hatY > 0) BTN_DPAD_DOWN else BTN_DPAD_UP, true)
            } else {
                nativeSetButton(BTN_DPAD_UP, false)
                nativeSetButton(BTN_DPAD_DOWN, false)
            }

            // Analog triggers (LT / RT on Xbox / PlayStation)
            val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceAtLeast(event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceAtLeast(event.getAxisValue(MotionEvent.AXIS_GAS))
            if (lt > 0.4f) {
                nativeSetButton(BTN_ZL, true)
            } else {
                nativeSetButton(BTN_ZL, false)
            }
            if (rt > 0.4f) {
                // RT used as Gas (A) or ZR
                nativeSetButton(BTN_A, true)
            }

            // Apply deadzone for stick
            if (Math.abs(stickX) < 0.15f) stickX = 0f
            if (Math.abs(stickY) < 0.15f) stickY = 0f

            nativeSetStick(stickX.coerceIn(-1.0f, 1.0f), stickY.coerceIn(-1.0f, 1.0f))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private var customKeyMap: Map<Int, Int> = emptyMap()

    private fun mapKeyCodeToButton(keyCode: Int): Int? {
        // First check user's custom controller configuration
        customKeyMap[keyCode]?.let { return it }

        // Default mapping fallback
        return when (keyCode) {
            // Xbox A / DualShock Cross / Nintendo B
            android.view.KeyEvent.KEYCODE_BUTTON_A -> BTN_A
            // Xbox B / DualShock Circle / Nintendo A
            android.view.KeyEvent.KEYCODE_BUTTON_B -> BTN_B
            // Xbox X / DualShock Square / Nintendo Y
            android.view.KeyEvent.KEYCODE_BUTTON_X -> BTN_X
            // Xbox Y / DualShock Triangle / Nintendo X
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y
            // Left Bumper (LB) / L1 -> Item (L)
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> BTN_L
            // Right Bumper (RB) / R1 -> Drift / Hop (R)
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> BTN_R
            // Left Trigger (LT) / L2 -> ZL
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> BTN_ZL
            // Right Trigger (RT) / R2 -> ZR
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> BTN_ZR
            // Start / Menu -> Plus / Pause
            android.view.KeyEvent.KEYCODE_BUTTON_START -> BTN_START
            // Select / View / Share -> Minus
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
            // D-Pad buttons
            android.view.KeyEvent.KEYCODE_DPAD_UP -> BTN_DPAD_UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DPAD_DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> BTN_DPAD_LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_DPAD_RIGHT
            else -> null
        }
    }

    private fun copyAssetFolder(assetPath: String, targetDir: java.io.File) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            targetDir.mkdirs()
            for (file in list) {
                val subAsset = if (assetPath.isEmpty()) file else "$assetPath/$file"
                copyAssetFolder(subAsset, java.io.File(targetDir, file))
            }
        }
    }
}

