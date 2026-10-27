package com.inkforge.notesstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Shared shaping/wrapping for the editor, saved Canvas content, thumbnails and PDF export. */
internal object TextBoxLayout {
    fun fontStyle(bold:Boolean,italic:Boolean):Int = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    class ToggleSpan(val kind:String,val enabled:Boolean):MetricAffectingSpan() {
        override fun updateDrawState(paint:TextPaint)=apply(paint)
        override fun updateMeasureState(paint:TextPaint)=apply(paint)
        private fun apply(paint:TextPaint) { when(kind) {
            "bold","italic" -> { val current=paint.typeface
                val bold=if(kind=="bold")enabled else current?.isBold==true
                val italic=if(kind=="italic")enabled else current?.isItalic==true
                paint.typeface=Typeface.create(current,fontStyle(bold,italic)) }
            "underline" -> paint.isUnderlineText=enabled
            "strike" -> paint.isStrikeThruText=enabled
        } }
    }
    val styleKeys = listOf("fontSize", "fontFamily", "fontWeight", "italic", "underline", "strike",
        "color", "alignment", "lineSpacing", "textBackground", "textBorder", "textPadding")
    fun color(value: String, fallback: Int = Color.BLACK) = runCatching { Color.parseColor(value) }.getOrDefault(fallback)
    fun typeface(obj: JSONObject): Typeface = Typeface.create(obj.optString("fontFamily", "sans-serif"),
        fontStyle(obj.optInt("fontWeight", 400) >= 600,obj.optBoolean("italic")))
    fun paint(obj: JSONObject, scale: Float = 1f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = obj.f("fontSize", 28f).coerceIn(8f, 180f) * scale
        color = color(obj.optString("color", "#172033")); typeface = typeface(obj)
        isUnderlineText = obj.optBoolean("underline"); isStrikeThruText = obj.optBoolean("strike")
    }
    fun styled(obj: JSONObject, scale: Float = 1f): SpannableStringBuilder {
        val text = SpannableStringBuilder(obj.optString("text"))
        val spans = obj.optJSONArray("textRuns") ?: return text
        for (i in 0 until spans.length()) {
            val run = spans.optJSONObject(i) ?: continue
            val start = run.optInt("start").coerceIn(0, text.length)
            val end = run.optInt("end").coerceIn(start, text.length)
            if (start == end) continue
            val span=span(run,obj)?:continue
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }
    fun span(run:JSONObject,obj:JSONObject):CharacterStyle? = when (run.optString("kind")) {
                "bold","italic","underline","strike" -> ToggleSpan(run.optString("kind"),run.optBoolean("value",true))
                "color" -> ForegroundColorSpan(color(run.optString("value")))
                "highlight" -> BackgroundColorSpan(color(run.optString("value")))
                "font" -> TypefaceSpan(run.optString("value", "sans-serif"))
                "size" -> RelativeSizeSpan(run.f("value",28f)/obj.f("fontSize",28f).coerceAtLeast(1f))
                else -> null
    }
    fun runs(text: Spanned, scale: Float, baseSize:Float): JSONArray = JSONArray().apply {
        for (span in text.getSpans(0, text.length, CharacterStyle::class.java)) {
            if (text.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0) continue
            val kind: String; val value: Any?
            when (span) {
                is ToggleSpan -> { kind=span.kind;value=span.enabled }
                is StyleSpan -> { kind = if (span.style == Typeface.ITALIC) "italic" else "bold"; value = null }
                is UnderlineSpan -> { kind = "underline"; value = null }
                is StrikethroughSpan -> { kind = "strike"; value = null }
                is ForegroundColorSpan -> { kind = "color"; value = String.format("#%08X", span.foregroundColor) }
                is BackgroundColorSpan -> { kind = "highlight"; value = String.format("#%08X", span.backgroundColor) }
                is TypefaceSpan -> { kind = "font"; value = span.family ?: "sans-serif" }
                is AbsoluteSizeSpan -> { kind = "size"; value = span.size / scale }
                is RelativeSizeSpan -> { kind = "size"; value = span.sizeChange * baseSize }
                else -> continue
            }
            val start = text.getSpanStart(span).coerceIn(0, text.length)
            val end = text.getSpanEnd(span).coerceIn(start, text.length)
            if (end > start) put(json("start" to start, "end" to end, "kind" to kind).apply {
                if (value != null) put("value", value)
            })
        }
    }
    fun alignment(obj: JSONObject) = when (obj.optString("alignment", "left")) {
        "center" -> Layout.Alignment.ALIGN_CENTER
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
    fun layout(obj: JSONObject): StaticLayout {
        val text = styled(obj)
        val width = ceil(obj.f("w", 360f) - obj.f("textPadding") * 2).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint(obj), width)
            .setAlignment(alignment(obj)).setIncludePad(true)
            .setLineSpacing(0f, obj.f("lineSpacing", 1.2f).coerceIn(.8f, 3f))
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if(Build.VERSION.SDK_INT>=28)setUseLineSpacingFromFallbacks(true) }.build()
    }
    fun height(obj: JSONObject): Float = layout(obj).height + obj.f("textPadding") * 2
    fun draw(canvas: Canvas, obj: JSONObject) {
        val x = obj.f("x"); val y = obj.f("y"); val w = obj.f("w", 360f); val h = obj.f("h", 40f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG); val saved = canvas.save()
        try {
            canvas.translate(x, y)
            if (obj.has("textBackground")) {
                paint.color = color(obj.optString("textBackground"), Color.TRANSPARENT)
                canvas.drawRoundRect(0f, 0f, w, h, 6f, 6f, paint)
            }
            if (obj.optBoolean("textBorder")) {
                paint.color = color(obj.optString("color", "#172033")); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
                canvas.drawRoundRect(0f, 0f, w, h, 6f, 6f, paint)
            }
            val padding = obj.f("textPadding"); canvas.translate(padding, padding); layout(obj).draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}
