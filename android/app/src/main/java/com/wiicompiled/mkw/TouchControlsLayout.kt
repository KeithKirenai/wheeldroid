package com.wiicompiled.mkw

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File

/**
 * Manages the persistent custom positions and sizes for each on-screen touch control.
 *
 * Coordinates are stored **normalized** (0.0..1.0 relative to the parent container
 * width/height) so that the layout is screen-resolution-independent.
 *
 * Stored as a JSON file in [Context.getFilesDir] so the launcher process and the
 * isolated `:game` process always see the same layout.
 *
 * Controls:
 *   - "item"    → L item button
 *   - "pause"   → + pause button
 *   - "steer"   → virtual steering pad
 *   - "actions" → A/B action button group
 */
object TouchControlsLayout {

    private const val FILE_NAME = "touch_controls_layout.json"

    // Identifiers used as JSON keys (stable, not resource IDs which may change between builds)
    const val CTRL_ITEM    = "item"
    const val CTRL_PAUSE   = "pause"
    const val CTRL_STEER   = "steer"
    const val CTRL_ACTIONS = "actions"

    /** Normalized position + size snapshot for a single control. */
    data class ControlLayout(
        /** Normalized X of the view's left edge, 0.0 = left of container, 1.0 = right */
        val normX: Float,
        /** Normalized Y of the view's top edge, 0.0 = top of container, 1.0 = bottom */
        val normY: Float,
        /** Normalized width relative to container width */
        val normW: Float,
        /** Normalized height relative to container height */
        val normH: Float
    )

    /** Default layouts expressed in normalised coordinates for a 16:9 landscape layout. */
    val DEFAULTS: Map<String, ControlLayout> = mapOf(
        CTRL_ITEM    to ControlLayout(0.03f,  0.05f, 0.09f, 0.14f),  // top-left L button
        CTRL_PAUSE   to ControlLayout(0.89f,  0.05f, 0.08f, 0.12f),  // top-right + button
        CTRL_STEER   to ControlLayout(0.03f,  0.65f, 0.22f, 0.32f),  // bottom-left steering
        CTRL_ACTIONS to ControlLayout(0.72f,  0.50f, 0.27f, 0.45f)   // bottom-right A/B group
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readRoot(context: Context): JSONObject? {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            JSONObject(f.readText())
        } catch (_: Throwable) {
            null
        }
    }

    /** Save a layout for a single control ID string. */
    fun save(context: Context, id: String, layout: ControlLayout) {
        val all = loadAll(context).toMutableMap()
        all[id] = layout
        saveAll(context, all)
    }

    fun saveAll(context: Context, layouts: Map<String, ControlLayout>) {
        val root = JSONObject()
        for ((id, layout) in layouts) {
            root.put(id, JSONObject().apply {
                put("x", layout.normX.toDouble())
                put("y", layout.normY.toDouble())
                put("w", layout.normW.toDouble())
                put("h", layout.normH.toDouble())
            })
        }
        file(context).writeText(root.toString())
    }

    /** Load a layout for a single control ID, falling back to the built-in default. */
    fun load(context: Context, id: String): ControlLayout {
        val def = DEFAULTS[id] ?: ControlLayout(0f, 0f, 0.1f, 0.1f)
        val obj = readRoot(context)?.optJSONObject(id) ?: return def
        return ControlLayout(
            normX = obj.optDouble("x", def.normX.toDouble()).toFloat(),
            normY = obj.optDouble("y", def.normY.toDouble()).toFloat(),
            normW = obj.optDouble("w", def.normW.toDouble()).toFloat(),
            normH = obj.optDouble("h", def.normH.toDouble()).toFloat()
        )
    }

    /** Load all four control layouts at once. */
    fun loadAll(context: Context): Map<String, ControlLayout> =
        DEFAULTS.keys.associateWith { load(context, it) }

    /** Reset all control layouts to built-in defaults. */
    fun resetAll(context: Context) {
        file(context).delete()
    }

    /** Returns true if the user has saved a custom layout file. */
    fun hasCustomLayout(context: Context): Boolean {
        val f = file(context)
        return f.exists() && f.length() > 0L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Applying layouts to live views
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies a [ControlLayout] to a [View] immediately after its parent has been laid out.
     * Clears FrameLayout gravity/margins so the control is placed from the top-left of
     * the overlay using the normalised coordinates.
     *
     * @param view        The control view to reposition.
     * @param layout      Normalised layout to apply.
     * @param containerW  Parent container pixel width (must be > 0).
     * @param containerH  Parent container pixel height (must be > 0).
     */
    fun applyToView(view: View, layout: ControlLayout, containerW: Int, containerH: Int) {
        if (containerW <= 0 || containerH <= 0) return

        val pxX = layout.normX * containerW
        val pxY = layout.normY * containerH
        val pxW = (layout.normW * containerW).toInt().coerceAtLeast(32)
        val pxH = (layout.normH * containerH).toInt().coerceAtLeast(32)

        val lp = view.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = pxW
            lp.height = pxH
            lp.leftMargin = pxX.toInt()
            lp.topMargin = pxY.toInt()
            lp.rightMargin = 0
            lp.bottomMargin = 0
            view.layoutParams = lp
        } else if (lp != null) {
            lp.width = pxW
            lp.height = pxH
            view.layoutParams = lp
            view.x = pxX
            view.y = pxY
        }
        view.translationX = 0f
        view.translationY = 0f
        view.requestLayout()
    }

    /**
     * Converts the current pixel position of [view] within its parent back to a
     * [ControlLayout] with normalised coordinates. Call this when saving the editor result.
     */
    fun fromView(view: View, containerW: Int, containerH: Int): ControlLayout {
        if (containerW <= 0 || containerH <= 0) return DEFAULTS.values.first()
        val lp = view.layoutParams
        val w = (if (lp != null && lp.width > 0) lp.width else view.width).toFloat()
        val h = (if (lp != null && lp.height > 0) lp.height else view.height).toFloat()
        return ControlLayout(
            normX = (view.x / containerW).coerceIn(0f, 1f),
            normY = (view.y / containerH).coerceIn(0f, 1f),
            normW = (w / containerW).coerceIn(0.03f, 1f),
            normH = (h / containerH).coerceIn(0.03f, 1f)
        )
    }
}
