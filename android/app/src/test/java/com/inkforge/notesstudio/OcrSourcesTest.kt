package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test

class OcrSourcesTest {
    @Test fun mixedRasterIncludesPdfAndImageWhileInkAndNativeTextStaySeparate() {
        val ink=json("id" to "ink","type" to "stroke")
        val image=json("id" to "image","type" to "image")
        val text=json("id" to "text","type" to "text","text" to "1")
        val page=NotePage("p",json("pdfSource" to "asset:p.pdf","width" to 1000,"height" to 1414),
            mutableListOf(ink,image,text))
        val before=page.json().toString()
        assertTrue(OcrSources.hasRasterSource(page))
        val raster=OcrSources.rasterOnly(page)
        assertEquals(listOf("image"),raster.objects.map { it.getString("id") })
        assertEquals("asset:p.pdf",raster.meta.optString("pdfSource"))
        assertEquals(before,page.json().toString())
    }
    @Test fun rasterPixelAndHeightBudgetAppliesToPortraitAndLandscapePages() {
        for ((w,h) in listOf(1000 to 1414,30000 to 32,32 to 30000,30000 to 30000)) {
            val page=NotePage("p",json("width" to w,"height" to h),mutableListOf())
            val width=OcrSources.rasterWidth(page)
            val height=kotlin.math.round(width*page.height/page.width).toInt().coerceIn(1,5000)
            assertTrue(width.toLong()*height <= 4_000_000)
            assertTrue(height <= 5000)
        }
    }
}
