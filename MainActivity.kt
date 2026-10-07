package com.example.cam8k

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs

private val RED = Color.rgb(217, 38, 44)

private fun glass(r: Float, a: Int): GradientDrawable = GradientDrawable().apply {
    setColor(Color.argb(a, 14, 14, 14))
    cornerRadius = r
    setStroke(2, Color.argb(70, 255, 255, 255))
}

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
        p.strokeWidth = r * 0.06f
        p.color = Color.WHITE
        cv.drawCircle(cx, cy, r - p.strokeWidth, p)
        p.style = Paint.Style.FILL
        if (photo) {
            p.color = Color.WHITE
            cv.drawCircle(cx, cy, r * 0.72f, p)
        } else {
            p.color = RED
            val half = r * (0.70f - 0.32f * morph)
            val corner = half * (1f - 0.75f * morph)
            cv.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, p)
        }
    }
}

class MainActivity : Activity() {

    private class Lens(val label: String, val zoom: Float, val phys: String?)
    private class Mode(val label: String, val w: Int, val h: Int, val fps: Int)

    private val thread = HandlerThread("cam").also { it.start() }
    private val handler by lazy { Handler(thread.looper) }
    private val executor = Executor { handler.post(it) }
    private val ui = Handler(Looper.getMainLooper())
    private val mgr by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

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

    private lateinit var tv: TextureView
    private lateinit var shutter: ShutterView
    private lateinit var timer: TextView
    private lateinit var pill: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var resRow: LinearLayout
    private lateinit var fpsRow: LinearLayout
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun chip(t: String, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
        setTextColor(Color.WHITE)
        setPadding(dp(13), dp(7), dp(13), dp(7))
        setOnClickListener { click() }
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t
        textSize = 10f
        letterSpacing = 0.18f
        setTextColor(Color.argb(150, 255, 255, 255))
        setPadding(dp(6), dp(6), 0, dp(2))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val sw = resources.displayMetrics.widthPixels
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        tv = TextureView(this)
        root.addView(tv, FrameLayout.LayoutParams(sw, sw * 16 / 9, Gravity.TOP).apply { topMargin = dp(56) })
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startPreview() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (physId == null) changeZoom(zoom * d.scaleFactor)
                return true
            }
        })
        tv.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !scale.isInProgress && drawerOpen) toggleDrawer(false)
            true
        }

        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(235, 0, 0, 0))
        )
        root.addView(scrim, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))

        pill = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = glass(dp(18).toFloat(), 170)
            setOnClickListener { toggleDrawer(!drawerOpen) }
        }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })

        timer = TextView(this).apply {
            setTextColor(RED)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        root.addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(16); marginStart = dp(18) })

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(22).toFloat(), 225)
            setPadding(dp(12), dp(8), dp(12), dp(12))
            visibility = View.GONE
        }
        resRow = LinearLayout(this)
        fpsRow = LinearLayout(this)
        drawer.addView(caption("РАЗРЕШЕНИЕ"))
        drawer.addView(resRow)
        drawer.addView(caption("КАДРОВ В СЕКУНДУ"))
        drawer.addView(fpsRow)
        root.addView(drawer, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(52); marginStart = dp(12); marginEnd = dp(12) })

        lensRow = LinearLayout(this).apply {
            background = glass(dp(22).toFloat(), 150)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        root.addView(lensRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(168) })

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

    override fun onResume() { super.onResume(); if (tv.isAvailable) startPreview() }

    override fun onPause() {
        super.onPause()
        gen++
        handler.post { if (recording) finishRecording(false) else closeAll() }
    }

    // ---------- UI helpers ----------

    private fun fmt(z: Float) = if (abs(z - Math.round(z)) < 0.05f) "${Math.round(z)}x" else "%.1f".format(z)

    private fun refreshPill() {
        pill.text = if (photoMode) "ФОТО · %.0f MP".format(photoSize.width * photoSize.height / 1e6)
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
        lenses.forEachIndexed { i, l -> if (l.phys == physId && l.zoom <= zoom + 0.01f) idx = i }
        return idx
    }

    private fun refreshLenses() {
        val a = activeLens()
        for (i in 0 until lensRow.childCount) {
            val v = lensRow.getChildAt(i) as TextView
            val on = i == a
            v.setTextColor(if (on) RED else Color.WHITE)
            v.text = if (on && lenses[i].phys == null) fmt(zoom) else lenses[i].label
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
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        lp.height = (sw * ratio).toInt()
        tv.layoutParams = lp
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

    private fun buildLenses(c: CameraCharacteristics, r: Range<Float>) {
        val list = mutableListOf<Lens>()
        for (z in listOf(0.6f, 1f, 2f, 3.2f))
            if (z >= r.lower - 0.01f && z <= r.upper) list += Lens(fmt(z), z, null)
        if (list.none { it.zoom == 1f }) list += Lens("1x", 1f, null)
        if (r.lower >= 0.99f && c.physicalCameraIds.isNotEmpty()) {
            val main = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 1f
            for (pid in c.physicalCameraIds) {
                val pf = mgr.getCameraCharacteristics(pid)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val ratio = pf / main
                if (abs(ratio - 1f) > 0.15f) list += Lens("%.1f".format(ratio), 1f, pid)
            }
        }
        lenses = list.sortedBy { if (it.phys == null) it.zoom else it.label.toFloatOrNull() ?: 1f }
        lensRow.removeAllViews()
        lenses.forEach { l -> lensRow.addView(chip(l.label) { onLens(l) }) }
    }

    // ---------- camera setup ----------

    private fun camId(): String = mgr.cameraIdList.first {
        mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            (if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK)
    }

    private fun buildModes(c: CameraCharacteristics, map: StreamConfigurationMap?) {
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        fpsRanges = ranges
        val tiers = listOf(Triple("8K", 7680, 4320), Triple("4K", 3840, 2160), Triple("1080", 1920, 1080), Triple("720", 1280, 720))
        val list = mutableListOf<Mode>()
        for ((name, w, h) in tiers) {
            if (sizes.none { it.width == w && it.height == h }) continue
            for (f in listOf(60, 30, 24))
                if (ranges.any { it.lower <= f && f <= it.upper }) list += Mode(name, w, h, f)
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
        refreshLenses()
        if (drawerOpen) refreshDrawer()
        val bs = if (photoMode) previewBufferFor(map, photoSize) else Size(1920, 1080)
        tv.surfaceTexture?.setDefaultBufferSize(bs.width, bs.height)
    }

    private fun startPreview() = runOnUiThread { startPreviewUi() }

    @SuppressLint("MissingPermission")
    private fun startPreviewUi() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !tv.isAvailable) return
        val g = ++gen
        val id = camId()
        prepare(id)
        val st = 
