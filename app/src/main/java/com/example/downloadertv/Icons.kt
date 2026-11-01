package com.example.downloadertv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

object IconPaths {
    const val DOWNLOAD = "M12,4V15 M7,11L12,16L17,11 M5,20H19"
    const val GLOBE = "M12,3A9,9 0 1,0 12,21A9,9 0 1,0 12,3Z M12,3C8.5,7 8.5,17 12,21 M12,3C15.5,7 15.5,17 12,21 M3,12H21"
    const val USB = "M8,10H16V21H8Z M10,3V10 M14,3V10 M10,3H14 M12,13V18"
    const val SEND = "M9,5A3,3 0 1,0 9,11A3,3 0 1,0 9,5Z M3,20C3,16 6,14 9,14C12,14 15,16 15,20 M17,7A2.2,2.2 0 1,0 17,11.4A2.2,2.2 0 1,0 17,7Z M16.5,14C19,14 21,16 21,19"
    const val NETWORK = "M12,3A2.5,2.5 0 1,0 12,8A2.5,2.5 0 1,0 12,3Z M5,16.5A2.5,2.5 0 1,0 5,21.5A2.5,2.5 0 1,0 5,16.5Z M12,16.5A2.5,2.5 0 1,0 12,21.5A2.5,2.5 0 1,0 12,16.5Z M19,16.5A2.5,2.5 0 1,0 19,21.5A2.5,2.5 0 1,0 19,16.5Z M12,8V16.5 M5,16.5V13H19V16.5"
    const val APPS = "M4,4H10V10H4Z M14,4H20V10H14Z M4,14H10V20H4Z M14,14H20V20H14Z"
    const val ANDROID = "M6,10A6,6 0 0,1 18,10Z M6,12V19H18V12 M8.5,5.5L7,3.5 M15.5,5.5L17,3.5 M12,13.5L12.8,15.3L14.7,15.5L13.3,16.8L13.7,18.7L12,17.7L10.3,18.7L10.7,16.8L9.3,15.5L11.2,15.3Z"
    const val SETTINGS = "M12,9A3,3 0 1,0 12,15A3,3 0 1,0 12,9Z M12,4.5A7.5,7.5 0 1,0 12,19.5A7.5,7.5 0 1,0 12,4.5Z M12,2V4.5 M12,19.5V22 M2,12H4.5 M19.5,12H22 M5,5L6.8,6.8 M17.2,17.2L19,19 M5,19L6.8,17.2 M17.2,6.8L19,5"
    const val BLUETOOTH = "M7,7L17,17L12,22V2L17,7L7,17"
    const val WIFI = "M2,9C8,4 16,4 22,9 M5,12.5C9,9.5 15,9.5 19,12.5 M8.5,16C10.5,14.5 13.5,14.5 15.5,16 M12,19.5H12.01"
    const val CHECK = "M5,12L10,17L19,7"
    const val FOLDER = "M3,6H10L12,8H21V19H3Z"
    const val FILE = "M6,3H14L18,7V21H6Z M14,3V7H18"
    const val UP = "M12,19V5 M6,11L12,5L18,11"
}

/** Draws a 24x24 stroke-icon from SVG path data, scaled to its bounds. */
class IconDrawable(pathData: String, color: Int = Color.WHITE) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        val save = canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
