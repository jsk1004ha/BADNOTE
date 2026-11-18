package com.inkforge.notesstudio

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** Applied to PDF tiles only; cache pixels, annotation layers and exports stay original. */
internal object PdfDisplayColors {
    const val PAPER = 0xff1a1a1a.toInt()
    private val dark = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -.82f, 0f, 0f, 0f, 235f,
            0f, -.82f, 0f, 0f, 235f,
            0f, 0f, -.82f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f)))
    }
    fun paint(enabled: Boolean): Paint? = if (enabled) dark else null
}
