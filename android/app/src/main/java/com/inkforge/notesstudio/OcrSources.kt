package com.inkforge.notesstudio

import kotlin.math.min
import kotlin.math.sqrt

internal object OcrSources {
    fun hasRasterSource(page: NotePage) = page.meta.has("backgroundImage") || page.meta.has("pdfSource") ||
        page.objects.any { it.optString("type") == "image" }

    /** Borrow worker-owned sources without drawing the same digital ink in a second engine. */
    fun rasterOnly(page: NotePage) = page.copy(objects = page.objects.filter { it.optString("type") == "image" }.toMutableList())

    fun rasterWidth(page: NotePage, maxPixels: Int = 4_000_000): Int {
        require(maxPixels > 0)
        val ratio = page.height.toDouble()/page.width
        val width = min(1400.0,min(sqrt(maxPixels/ratio),5000.0/ratio)).toInt().coerceAtLeast(1)
        require(width.toLong() * (width*ratio).toLong().coerceAtLeast(1) <= maxPixels) { "rasterBudgetExceeded" }
        return width
    }
}
