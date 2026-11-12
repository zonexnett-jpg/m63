package com.hexkeys.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

interface KeyboardListener {
    fun onCommit(text: String)
    fun onDeleteOne(): String?
    fun onRestore(text: String)
    fun onEnter()
}

private enum class Mode { NONE, DELETE, HOLD, REPEAT }

private enum class ShiftState { OFF, ONCE, LOCK }

private class Popup(val key: Key, var selected: Int)

class HexKeyboardView(context: Context) : View(context) {

    var listener: KeyboardListener? = null

    private val prefs = context.getSharedPreferences("hexkeys", Context.MODE_PRIVATE)
    private var arabic = prefs.getBoolean("arabic", false)
    private var symbols = false
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L
    private var editMode = false
    private var editFirst: Key? = null

    // ---- layouts (with the user's custom key order)
    private val bases: Map<String, List<Key>> = mapOf(
        "letters" to Layouts.letters,
        "arabic" to Layouts.arabic,
        "symbols" to Layouts.symbols
    )
    private val perms = HashMap<String, IntArray>()
    private val built = HashMap<String, List<Key>>()

    private fun layoutId() = when {
        symbols -> "symbols"
        arabic -> "arabic"
        else -> "letters"
    }

    private fun perm(id: String): IntArray = perms.getOrPut(id) {
        val n = bases.getValue(id).size
        val saved = prefs.getString("perm_$id", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (saved != null && saved.size == n && saved.sorted() == (0 until n).toList()) {
            saved.toIntArray()
        } else {
            IntArray(n) { it }
        }
    }

    private val keys: List<Key>
        get() {
            val id = layoutId()
            return built.getOrPut(id) {
                val base = bases.getValue(id)
                val p = perm(id)
                List(base.size) { slot ->
                    base[p[slot]].copy(x = base[slot].x, row = base[slot].row)
                }
            }
        }

    // ---- geometry (w = key width, r = hexagon radius)
    private var w = 100f
    private var r = 57.7f
    private var padTop = 14f
    private var offX = 0f

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(10f)
    }
    private val flat = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    // ---- touch state
    private var activeId = -1
    private var downX = 0f
    private var downY = 0f
    private var downKey: Key? = null
    private var pressedKey: Key? = null
    private var mode = Mode.NONE
    private var consumed = false
    private var popup: Popup? = null
    private val deleted = ArrayDeque<String>()
    private var lastX = 0f
    private var deleteCount = 0
    private var repeatCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable { startHold() }
    private val repeat = object : Runnable {
        override fun run() {
            listener?.onDeleteOne()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            repeatCount++
            handler.postDelayed(this, max(35L, 90L - repeatCount * 4L))
        }
    }

    /** Called when a new input field starts: back to letters, reload saved layout. */
    fun showLetters() {
        symbols = false
        shift = ShiftState.OFF
        editMode = false
        editFirst = null
        arabic = prefs.getBoolean("arabic", false)
        perms.clear()
        built.clear()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        // keyboard height is 8.5r = 4.907w; cap it at half the screen height (landscape / tablets)
        val maxH = resources.displayMetrics.heightPixels * 0.5f
        w = minOf(width / 7.2f, maxH / 4.907f)
        offX = (width - 7.2f * w) / 2f
        r = w / sqrt(3f)
        padTop = 0.25f * r
        setMeasuredDimension(width, (padTop * 2 + 8f * r).toInt())
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- drawing

    private fun keyColor(d: Boolean) = if (d) Color.rgb(58, 58, 58) else Color.WHITE
    private fun pressedColor(d: Boolean) = if (d) Color.rgb(95, 95, 95) else Color.rgb(222, 226, 234)
    private fun bandColor(d: Boolean) = if (d) Color.rgb(44, 44, 44) else Color.rgb(230, 231, 236)
    private fun textColor(d: Boolean) = if (d) Color.WHITE else Color.rgb(30, 30, 30)
    private fun dimColor(d: Boolean) = if (d) Color.rgb(150, 150, 150) else Color.rgb(120, 120, 120)

    private fun cxOf(k: Key) = offX + k.x * w

    private fun centerY(k: Key) = padTop + r + k.row * 1.5f * r

    private fun applyShift(s: String) =
        if (shift != ShiftState.OFF && !arabic && !symbols) s.uppercase() else s

    private fun hexPath(cx: Float, cy: Float, rad: Float) {
        path.reset()
        for (i in 0 until 6) {
            val a = Math.toRadians(60.0 * i - 90.0)
            val x = cx + rad * cos(a).toFloat()
            val y = cy + rad * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        canvas.drawColor(if (dark) Color.BLACK else Color.rgb(208, 212, 221))
        for (k in keys) drawKey(canvas, k, dark)
        popup?.let { drawPopup(canvas, it, dark) }
    }

    private fun drawKey(c: Canvas, k: Key, dark: Boolean) {
        if (k.kind == Kind.SHIFT || k.kind == Kind.BACKSPACE) {
            drawSide(c, k, dark)
            return
        }
        val cx = cxOf(k)
        val cy = centerY(k)
        hexPath(cx, cy, r * 0.94f)
        val pressed = k === pressedKey
        keyPaint.color = when {
            editMode && k === editFirst -> Color.rgb(255, 170, 60)
            editMode -> if (dark) Color.rgb(55, 80, 105) else Color.rgb(205, 228, 248)
            k.kind == Kind.ENTER -> if (pressed) Color.rgb(20, 60, 170) else Color.rgb(30, 90, 230)
            pressed -> pressedColor(dark)
            else -> keyColor(dark)
        }
        c.drawPath(path, keyPaint)

        if (k.kind == Kind.PUNCT || k.kind == Kind.TOGGLE) {
            c.save()
            c.clipPath(path)
            flat.color = bandColor(dark)
            c.drawRect(cx - r, cy + 0.05f * r, cx + r, cy + r, flat)
            c.restore()
        }

        when (k.kind) {
            Kind.CHAR -> {
                label(c, applyShift(k.main), cx, cy, 0.42f * w, textColor(dark))
                if (k.showUp) label(c, k.up ?: "", cx + 0.5f * r, cy - 0.5f * r, 0.2f * w, dimColor(dark))
            }
            Kind.PUNCT -> {
                label(c, k.up ?: "", cx, cy - 0.3f * r, 0.3f * w, textColor(dark))
                label(c, k.main, cx, cy + 0.45f * r, 0.3f * w, textColor(dark))
                label(c, k.hint, cx + 0.55f * r, cy + 0.1f * r, 0.18f * w, dimColor(dark))
            }
            Kind.TOGGLE -> {
                label(c, if (arabic) "EN" else "ع", cx, cy - 0.35f * r, 0.24f * w, dimColor(dark))
                label(c, k.main, cx, cy + 0.5f * r, 0.22f * w, textColor(dark))
            }
            Kind.ENTER -> arrow(c, cx, cy)
            Kind.SPACE, Kind.SHIFT, Kind.BACKSPACE -> Unit
        }
    }

    /** Shift and backspace are plain icons at the sides, not hexagons. */
    private fun drawSide(c: Canvas, k: Key, dark: Boolean) {
        val cx = cxOf(k)
        val cy = centerY(k)
        val s = 0.3f * w
        val col = if (editMode) dimColor(dark) else textColor(dark)
        stroke.color = col
        stroke.strokeWidth = s * 0.16f
        flat.color = col
        path.reset()
        if (k.kind == Kind.SHIFT) {
            path.moveTo(cx, cy - 0.9f * s)
            path.lineTo(cx + 0.85f * s, cy)
            path.lineTo(cx + 0.35f * s, cy)
            path.lineTo(cx + 0.35f * s, cy + 0.8f * s)
            path.lineTo(cx - 0.35f * s, cy + 0.8f * s)
            path.lineTo(cx - 0.35f * s, cy)
            path.lineTo(cx - 0.85f * s, cy)
            path.close()
            c.drawPath(path, if (shift == ShiftState.OFF) stroke else flat)
            if (shift == ShiftState.LOCK) {
                c.drawRect(cx - 0.35f * s, cy + 1.0f * s, cx + 0.35f * s, cy + 1.15f * s, flat)
            }
        } else {
            path.moveTo(cx - 1.0f * s, cy)
            path.lineTo(cx - 0.4f * s, cy - 0.7f * s)
            path.lineTo(cx + 0.9f * s, cy - 0.7f * s)
            path.lineTo(cx + 0.9f * s, cy + 0.7f * s)
            path.lineTo(cx - 0.4f * s, cy + 0.7f * s)
            path.close()
            c.drawPath(path, stroke)
            path.reset()
            path.moveTo(cx - 0.05f * s, cy - 0.3f * s)
            path.lineTo(cx + 0.5f * s, cy + 0.3f * s)
            path.moveTo(cx + 0.5f * s, cy - 0.3f * s)
            path.lineTo(cx - 0.05f * s, cy + 0.3f * s)
            c.drawPath(path, stroke)
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        if (s.isEmpty()) return
        textPaint.textSize = size
        textPaint.color = color
        val fm = textPaint.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float) {
        stroke.color = Color.WHITE
        stroke.strokeWidth = r * 0.14f
        path.reset()
        path.moveTo(cx + 0.35f * r, cy - 0.35f * r)
        path.lineTo(cx + 0.35f * r, cy + 0.1f * r)
        path.lineTo(cx - 0.35f * r, cy + 0.1f * r)
        path.moveTo(cx - 0.12f * r, cy - 0.12f * r)
        path.lineTo(cx - 0.35f * r, cy + 0.1f * r)
        path.lineTo(cx - 0.12f * r, cy + 0.32f * r)
        c.drawPath(path, stroke)
    }

    private fun drawPopup(c: Canvas, p: Popup, dark: Boolean) {
        val cell = 0.8f * w
        val n = p.key.hold.size
        val ph = 0.85f * w
        val pw = n * cell
        val left = (cxOf(p.key) - (p.key.holdStart + 0.5f) * cell).coerceIn(0f, max(0f, width - pw))
        val cy = max(ph / 2 + 4f, centerY(p.key) - 1.5f * w)
        rect.set(left, cy - ph / 2, left + pw, cy + ph / 2)
        flat.color = if (dark) Color.rgb(40, 40, 40) else Color.WHITE
        c.drawRoundRect(rect, 16f, 16f, flat)
        for (i in 0 until n) {
            val ccx = left + (i + 0.5f) * cell
            val sel = i == p.selected
            if (sel) {
                flat.color = Color.rgb(77, 182, 232)
                rect.set(ccx - cell / 2 + 4, cy - ph / 2 + 4, ccx + cell / 2 - 4, cy + ph / 2 - 4)
                c.drawRoundRect(rect, 12f, 12f, flat)
            }
            val txt = if (p.key.holdLabels.isNotEmpty()) p.key.holdLabels[i] else applyShift(p.key.hold[i])
            label(c, txt, ccx, cy, 0.4f * w, if (sel) Color.WHITE else textColor(dark))
        }
    }

    // ---------------------------------------------------------------- touch

    private fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = x - cxOf(k)
            val dy = y - centerY(k)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        val lim = 0.8f * w
        return if (bestD < lim * lim) best else null
    }

    private fun tick() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                downX = e.x
                downY = e.y
                val k = keyAt(downX, downY)
                downKey = k
                pressedKey = k
                mode = Mode.NONE
                consumed = false
                popup = null
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeat)
                if (k != null && k.kind == Kind.BACKSPACE && !editMode) {
                    mode = Mode.REPEAT
                    repeatCount = 0
                    listener?.onDeleteOne()
                    tick()
                    handler.postDelayed(repeat, 400)
                } else if (k != null && (k.kind == Kind.TOGGLE || (k.hold.isNotEmpty() && !editMode))) {
                    handler.postDelayed(longPress, 350)
                }
                invalidate()
            }

            // second finger (two-thumb typing): finish the first key, then tap the new one
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == Mode.NONE) {
                if (!consumed) {
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) release(e.getX(i), e.getY(i))
                    consumed = true
                    pressedKey = null
                    handler.removeCallbacks(longPress)
                }
                val j = e.actionIndex
                keyAt(e.getX(j), e.getY(j))?.let { activate(it) }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0 && !consumed) {
                    val x = e.getX(i)
                    val y = e.getY(i)
                    when (mode) {
                        Mode.NONE -> {
                            val dx = x - downX
                            val dy = y - downY
                            if (!editMode && dx < -0.35f * w && abs(dx) > abs(dy)) {
                                startDelete(x)
                            } else if (abs(dx) > 0.3f * w || abs(dy) > 0.3f * w) {
                                handler.removeCallbacks(longPress)
                            }
                        }
                        Mode.DELETE -> updateDelete(x)
                        Mode.HOLD -> updateHold(x)
                        Mode.REPEAT -> Unit
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                if (e.getPointerId(idx) == activeId) {
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(repeat)
                    if (!consumed) {
                        when (mode) {
                            Mode.HOLD -> popup?.let {
                                commit(it.key.hold[it.selected])
                                tick()
                            }
                            Mode.DELETE, Mode.REPEAT -> Unit
                            Mode.NONE -> release(e.getX(idx), e.getY(idx))
                        }
                    }
                    resetTouch()
                }
            }

            MotionEvent.ACTION_CANCEL -> resetTouch()
        }
        return true
    }

    private fun resetTouch() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        activeId = -1
        consumed = true
        mode = Mode.NONE
        popup = null
        pressedKey = null
        invalidate()
    }

    /** Commits text (applying Shift) and consumes a one-shot Shift. */
    private fun commit(s: String) {
        listener?.onCommit(applyShift(s))
        if (shift == ShiftState.ONCE) {
            shift = ShiftState.OFF
            invalidate()
        }
    }

    /** Finger lifted without delete/hold mode: swipe up = alternate char, otherwise tap. */
    private fun release(x: Float, y: Float) {
        val k = downKey ?: return
        if (editMode) {
            activate(k)
            return
        }
        val dx = x - downX
        val dy = y - downY
        val up = k.up
        val swipeUp = dy < -0.35f * w && abs(dy) > abs(dx)
        if (swipeUp && k.kind == Kind.TOGGLE) {
            arabic = !arabic
            symbols = false
            shift = ShiftState.OFF
            prefs.edit().putBoolean("arabic", arabic).apply()
            tick()
            invalidate()
        } else if (up != null && swipeUp) {
            commit(up)
            tick()
        } else {
            activate(k)
        }
    }

    private fun activate(k: Key) {
        if (editMode) {
            editTap(k)
            return
        }
        when (k.kind) {
            Kind.CHAR, Kind.PUNCT -> commit(k.main)
            Kind.SPACE -> commit(" ")
            Kind.ENTER -> listener?.onEnter()
            Kind.TOGGLE -> symbols = !symbols
            Kind.SHIFT -> cycleShift()
            Kind.BACKSPACE -> listener?.onDeleteOne()
        }
        tick()
        invalidate()
    }

    private fun cycleShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.LOCK -> ShiftState.OFF
            shift == ShiftState.ONCE && now - lastShiftTap < 400 -> ShiftState.LOCK
            shift == ShiftState.ONCE -> ShiftState.OFF
            else -> ShiftState.ONCE
        }
        lastShiftTap = now
    }

    // ---- edit mode: tap two keys to swap them (with all their functions)

    private fun editTap(k: Key) {
        if (k.kind == Kind.SHIFT || k.kind == Kind.BACKSPACE) return
        val f = editFirst
        if (f == null) {
            editFirst = k
        } else if (f === k) {
            editFirst = null
        } else {
            swapKeys(f, k)
            editFirst = null
        }
        tick()
        invalidate()
    }

    private fun swapKeys(a: Key, b: Key) {
        val id = layoutId()
        val list = keys
        val i = list.indexOfFirst { it === a }
        val j = list.indexOfFirst { it === b }
        if (i < 0 || j < 0 || i == j) return
        val p = perm(id)
        val t = p[i]
        p[i] = p[j]
        p[j] = t
        built.remove(id)
        prefs.edit().putString("perm_$id", p.joinToString(",")).apply()
    }

    // ---- swipe-left delete (with restore when swiping back right)

    private fun step() = max(0.2f * w, 0.5f * w - deleteCount * 0.03f * w)

    private fun startDelete(x: Float) {
        handler.removeCallbacks(longPress)
        mode = Mode.DELETE
        pressedKey = null
        deleted.clear()
        deleteCount = 0
        lastX = x
        deleteOne()
        invalidate()
    }

    private fun updateDelete(x: Float) {
        while (x < lastX - step()) {
            lastX -= step()
            deleteOne()
        }
        while (x > lastX + step()) {
            if (deleted.isEmpty()) { lastX = x; break }
            lastX += step()
            restoreOne()
        }
    }

    private fun deleteOne() {
        val s = listener?.onDeleteOne() ?: return
        deleted.addLast(s)
        deleteCount++
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun restoreOne() {
        val s = deleted.removeLast()
        listener?.onRestore(s)
        deleteCount = max(0, deleteCount - 1)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // ---- long press: popup on keys, edit-mode switch on the 123 key

    private fun startHold() {
        val k = downKey ?: return
        if (mode != Mode.NONE || consumed) return
        if (k.kind == Kind.TOGGLE) {
            editMode = !editMode
            editFirst = null
            consumed = true
            pressedKey = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
        }
        if (editMode || k.hold.isEmpty()) return
        popup = Popup(k, k.holdStart)
        mode = Mode.HOLD
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun updateHold(x: Float) {
        val p = popup ?: return
        val cell = 0.8f * w
        val idx = (p.key.holdStart + ((x - downX) / cell).roundToInt())
            .coerceIn(0, p.key.hold.size - 1)
        if (idx != p.selected) {
            p.selected = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }
}
