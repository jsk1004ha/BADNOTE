package com.inkforge.notesstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Replays the original browser's 36 input cases through the product Canvas brush. */
internal object BrushRasterChecks {
    fun run(context: Context, transparent: Boolean = false) {
        val root = File(context.filesDir, "brush-raster")
        val manifest = JSONObject(File(root, "manifest.json").readText(Charsets.UTF_8))
        val raster = manifest.getJSONObject("raster")
        require(raster.getInt("width") == 1024 && raster.getInt("height") == 1024)
        val checker = raster.getJSONArray("backgroundColors")
        val first = Color.parseColor(checker.getString(0))
        val second = Color.parseColor(checker.getString(1))
        val cell = raster.getInt("checkerCell")
        val offset = raster.getJSONArray("translate")
        val cases = manifest.getJSONArray("cases")
        require(cases.length() == 36) { "Expected all 36 browser brush cases" }
        val native = File(root, if (transparent) "transparent" else "native").apply { mkdirs() }
        val brush = LegacyCanvasBrush()
        val paint = Paint().apply { style = Paint.Style.FILL }
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            require(item.getInt("index") == index && item.getString("file") == "case-%03d.png".format(index))
            val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                if (!transparent) for (y in 0 until 1024 step cell) for (x in 0 until 1024 step cell) {
                    paint.color = if ((x / cell + y / cell) % 2 == 0) first else second
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
                }
                canvas.translate(offset.getDouble(0).toFloat(), offset.getDouble(1).toFloat())
                val scale = item.getDouble("scale")
                canvas.scale(scale.toFloat(), scale.toFloat())
                brush.draw(canvas, item.getJSONObject("stroke"), scale)
                FileOutputStream(File(native, "case-%03d.png".format(index))).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.fd.sync()
                }
            } finally { bitmap.recycle() }
        }
    }
}
