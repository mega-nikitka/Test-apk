package com.example.cam8k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

class GlassPanel(c: Context, private val src: TextureView, private val rad: Float) : FrameLayout(c) {
    private val back = ImageView(c)
    private val top = View(c)

    init {
        back.scaleType = ImageView.ScaleType.FIT_XY
        back.setColorFilter(Color.argb(95, 0, 0, 0))
        back.setRenderEffect(RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP))
        top.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(24, 255, 255, 255))
        ).apply { cornerRadius = rad; setStroke(2, Color.argb(150, 255, 255, 255)) }
        addView(back, FrameLayout.LayoutParams(-1, -1))
        addView(top, FrameLayout.LayoutParams(-1, -1))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad) }
        }
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val content = getChildAt(childCount - 1)
        measureChild(content, ws, hs)
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else content.measuredWidth
        val h = if (MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY) MeasureSpec.getSize(hs) else content.measuredHeight
        setMeasuredDimension(w, h)
        val ew = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val eh = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ew, eh)
    }

    fun sample(b: Bitmap, s: Float, tl: IntArray) {
        if (!isShown || width == 0 || height == 0) return
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val x = ((loc[0] - tl[0]) * s).toInt().coerceIn(0, b.width - 1)
        val y = ((loc[1] - tl[1]) * s).toInt().coerceIn(0, b.height - 1)
        val w = (width * s).toInt().coerceIn(1, b.width - x)
        val h = (height * s).toInt().coerceIn(1, b.height - y)
        back.setImageBitmap(Bitmap.createBitmap(b, x, y, w, h))
    }
}

private var sharedBmp: Bitmap? = null

fun refreshGlass(src: TextureView, panels: List<GlassPanel>) {
    if (!src.isAvailable || src.width == 0 || panels.none { it.isShown }) return
    val s = 0.0625f
    val sw = (src.width * s).toInt().coerceAtLeast(2)
    val sh = (src.height * s).toInt().coerceAtLeast(2)
    val b = sharedBmp?.takeIf { it.width == sw && it.height == sh }
        ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { sharedBmp = it }
    src.getBitmap(b)
    val tl = IntArray(2)
    src.getLocationInWindow(tl)
    panels.forEach { it.sample(b, s, tl) }
}
