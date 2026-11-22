package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderNode
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sin

/** Deterministically reproduce HWUI reclaiming a retained node's display list. */
internal object InkDisplayListRecoveryChecks {
    private fun field(owner: Any, name: String): Any =
        checkNotNull(owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner))

    private fun discard(nodes: List<RenderNode>) {
        check(nodes.isNotEmpty()) { "Fixture did not exercise retained hardware commands" }
        nodes.forEach { it.discardDisplayList(); check(!it.hasDisplayList()) }
    }

    private fun bodyNodes(renderer: NoteRenderer): List<RenderNode> {
        val paths = field(field(renderer, "legacyBrush"), "paths")
        val passes = field(field(paths, "bodies"), "passes") as List<*>
        return passes.flatMap { (field(checkNotNull(it), "nodes") as List<*>).filterIsInstance<RenderNode>() }
    }

    private fun commandNodes(view: InkCanvasView): List<RenderNode> =
        (field(view, "pictures") as Map<*, *>).values.flatMap { cache ->
            (field(checkNotNull(cache), "entries") as Map<*, *>).values.flatMap { entry ->
                (field(field(checkNotNull(entry), "layer"), "commands") as List<*>).mapNotNull {
                    val command = checkNotNull(it)
                    val display = command.javaClass.getDeclaredField("display").apply { isAccessible = true }.get(command)
                    display?.let { field(it, "node") as RenderNode }
                }
            }
        }

    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle) {
        val failures = ArrayList<String>()
        var comparisons = 0
        fun compare(expected: Bitmap, actual: Bitmap, label: String) {
            try { if (!actual.sameAs(expected)) failures.add(label); comparisons++ }
            finally { actual.recycle() }
        }
        val repository = activity.repository
        for (strokeCount in listOf(1024, 6144)) {
        val document = repository.create("display-list-recovery-check", "root", "blank")
        val page = requireNotNull(repository.page(repository.pageIds(document.id).single()))
        repeat(strokeCount) { index ->
            val points = JSONArray()
            val columns = if (strokeCount == 1024) 16 else 64
            repeat(32) { point -> points.put(InkPoint(20f + (index % columns) * (960f / columns) + point * (20f / columns),
                20f + (index / columns) * (1360f / (strokeCount / columns)) + sin(point * .4).toFloat() * 4f).json()) }
            page.objects.add(json("id" to "retained-$index", "type" to "stroke",
                "brush" to "ballpoint", "width" to 3.5, "color" to "#172033", "points" to points))
        }
        lateinit var view: InkCanvasView
        var viewCreated = false
        try {
            ui {
                view = InkCanvasView(activity, repository)
                viewCreated = true
                view.layout(0, 0, 768, 1024)
                view.open(listOf(page.id), listOf(page.meta)); view.replacePage(page)
            }
            PenPresentationChecks.HardwareRasterOracle(768, 1024).use { hardware ->
                fun capture(label: String) = hardware.capture(label) { canvas -> ui {
                    canvas.drawColor(Color.MAGENTA, BlendMode.SRC); view.drawRetainedScene(canvas)
                } }
                val initial = capture("retained/initial")
                try {
                    val expected = hardware.capture("retained/fresh-reference/$strokeCount") { canvas -> ui {
                        canvas.drawColor(0xfff7f7f8.toInt())
                        val origin = view.screenPoint(0f, 0f)
                        val saved = canvas.save()
                        canvas.translate(origin.x, origin.y); canvas.scale(view.scale, view.scale)
                        canvas.drawRect(0f, 0f, page.width, page.height, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                        canvas.clipRect(0f, 0f, page.width, page.height)
                        val renderer = NoteRenderer(); renderer.template(canvas, page)
                        page.objects.forEach { renderer.draw(canvas, it, view.scale.toDouble()) }
                        canvas.restoreToCount(saved)
                    } }
                    compare(initial, expected, "fresh reference/$strokeCount")
                    var recorded = 0L
                    ui {
                        recorded = view.recordedCommandObjects
                        if (strokeCount == 1024) check(recorded == strokeCount.toLong())
                        else check(recorded in 1 until strokeCount.toLong()) { "Fixture did not exceed retained-command budget" }
                        discard(listOf(field(field(view, "hardwareScene"), "node") as RenderNode))
                    }
                    compare(initial, capture("retained/reclaimed-parent"), "retained parent")
                    ui {
                        if (!(field(field(view, "hardwareScene"), "node") as RenderNode).hasDisplayList())
                            failures.add("retained parent stayed invalid")
                        check(view.recordedCommandObjects == recorded) { "Recovery rebuilt unchanged ink geometry" }
                        view.resetZoom(); view.zoomBy(1.2f)
                    }
                    val zoomed = capture("retained/zoomed")
                    try {
                        ui {
                            recorded = view.recordedCommandObjects
                            discard(commandNodes(view).take(16))
                            check((field(field(view, "hardwareScene"), "node") as RenderNode).hasDisplayList())
                        }
                        compare(zoomed, capture("retained/reclaimed-child-with-live-parent"), "retained child with live parent")
                        ui {
                            check(view.recordedCommandObjects == recorded)
                            discard(commandNodes(view))
                            // A zoom/erase/pan invalidates the scene but keeps unchanged object commands.
                            view.zoomBy(1f)
                        }
                        compare(zoomed, capture("retained/reclaimed-children"), "retained children after viewport change")
                        ui { check(view.recordedCommandObjects == recorded) { "Recovery discarded Picture optimization" } }
                    } finally { zoomed.recycle() }
                } finally { initial.recycle() }
            }
        } finally {
            if (viewCreated) ui { view.close() }
            repository.deleteDocument(document.id)
        }
        }

        PenPresentationChecks.HardwareRasterOracle(384, 256).use { hardware ->
            val cases = listOf("fountain", "ballpoint", "gel", "brush", "fineliner", "highlighter").map { it to 521 } +
                listOf("fountain" to 9001, "highlighter" to 9001)
            for ((brush, sampleCount) in cases) {
                val publication = InkPointPublication()
                val raw = JSONArray()
                fun append(index: Int) {
                    val point = InkPoint(20f + index * (if (sampleCount == 521) .6f else .035f), 120f + sin(index * .05).toFloat() * 60f,
                        .4f + (index % 13) * .04f)
                    publication.append(point); raw.put(point.json())
                }
                repeat(sampleCount, ::append)
                val stroke = json("id" to "live-$brush", "type" to "stroke", "brush" to brush,
                    "width" to 6, "color" to "#a9436fa1", "opacity" to .75,
                    "settings" to json("smoothing" to .37, "taper" to .78, "pressure" to .63))
                val ordinary = NoteRenderer()
                val front = InkFrontFrameRenderer()
                fun frame() = InkFrontFrame(stroke, 0f, 0f, 1f, 384f, 256f, publication.snapshot())
                fun canonical() = JSONObject().apply {
                    stroke.keys().forEach { put(it, stroke.get(it)) }; put("points", raw)
                }
                fun capture(frontLayer: Boolean, reference: Boolean, label: String): Bitmap =
                    hardware.capture(label) { canvas ->
                        canvas.drawColor(if (frontLayer) Color.TRANSPARENT else Color.LTGRAY, BlendMode.SRC)
                        if (frontLayer) {
                            if (reference) InkFrontFrameRenderer().draw(canvas, 384, 256,
                                frame().copy(stroke = canonical(), points = null))
                            else front.draw(canvas, 384, 256, frame())
                        } else if (reference) NoteRenderer().draw(canvas, canonical(), 1.0)
                        else ordinary.stroke(canvas, stroke, publication.snapshot(), 1.0)
                    }
                for (frontLayer in listOf(false, true)) {
                    val label = "$brush/$sampleCount/${if (frontLayer) "front" else "ordinary"}"
                    capture(frontLayer, false, "$label/warm").recycle()
                    val renderer = if (frontLayer) field(front, "brush") as NoteRenderer else ordinary
                    // Repeat with the same snapshot and then an appended point, keeping cached paths.
                    repeat(2) { step ->
                        val paths = field(field(field(renderer, "legacyBrush"), "paths"), "paths") as List<*>
                        val prefixPaths = paths.toList()
                        discard(bodyNodes(renderer))
                        if (step == 1) append(raw.length())
                        val expected = capture(frontLayer, true, "$label/reference/$step")
                        try { compare(expected, capture(frontLayer, false, "$label/recovered/$step"), "$label/$step") }
                        finally { expected.recycle() }
                        if (bodyNodes(renderer).any { !it.hasDisplayList() }) failures.add("$label/$step stayed invalid")
                        check(prefixPaths.indices.all { paths[it] === prefixPaths[it] }) {
                            "Recovery rebuilt cached live geometry: $label/$step"
                        }
                    }
                }
            }
        }
        result.putInt("displayListRecoveryComparisons", comparisons)
        check(failures.isEmpty()) { "Reclaimed hardware commands lost ink: ${failures.joinToString()}" }
        result.putString("displayListRecovery", "1024_and_6144_strokes_including_command_budget_fallback_six_live_brushes_and_9001_point_live_cache_overflow_recovered_with_identical_hardware_pixels")
    }
}
