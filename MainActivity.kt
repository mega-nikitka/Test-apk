package com.example.cam8k

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

private val RED = Color.rgb(217, 38, 44)

class ShutterView(c: Context) : View(c) {
    var photo = false
        set(v) { field = v; invalidate() }
    var morph = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null

    fun animateRecording(on: Boolean) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(morph, if (on) 1f else 0f).apply {
            duration = 220
            addUpdateListener { morph = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.05f
        p.color = Color.WHITE
        cv.drawCircle(cx, cy, r - p.strokeWidth, p)
        p.style = Paint.Style.FILL
        if (photo) {
            p.color = Color.WHITE
            cv.drawCircle(cx, cy, r * 0.74f, p)
        } else {
            p.color = RED
            val half = r * (0.72f - 0.34f * morph)
            val corner = half * (1f - 0.75f * morph)
            cv.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, p)
        }
    }
}

class ThirdsView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 2f
    }

    override fun onDraw(cv: Canvas) {
        for (i in 1..2) {
            cv.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), p)
            cv.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, p)
        }
    }
}

class MainActivity : Activity() {

    private class Lens(val label: String, val zoom: Float, val phys: String?, val ratio: Float)
    private class Mode(val label: String, val w: Int, val h: Int, val fps: Int)

    private val thread = HandlerThread("cam").also { it.start() }
    private val handler by lazy { Handler(thread.looper) }
    private val executor = Executor { handler.post(it) }
    private val ui = Handler(Looper.getMainLooper())
    private val mgr by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val prefs by lazy { getSharedPreferences("cam8k", MODE_PRIVATE) }

    private var supported = listOf<Mode>()
    private var fpsRanges = listOf<Range<Int>>()
    private var cur = Mode("1080", 1920, 1080, 30)
    private var photoSize = Size(4000, 3000)
    private var photoMode = false
    private var front = false
    private var zoom = 1f
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var physId: String? = null
    private var sensorOrientation = 90
    @Volatile private var recording = false
    @Volatile private var gen = 0
    @Volatile private var zoomPending = false
    @Volatile private var measured = 0.0
    @Volatile private var lowSecs = 0
    private var tsStart = 0L
    private var frames = 0

    private var chars: CameraCharacteristics? = null
    private var maxAf = 0
    private var maxAe = 0
    private var hdrAvail = false
    private var focusRegion: MeteringRectangle? = null
    private var wmOn = true
    private var hdrOn = false
    private var gridOn = false
    private var lastUri: Uri? = null
    private var lastVideo = false
    private var downX = 0f
    private var downY = 0f
    private var pinched = false

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var reader: ImageReader? = null
    private var file: File? = null
    private var previewSurface: Surface? = null
    private var recSurface: Surface? = null
    private var lenses = listOf<Lens>()
    private var t0 = 0L
    private var drawerOpen = false
    private var zoomAnim: ValueAnimator? = null
    private val panels = mutableListOf<GlassPanel>()

    private lateinit var stage: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var grid: ThirdsView
    private lateinit var ring: View
    private lateinit var thumb: ImageView
    private lateinit var shutter: ShutterView
    private lateinit var timer: TextView
    private lateinit var pillText: TextView
    private lateinit var infoLine: TextView
    private lateinit var pill: GlassPanel
    private lateinit var drawer: GlassPanel
    private lateinit var lensPanel: GlassPanel
    private lateinit var resRow: LinearLayout
    private lateinit var fpsRow: LinearLayout
    private lateinit var tgRow: LinearLayout
    private lateinit var lensRow: LinearLayout
    private lateinit var modeVideo: TextView
    private lateinit var modePhoto: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (recording) {
                val s = (SystemClock.elapsedRealtime() - t0) / 1000
                timer.text = "●  %02d:%02d".format(s / 60, s % 60)
                timer.alpha = if (s % 2 == 0L) 1f else 0.55f
                ui.postDelayed(this, 500)
            }
        }
    }

    private val glassTick = object : Runnable {
        override fun run() {
            if (!recording) refreshGlass(tv, panels)
            ui.postDelayed(this, 400)
        }
    }

    private val cb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (tsStart == 0L) { tsStart = ts; frames = 0; return }
            frames++
            if (ts - tsStart >= 1_000_000_000L) {
                measured = frames * 1e9 / (ts - tsStart)
                tsStart = ts
                frames = 0
                val target = cur.fps
                if (!photoMode && physId != null && target > 30 && measured < target * 0.6) lowSecs++ else lowSecs = 0
                if (lowSecs >= 4 && !recording) {
                    lowSecs = 0
                    val m = supported.firstOrNull { it.w == cur.w && it.h == cur.h && it.fps == 30 }
                    if (m != null) {
                        cur = m
                        toast("Этот модуль не держит $target fps, переключил на 30")
                        startPreview()
                    }
                }
                runOnUiThread { refreshInfo() }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun chip(t: String, minW: Int = 0, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        minWidth = dp(minW)
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { click() }
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t
        textSize = 10f
        letterSpacing = 0.18f
        setTextColor(Color.argb(170, 255, 255, 255))
        setPadding(dp(6), dp(6), 0, dp(2))
    }

    private fun panel(content: View, r: Int, w: Int = -2): GlassPanel {
        val p = GlassPanel(this, tv, dp(r).toFloat())
        p.addView(content, FrameLayout.LayoutParams(w, -2))
        panels += p
        return p
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        wmOn = prefs.getBoolean("wm", true)
        hdrOn = prefs.getBoolean("hdr", false)
        gridOn = prefs.getBoolean("grid", false)
        val sw = resources.displayMetrics.widthPixels
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        stage = FrameLayout(this)
        tv = TextureView(this)
        stage.addView(tv, FrameLayout.LayoutParams(-1, -1))
        grid = ThirdsView(this)
        grid.visibility = if (gridOn) View.VISIBLE else View.GONE
        stage.addView(grid, FrameLayout.LayoutParams(-1, -1))
        ring = View(this)
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), Color.WHITE)
        }
        ring.visibility = View.GONE
        stage.addView(ring, FrameLayout.LayoutParams(dp(64), dp(64)))
        root.addView(stage, FrameLayout.LayoutParams(sw, sw * 16 / 9, Gravity.TOP).apply { topMargin = dp(56) })

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startPreview() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinched = true
                if (physId == null) changeZoom(zoom * d.scaleFactor)
                return true
            }
        })
        tv.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; pinched = false }
                MotionEvent.ACTION_UP -> {
                    if (drawerOpen) toggleDrawer(false)
                    else if (!pinched && abs(e.x - downX) < dp(12) && abs(e.y - downY) < dp(12))
                        focusAt(e.x / v.width, e.y / v.height, e.x, e.y)
                }
            }
            true
        }

        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(235, 0, 0, 0))
        )
        root.addView(scrim, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))

        pillText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        pill = panel(pillText, 18)
        pill.setOnClickListener { toggleDrawer(!drawerOpen) }
        pill.setOnLongClickListener { showInfo(); true }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })

        val dot = View(this)
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED) }
        root.addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply { topMargin = dp(22); marginEnd = dp(22) })

        infoLine = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setTextColor(Color.argb(200, 255, 255, 255))
        }
        root.addView(infoLine, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })

        timer = TextView(this).apply {
            setTextColor(RED)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        root.addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(18); marginStart = dp(18) })

        resRow = LinearLayout(this)
        fpsRow = LinearLayout(this)
        tgRow = LinearLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(caption("РАЗРЕШЕНИЕ"))
            addView(resRow)
            addView(caption("КАДРОВ В СЕКУНДУ"))
            addView(fpsRow)
            addView(caption("КАДР И ФОТО"))
            addView(tgRow)
        }
        drawer = panel(col, 22, -1)
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(88); marginStart = dp(12); marginEnd = dp(12) })

        lensRow = LinearLayout(this).apply { setPadding(dp(6), dp(3), dp(6), dp(3)) }
        lensPanel = panel(lensRow, 24)
        root.addView(lensPanel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(168) })

        val modes = LinearLayout(this)
        modeVideo = chip("ВИДЕО") { switchPhoto(false) }
        modePhoto = chip("ФОТО") { switchPhoto(true) }
        modes.addView(modeVideo)
        modes.addView(modePhoto)
        root.addView(modes, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(122) })

        shutter = ShutterView(this)
        shutter.setOnClickListener {
            shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
                shutter.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }.start()
            if (photoMode) takePhoto() else toggleRecord()
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })

        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.argb(60, 255, 255, 255))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
            }
            setOnClickListener { openLast() }
        }
        root.addView(thumb, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(48); marginStart = dp(32) })

        val flip = chip("⟲") {
            if (!recording) { front = !front; zoom = 1f; physId = null; startPreview() }
        }
        flip.textSize = 30f
        root.addView(flip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(48); marginEnd = dp(32) })

        setContentView(root)

        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        startPreview()
    }

    override fun onResume() {
        super.onResume()
        ui.post(glassTick)
        loadLast()
        if (tv.isAvailable) startPreview()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(glassTick)
        gen++
        handler.post { if (recording) finishRecording(false) else closeAll() }
    }    // ---------- UI helpers ----------

    private fun fmt(z: Float) =
        if (abs(z - Math.round(z)) < 0.05f) "${Math.round(z)}" else String.format(Locale.US, "%.1f", z)

    private fun refreshInfo() {
        infoLine.text = "%.0f FPS · %s".format(measured, if (physId != null) "МОДУЛЬ $physId" else "ОСНОВНАЯ")
    }

    private fun refreshPill() {
        pillText.text = if (photoMode) "ФОТО · %.0f MP".format(photoSize.width * photoSize.height / 1e6)
        else "${cur.label} · ${cur.fps}  ▾"
    }

    private fun refreshMode() {
        modeVideo.setTextColor(if (!photoMode) RED else Color.WHITE)
        modePhoto.setTextColor(if (photoMode) RED else Color.WHITE)
        modeVideo.animate().alpha(if (photoMode) 0.55f else 1f).setDuration(150).start()
        modePhoto.animate().alpha(if (photoMode) 1f else 0.55f).setDuration(150).start()
        shutter.photo = photoMode
    }

    private fun activeLens(): Int {
        var idx = 0
        lenses.forEachIndexed { i, l ->
            if (physId != null) { if (l.phys == physId) idx = i }
            else if (l.phys == null && l.zoom <= zoom + 0.01f) idx = i
        }
        return idx
    }

    private fun refreshLenses() {
        val a = activeLens()
        for (i in 0 until lensRow.childCount) {
            val v = lensRow.getChildAt(i) as TextView
            val on = i == a
            v.setTextColor(if (on) RED else Color.WHITE)
            v.text = if (on) (if (lenses[i].phys == null) fmt(zoom) else lenses[i].label) + "x" else lenses[i].label
            val s = if (on) 1.15f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    private fun refreshDrawer() {
        resRow.removeAllViews()
        fpsRow.removeAllViews()
        supported.map { Triple(it.label, it.w, it.h) }.distinct().forEach { (name, w, h) ->
            val v = chip(name) { if (!recording) pickMode(w, h, cur.fps) }
            v.setTextColor(if (w == cur.w && h == cur.h) RED else Color.WHITE)
            resRow.addView(v)
        }
        supported.filter { it.w == cur.w && it.h == cur.h }.map { it.fps }.sorted().forEach { f ->
            val v = chip("$f") { if (!recording) pickMode(cur.w, cur.h, f) }
            v.setTextColor(if (f == cur.fps) RED else Color.WHITE)
            fpsRow.addView(v)
        }
        refreshToggles()
    }

    private fun refreshToggles() {
        tgRow.removeAllViews()
        fun add(name: String, on: Boolean, f: () -> Unit) {
            val v = chip(name) { f(); refreshToggles() }
            v.setTextColor(if (on) RED else Color.WHITE)
            tgRow.addView(v)
        }
        add("HDR", hdrOn) {
            if (!hdrAvail && !hdrOn) toast("HDR-режим камеры недоступен на этом телефоне")
            else {
                hdrOn = !hdrOn
                prefs.edit().putBoolean("hdr", hdrOn).apply()
                handler.post { applyRequest() }
            }
        }
        add("СЕТКА", gridOn) {
            gridOn = !gridOn
            prefs.edit().putBoolean("grid", gridOn).apply()
            grid.visibility = if (gridOn) View.VISIBLE else View.GONE
        }
        add("ЗНАК", wmOn) {
            wmOn = !wmOn
            prefs.edit().putBoolean("wm", wmOn).apply()
        }
    }

    private fun toggleDrawer(open: Boolean) {
        if (open == drawerOpen) return
        drawerOpen = open
        if (open) {
            refreshDrawer()
            drawer.alpha = 0f
            drawer.translationY = -dp(30).toFloat()
            drawer.visibility = View.VISIBLE
            drawer.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            drawer.animate().alpha(0f).translationY(-dp(30).toFloat()).setDuration(160)
                .withEndAction { drawer.visibility = View.GONE }.start()
        }
    }

    private fun pickMode(w: Int, h: Int, fps: Int) {
        val m = supported.filter { it.w == w && it.h == h }.minByOrNull { abs(it.fps - fps) } ?: return
        cur = m
        startPreview()
    }

    private fun switchPhoto(p: Boolean) {
        if (recording || p == photoMode) return
        photoMode = p
        toggleDrawer(false)
        startPreview()
    }

    private fun layoutPreview() {
        val sw = resources.displayMetrics.widthPixels
        val ratio = if (photoMode) photoSize.width.toFloat() / photoSize.height else cur.w.toFloat() / cur.h
        val lp = stage.layoutParams as FrameLayout.LayoutParams
        lp.height = (sw * ratio).toInt()
        stage.layoutParams = lp
    }

    // ---------- gallery button ----------

    private fun openLast() {
        val u = lastUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, if (lastVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) { toast("Не удалось открыть: ${e.message}") }
    }

    private fun loadLast() {
        Thread {
            try {
                var bestUri: Uri? = null
                var bestVideo = false
                var bestT = -1L
                val col = MediaStore.MediaColumns.DATE_ADDED
                for (vid in listOf(false, true)) {
                    val base = if (vid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val path = if (vid) "Movies/Cam8K%" else "Pictures/Cam8K%"
                    contentResolver.query(
                        base, arrayOf(MediaStore.MediaColumns._ID, col),
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf(path), "$col DESC"
                    )?.use { c ->
                        if (c.moveToFirst() && c.getLong(1) >= bestT) {
                            bestT = c.getLong(1)
                            bestUri = ContentUris.withAppendedId(base, c.getLong(0))
                            bestVideo = vid
                        }
                    }
                }
                val u = bestUri ?: return@Thread
                val bm = contentResolver.loadThumbnail(u, Size(256, 256), null)
                lastUri = u
                lastVideo = bestVideo
                runOnUiThread { thumb.setImageBitmap(bm) }
            } catch (_: Exception) {}
        }.start()
    }

    // ---------- tap to focus ----------

    private fun focusAt(nx: Float, ny: Float, px: Float, py: Float) {
        ring.animate().cancel()
        ring.translationX = px - dp(32)
        ring.translationY = py - dp(32)
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 1.5f
        ring.scaleY = 1.5f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        ui.postDelayed({ ring.animate().alpha(0f).setDuration(300).start() }, 900)
        handler.post { setFocusRegion(nx, ny) }
    }

    private fun setFocusRegion(nx: Float, ny: Float) {
        val c = chars ?: return
        if (sensorOrientation != 90 || (maxAf <= 0 && maxAe <= 0)) return
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val u = (0.5f + (nx - 0.5f) / zoom).coerceIn(0f, 1f)
        val v = (0.5f + (ny - 0.5f) / zoom).coerceIn(0f, 1f)
        val cx = a.left + (v * a.width()).toInt()
        val cy = a.top + ((1f - u) * a.height()).toInt()
        val half = (minOf(a.width(), a.height()) * 0.06f).toInt()
        val r = Rect(
            (cx - half).coerceAtLeast(a.left), (cy - half).coerceAtLeast(a.top),
            (cx + half).coerceAtMost(a.right - 1), (cy + half).coerceAtMost(a.bottom - 1)
        )
        focusRegion = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        applyRequest()
    }

    // ---------- zoom / lenses ----------

    private fun changeZoom(z: Float) {
        zoom = z.coerceIn(zoomMin, zoomMax)
        refreshLenses()
        if (!zoomPending) {
            zoomPending = true
            handler.post { zoomPending = false; applyRequest() }
        }
    }

    private fun animateZoom(to: Float) {
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(zoom, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { changeZoom(it.animatedValue as Float) }
            start()
        }
    }

    private fun onLens(l: Lens) {
        if (l.phys != physId) {
            if (recording) return
            physId = l.phys
            zoom = l.zoom
            startPreview()
        } else animateZoom(l.zoom.coerceIn(zoomMin, zoomMax))
    }

    private fun eqFocal(cc: CameraCharacteristics): Float {
        val f = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val w = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return 0f
        return f * 36f / w
    }

    private fun buildLenses(c: CameraCharacteristics, r: Range<Float>) {
        val phys = mutableListOf<Lens>()
        val mainEq = eqFocal(c)
        val mainF = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 1f
        for (pid in c.physicalCameraIds) {
            val pc = mgr.getCameraCharacteristics(pid)
            val e = eqFocal(pc)
            val ratio = if (e > 0f && mainEq > 0f) e / mainEq
            else (pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: mainF) / mainF
            if (abs(ratio - 1f) > 0.15f) phys += Lens(fmt(Math.round(ratio * 10) / 10f), 1f, pid, ratio)
        }
        val list = mutableListOf<Lens>()
        list += Lens("1", 1f, null, 1f)
        if (r.lower <= 0.65f) list += Lens("0.6", 0.6f, null, 0.6f)
        if (r.upper >= 2f && phys.none { abs(it.ratio - 2f) < 0.3f }) list += Lens("2", 2f, null, 2f)
        if (r.upper >= 3.2f && phys.none { it.ratio >= 2.5f }) list += Lens("3.2", 3.2f, null, 3.2f)
        list += phys
        lenses = list.sortedBy { it.ratio }
        lensRow.removeAllViews()
        lenses.forEach { l -> lensRow.addView(chip(l.label, 44) { onLens(l) }) }
        refreshLenses()
    }

    private fun showInfo() {
        val sb = StringBuilder()
        val id = camId()
        val c = mgr.getCameraCharacteristics(id)
        sb.appendLine("Камера $id, зум ${c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}")
        sb.appendLine("Режимы: " + supported.joinToString { "${it.label}/${it.fps}" })
        sb.appendLine("FPS-диапазоны: " + fpsRanges.joinToString())
        sb.appendLine("HDR: $hdrAvail, AF-зон: $maxAf, AE-зон: $maxAe")
        for (pid in c.physicalCameraIds) {
            val pc = mgr.getCameraCharacteristics(pid)
            val f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val sz = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            sb.appendLine("Модуль $pid: ${f}мм, сенсор $sz, экв %.1f".format(eqFocal(pc)))
        }
        sb.appendLine("Основная: экв %.1f".format(eqFocal(c)))
        sb.appendLine("Кнопки: " + lenses.joinToString { "${it.label}${if (it.phys != null) "(м${it.phys})" else ""}" })
        sb.appendLine("Измерено: %.1f fps".format(measured))
        AlertDialog.Builder(this).setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    // ---------- camera setup ----------

    private fun camId(): String = mgr.cameraIdList.first {
        mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            (if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK)
    }

    private fun encOk(w: Int, h: Int, f: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals("video/hevc", true) } &&
                ci.getCapabilitiesForType("video/hevc").videoCapabilities.areSizeAndRateSupported(w, h, f.toDouble())
        }
    } catch (e: Exception) { true }

    private fun buildModes(c: CameraCharacteristics, map: StreamConfigurationMap?) {
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        fpsRanges = ranges
        val tiers = listOf(Triple("8K", 7680, 4320), Triple("4K", 3840, 2160), Triple("1080", 1920, 1080), Triple("720", 1280, 720))
        val list = mutableListOf<Mode>()
        for ((name, w, h) in tiers) {
            if (sizes.none { it.width == w && it.height == h }) continue
            for (f in listOf(60, 30, 24)) {
                val inRange = ranges.any { it.lower <= f && f <= it.upper }
                if ((inRange || f == 60) && encOk(w, h, f)) list += Mode(name, w, h, f)
            }
        }
        if (list.isEmpty()) list += Mode("1080", 1920, 1080, 30)
        supported = list
        if (supported.none { it.w == cur.w && it.h == cur.h && it.fps == cur.fps })
            cur = supported.firstOrNull { it.w == 1920 && it.fps == 30 } ?: supported.last()
    }

    private fun previewBufferFor(map: StreamConfigurationMap?, ps: Size): Size {
        val ratio = ps.width.toFloat() / ps.height
        return map?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width <= 1920 && abs(it.width.toFloat() / it.height - ratio) < 0.02f }
            ?.maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
    }

    private fun prepare(id: String) {
        val c = mgr.getCameraCharacteristics(id)
        chars = c
        maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        hdrAvail = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?.contains(CaptureRequest.CONTROL_SCENE_MODE_HDR) == true
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        zoomMin = r.lower
        zoomMax = maxOf(zoomMin, minOf(r.upper, 20f))
        zoom = zoom.coerceIn(zoomMin, zoomMax)
        buildModes(c, map)
        photoSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
        buildLenses(c, r)
        layoutPreview()
        refreshPill()
        refreshMode()
        refreshInfo()
        if (drawerOpen) refreshDrawer()
        val bs = if (photoMode) previewBufferFor(map, photoSize) else Size(1920, 1080)
        tv.surfaceTexture?.setDefaultBufferSize(bs.width, bs.height)
    }    private fun startPreview() = runOnUiThread { startPreviewUi() }

    @SuppressLint("MissingPermission")
    private fun startPreviewUi() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !tv.isAvailable) return
        val g = ++gen
        val id = camId()
        prepare(id)
        val st = tv.surfaceTexture ?: return
        val mode = cur
        val photo = photoMode
        val pSize = photoSize
        tsStart = 0L
        lowSecs = 0
        focusRegion = null
        handler.post {
            if (g != gen) return@post
            closeAll()
            try {
                previewSurface = Surface(st)
                if (photo) {
                    val rd = ImageReader.newInstance(pSize.width, pSize.height, ImageFormat.JPEG, 2)
                    rd.setOnImageAvailableListener({ saveJpeg(it) }, handler)
                    reader = rd
                } else buildRecorder(mode)
                mgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        if (g != gen) { cam.close(); return }
                        device = cam
                        createSession(cam, g, photo)
                    }
                    override fun onDisconnected(cam: CameraDevice) { cam.close() }
                    override fun onError(cam: CameraDevice, e: Int) { cam.close(); toast("Ошибка камеры $e") }
                }, handler)
            } catch (e: Exception) {
                toast("Режим недоступен: ${e.message}")
                if (!photo) fallback()?.let { cur = it; startPreview() }
            }
        }
    }

    private fun fallback(): Mode? {
        val i = supported.indexOfFirst { it.w == cur.w && it.h == cur.h && it.fps == cur.fps }
        return supported.getOrNull(i + 1)
    }

    private fun buildRecorder(m: Mode) {
        file = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "cam8k_${System.currentTimeMillis()}.mp4")
        val mr = MediaRecorder(this)
        recorder = mr
        mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setVideoSize(m.w, m.h)
        mr.setVideoFrameRate(m.fps)
        mr.setVideoEncodingBitRate(minOf(100_000_000L, m.w.toLong() * m.h * m.fps * 3 / 10).toInt())
        mr.setAudioEncodingBitRate(192_000)
        mr.setAudioSamplingRate(48_000)
        mr.setOrientationHint(sensorOrientation)
        mr.setOutputFile(file!!.absolutePath)
        mr.prepare()
        recSurface = mr.surface
    }

    private fun createSession(cam: CameraDevice, g: Int, photo: Boolean) {
        val p = OutputConfiguration(previewSurface!!)
        val second = OutputConfiguration(if (photo) reader!!.surface else recSurface!!)
        physId?.let { p.setPhysicalCameraId(it); second.setPhysicalCameraId(it) }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(p, second), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (g != gen) { s.close(); return }
                    session = s
                    applyRequest()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (g != gen) return
                    if (physId != null) {
                        physId = null; zoom = 1f
                        toast("Этот модуль прошивка не отдаёт, включаю основную камеру")
                        startPreview()
                        return
                    }
                    val next = if (photo) null else fallback()
                    if (next != null) {
                        toast("${cur.label}·${cur.fps} не поддерживается, перехожу на ${next.label}·${next.fps}")
                        cur = next
                        startPreview()
                    } else toast("Не удалось запустить камеру")
                }
            })
        try { cam.createCaptureSession(cfg) } catch (e: Exception) { toast("Сессия: ${e.message}") }
    }

    private fun ois(b: CaptureRequest.Builder) {
        val ok = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        if (ok) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    private fun hq(b: CaptureRequest.Builder) {
        val c = chars ?: return
        fun ok(key: CameraCharacteristics.Key<IntArray>, v: Int) = c.get(key)?.contains(v) == true
        if (ok(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (ok(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        if (ok(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        if (ok(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
    }

    private fun scene(b: CaptureRequest.Builder) {
        if (photoMode && hdrOn && hdrAvail) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        }
    }

    private fun regions(b: CaptureRequest.Builder) {
        val r = focusRegion ?: return
        if (maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
        if (maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
    }

    private fun applyRequest() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (recording) b.addTarget(recSurface!!)
            if (!photoMode) {
                val f = cur.fps
                val range = fpsRanges.firstOrNull { it.lower == f && it.upper == f }
                    ?: fpsRanges.filter { it.lower <= f && f <= it.upper }.minByOrNull { it.upper - it.lower }
                    ?: Range(f, f)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (recording || !photoMode) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            scene(b)
            ois(b)
            regions(b)
            s.setRepeatingRequest(b.build(), cb, handler)
        } catch (e: Exception) { toast("Запрос: ${e.message}") }
    }

    // ---------- photo ----------

    private fun takePhoto() {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            val rd = reader ?: return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(rd.surface)
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                scene(b)
                hq(b)
                ois(b)
                regions(b)
                s.capture(b.build(), null, handler)
                runOnUiThread {
                    stage.animate().alpha(0.3f).setDuration(60).withEndAction {
                        stage.animate().alpha(1f).setDuration(140).start()
                    }.start()
                }
            } catch (e: Exception) { toast("Фото: ${e.message}") }
        }
    }

    private fun watermark(src: ByteArray): ByteArray {
        try {
            val deg = when (ExifInterface(ByteArrayInputStream(src)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            var bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return src
            if (deg != 0) {
                val m = Matrix().apply { postRotate(deg.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(out)
            val ts = out.width * 0.022f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = ts
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.08f
                setShadowLayer(ts * 0.15f, 0f, 0f, Color.argb(160, 0, 0, 0))
            }
            val text = "Shot on " + Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL + "  ·  Cam8K"
            val margin = out.width * 0.035f
            val y = out.height - margin
            val r = ts * 0.32f
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
            cv.drawCircle(margin + r, y - ts * 0.33f, r, dot)
            cv.drawText(text, margin + r * 3.2f, y, p)
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 97, bos)
            return bos.toByteArray()
        } catch (t: Throwable) {
            return src
        }
    }

    private fun saveJpeg(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            arr
        } finally { img.close() }
        val wm = wmOn
        Thread {
            try {
                val data = if (wm) watermark(bytes) else bytes
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "cam8k_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cam8K")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(data) }
                toast("Фото сохранено: Pictures/Cam8K")
                loadLast()
            } catch (e: Exception) {
                toast("Ошибка сохранения фото: ${e.message}")
            }
        }.start()
    }

    // ---------- video ----------

    private fun toggleRecord() {
        handler.post {
            if (!recording) {
                if (session == null) { toast("Камера ещё не готова"); return@post }
                try {
                    recording = true
                    applyRequest()
                    recorder?.start()
                    t0 = SystemClock.elapsedRealtime()
                    runOnUiThread {
                        shutter.animateRecording(true)
                        timer.alpha = 0f
                        timer.visibility = View.VISIBLE
                        timer.animate().alpha(1f).setDuration(200).start()
                        toggleDrawer(false)
                        ui.post(tick)
                    }
                } catch (e: Exception) {
                    recording = false
                    toast("Старт записи: ${e.message}")
                    finishRecording(true)
                }
            } else finishRecording(true)
        }
    }

    private fun finishRecording(restart: Boolean) {
        val was = recording
        val f = file
        recording = false
        if (was) {
            file = null
            try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
            try { recorder?.stop() } catch (_: Exception) {}
        }
        closeAll()
        if (was && f != null) Thread { saveToGallery(f) }.start()
        runOnUiThread { shutter.animateRecording(false); timer.visibility = View.GONE }
        if (restart) startPreview()
    }

    private fun closeAll() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null; device = null; recorder = null; reader = null
        previewSurface = null; recSurface = null
        file?.delete()
        file = null
    }

    private fun saveToGallery(f: File) {
        try {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cam8K")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            f.delete()
            toast("Видео сохранено: Movies/Cam8K")
            loadLast()
        } catch (e: Exception) { toast("Ошибка сохранения: ${e.message}") }
    }

    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
                      }
