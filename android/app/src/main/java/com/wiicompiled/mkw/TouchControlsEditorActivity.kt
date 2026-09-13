package com.wiicompiled.mkw

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Full-screen drag-and-resize editor for the on-screen touch controls.
 *
 * Each control widget is:
 *   • Dragged anywhere on screen by touching its body.
 *   • Resized by dragging the ◢ corner grip in its bottom-right corner.
 *
 * On **Save** the normalised positions/sizes are written via [TouchControlsLayout]
 * (shared JSON file) so GameActivity can re-apply them immediately.
 *
 * On **Reset** the on-screen preview returns to defaults; Save is still required to persist.
 * On **Cancel** no changes are committed.
 */
class TouchControlsEditorActivity : AppCompatActivity() {

    // Control IDs and display labels
    private data class ControlSpec(
        val id: String,
        val label: String,
        val bgColor: Int,
        val strokeColor: Int
    )

    private val controls = listOf(
        ControlSpec(TouchControlsLayout.CTRL_STEER,   "◀ STEER ▶",  0x2200BFFF, 0xCC00BFFF.toInt()),
        ControlSpec(TouchControlsLayout.CTRL_ACTIONS, "A  /  B",    0x22FF4444, 0xCCFF4444.toInt()),
        ControlSpec(TouchControlsLayout.CTRL_ITEM,    "L  ITEM",    0x22FFB300, 0xCCFFB300.toInt()),
        ControlSpec(TouchControlsLayout.CTRL_PAUSE,   "+  PAUSE",   0x22AAAAAA, 0xCCAAAAAA.toInt())
    )

    private lateinit var overlay: FrameLayout

    // Map from control ID → its editor wrapper view
    private val wrappers = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        setContentView(R.layout.activity_touch_editor)

        overlay = findViewById(R.id.editorOverlay)

        // Build draggable control widgets once the overlay has real dimensions
        overlay.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                overlay.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (wrappers.isEmpty()) buildControls()
            }
        })

        findViewById<MaterialButton>(R.id.editorBtnSave).setOnClickListener { saveAndFinish() }
        findViewById<MaterialButton>(R.id.editorBtnCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.editorBtnReset).setOnClickListener { confirmReset() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildControls() {
        val cW = overlay.width
        val cH = overlay.height
        if (cW == 0 || cH == 0) return

        for (spec in controls) {
            val layout = TouchControlsLayout.load(this, spec.id)
            val wrapper = buildWrapper(spec, layout, cW, cH)
            overlay.addView(wrapper)
            wrappers[spec.id] = wrapper
        }
    }

    /**
     * Creates a FrameLayout wrapper that:
     *   - has a coloured rounded rectangle background with a label
     *   - handles drag-to-move via raw touch on its body
     *   - handles pinch/corner-drag resize via the ◢ grip view in its BR corner
     */
    private fun buildWrapper(spec: ControlSpec, layout: TouchControlsLayout.ControlLayout,
                              cW: Int, cH: Int): View {
        val pxX = (layout.normX * cW)
        val pxY = (layout.normY * cH)
        val pxW = (layout.normW * cW).toInt().coerceAtLeast(64)
        val pxH = (layout.normH * cH).toInt().coerceAtLeast(48)

        // Outer wrapper: absolute-positioned FrameLayout
        val wrapper = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(pxW, pxH)
        wrapper.layoutParams = lp
        wrapper.x = pxX
        wrapper.y = pxY

        // ── Background panel ──
        val panel = buildPanel(spec)
        wrapper.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── Label ──
        val label = TextView(this).apply {
            text = spec.label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        wrapper.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── Resize grip (bottom-right corner) ──
        val grip = buildResizeGrip()
        val gripLp = FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.END or Gravity.BOTTOM)
        wrapper.addView(grip, gripLp)

        // ── Touch: move by dragging body, resize by dragging grip ──
        attachMoveListener(wrapper, panel, label)
        attachResizeListener(wrapper, grip, cW, cH)

        return wrapper
    }

    private fun buildPanel(spec: ControlSpec): View {
        val panel = View(this)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20.dp.toFloat()
            setColor(spec.bgColor)
            setStroke(3, spec.strokeColor)
        }
        panel.background = bg
        return panel
    }

    private fun buildResizeGrip(): ImageView {
        val grip = ImageView(this)
        grip.setImageResource(R.drawable.ic_resize_handle)
        grip.setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        grip.alpha = 0.85f
        return grip
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag to move
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachMoveListener(wrapper: FrameLayout, vararg bodyViews: View) {
        var dX = 0f
        var dY = 0f

        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val lp = wrapper.layoutParams
                val maxW = (overlay.width - wrapper.x).toInt().coerceAtLeast(64)
                val maxH = (overlay.height - wrapper.y).toInt().coerceAtLeast(48)
                lp.width = (wrapper.width * detector.scaleFactor).toInt().coerceIn(64, maxW)
                lp.height = (wrapper.height * detector.scaleFactor).toInt().coerceIn(48, maxH)
                wrapper.layoutParams = lp
                return true
            }
        })

        val moveListener = View.OnTouchListener { _, event ->
            scaler.onTouchEvent(event)
            if (event.pointerCount > 1) return@OnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = wrapper.x - event.rawX
                    dY = wrapper.y - event.rawY
                    wrapper.elevation = 8f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cW = overlay.width.toFloat()
                    val cH = overlay.height.toFloat()
                    val newX = (event.rawX + dX).coerceIn(0f, (cW - wrapper.width).coerceAtLeast(0f))
                    val newY = (event.rawY + dY).coerceIn(0f, (cH - wrapper.height).coerceAtLeast(0f))
                    wrapper.x = newX
                    wrapper.y = newY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    wrapper.elevation = 2f
                    true
                }
                else -> false
            }
        }
        bodyViews.forEach { it.setOnTouchListener(moveListener) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Corner-drag to resize
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachResizeListener(wrapper: FrameLayout, grip: View, cW: Int, cH: Int) {
        var startRawX = 0f
        var startRawY = 0f
        var startW    = 0
        var startH    = 0

        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startW = wrapper.width
                    startH = wrapper.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    val newW = (startW + dx).toInt().coerceIn(64, cW - wrapper.x.toInt())
                    val newH = (startH + dy).toInt().coerceIn(48, cH - wrapper.y.toInt())
                    val lp = wrapper.layoutParams
                    lp.width  = newW
                    lp.height = newH
                    wrapper.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save / Reset
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveAndFinish() {
        val cW = overlay.width
        val cH = overlay.height
        if (cW == 0 || cH == 0) { finish(); return }

        val layouts = wrappers.mapValues { (_, wrapper) ->
            TouchControlsLayout.fromView(wrapper, cW, cH)
        }
        TouchControlsLayout.saveAll(this, layouts)

        android.util.Log.i("WiiCompiled", "Touch layout saved by user")
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset Layout")
            .setMessage("Restore all controls to their default positions and sizes?")
            .setPositiveButton("Reset") { _, _ ->
                // Preview defaults only; file is unchanged until Save.
                val cW = overlay.width
                val cH = overlay.height
                for (spec in controls) {
                    val wrapper = wrappers[spec.id] ?: continue
                    val def = TouchControlsLayout.DEFAULTS[spec.id] ?: continue
                    wrapper.x = def.normX * cW
                    wrapper.y = def.normY * cH
                    val lp = wrapper.layoutParams
                    lp.width = (def.normW * cW).toInt().coerceAtLeast(64)
                    lp.height = (def.normH * cH).toInt().coerceAtLeast(48)
                    wrapper.layoutParams = lp
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Converts Int dp → px. */
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        // Treat back press as Cancel (no changes committed)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
