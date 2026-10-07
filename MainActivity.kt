package com.example.cam8k

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

private val RED = Color.rgb(217, 38, 44)

private fun sheen(r: Float) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(24, 255, 255, 255))
).apply { cornerRadius = r; setStroke(2, Color.argb(150, 255, 255, 255)) }

class GlassPanel(c: Context, private val src: TextureView, private val rad: Float) : FrameLayout(c) {
    private val back = ImageView(c)
    private var bmp: Bitmap? = null

    init {
        back.scaleType = ImageView.ScaleType.FIT_XY
        back.setColorFilter(Color.argb(95, 0, 0, 0))
        back.setRenderEffect(RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP))
        addView(back, FrameLayout.LayoutParams(-1, -1))
        val top = View(c)
        top.background = sheen(rad)
        addView(top, FrameLayout.LayoutParams(-1, -1))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad) }
        }
    }

    fun refresh() {
        if (!isShown || width == 0 || height == 0 || !src.isAvailable || src.width == 0) return
        val loc = IntArray(2)
        val tl = IntArray(2)
        getLocationInWindow(loc)
        src.getLocationInWindow(tl)
        val s = 0.125f
        val sw = (src.width * s).toInt().coerceAtLeast(2)
        val sh = (src.height * s).toInt().coerceAtLeast(2)
        val b = bmp?.takeIf { it.width == sw && it.height == sh }
            ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { bmp = it }
        src.getBitmap(b)
        val x = ((loc[0] - tl[0]) * s).toInt().coerceIn(0, sw - 1)
        val y = ((loc[1] - tl[1]) * s).toInt().coerceIn(0, sh - 1)
        val w = (width * s).toInt().coerceIn(1, sw - x)
        val h = (height * s).toInt().coerceIn(1, sh - y)
        back.setImageBitmap(Bitmap.createBitmap(b, x, y, w, h))
    }
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

class MainActivity : Activity() {

    private class Lens(val label: String, val zoom: Float, val phys: String?, val ratio: Float)
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
    @Volatile private var measured = 0.0
    private var tsStart = 0L
    private var frames = 0

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

    private lateinit var tv: TextureView
    private lateinit var shutter: ShutterView
    private lateinit var timer: TextView
    private lateinit var pillText: TextView
    private lateinit var infoLine: TextView
    private lateinit var pill: GlassPanel
    private lateinit var drawer: GlassPanel
    private lateinit var lensPanel: GlassPanel
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

    private val glassTick = object : Runnable {
        override fun run() {
            panels.forEach { it.refresh() }
            ui.postDelayed(this, 130)
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
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(caption("РАЗРЕШЕНИЕ"))
            addView(resRow)
            addView(caption("КАДРОВ В СЕКУНДУ"))
            addView(fpsRow)
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
        for (pid in c.physicalCameraIds) {
            val pc = mgr.getCameraCharacteristics(pid)
            val f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val sz = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            sb.appendLine("Модуль $pid: ${f}мм, сенсор $sz, экв %.1f".format(eqFocal(pc)))
        }
        sb.appendLine("Основная: экв %.1f, ${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()}мм".format(eqFocal(c)))
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
                s.capture(b.build(), null, handler)
                runOnUiThread {
                    tv.animate().alpha(0.3f).setDuration(60).withEndAction {
                        tv.animate().alpha(1f).setDuration(140).start()
                    }.start()
                }
            } catch (e: Exception) { toast("Фото: ${e.message}") }
        }
    }

    private fun saveJpeg(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "cam8k_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cam8K")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            toast("Фото сохранено: Pictures/Cam8K")
        } catch (e: Exception) {
            toast("Ошибка сохранения фото: ${e.message}")
        } finally { img.close() }
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
        } catch (e: Exception) { toast("Ошибка сохранения: ${e.message}") }
    }

    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
                      }
