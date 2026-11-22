package com.inkforge.notesstudio

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Delayed real repository writes must retain the visible state published by each View edit. */
object LassoOwnershipChecks {
    private const val WIDTH = 1080
    private const val HEIGHT = 1600
    private const val COORDINATE_TOLERANCE = .001

    private data class PublishedEdit(
        val label: String,
        val pageId: String,
        val change: ObjectChange,
        val positions: Map<String, Int>,
        val expected: NotePage
    )

    private fun fixture(): List<JSONObject> {
        val prefix = uid("lasso-ownership")
        fun stroke(index: Int, x: Int, y: Int) = json(
            "id" to "$prefix-stroke-$index", "type" to "stroke",
            "brush" to if (index == 0) "ballpoint" else "fountain",
            "color" to "#172033", "width" to 4, "screenWidth" to 3,
            "captureSeq" to 45 + index, "captureSessionId" to "ownership-session-$index",
            "timeBasis" to "session-monotonic", "revision" to 4 + index * 5,
            "createdAt" to 1_700_000_000_000L + index, "optionalObject" to JSONObject.NULL,
            "vendorObject" to json("revision" to 700 + index, "x" to -999,
                "nested" to JSONArray(listOf(3, JSONObject.NULL, json("future" to true)))),
            "points" to JSONArray((0 until 3).map { sample ->
                json("x" to x + sample * 18, "y" to y + if (sample == 1) 11 else 0,
                    "p" to .35 + sample * .1, "t" to 1000L + index * 100 + sample,
                    "tilt" to .2, "orientation" to -.4, "pointerId" to 7,
                    "azimuth" to 1.1, "ty" to JSONObject.NULL,
                    "vendorPoint" to json("sample" to sample,
                        "nested" to JSONArray(listOf("raw", JSONObject.NULL, json("x" to -123)))))
                    .apply { if (sample != 1) put("tx", JSONObject.NULL) }
            })
        )
        return listOf(stroke(0, 200, 320),
            json("id" to "$prefix-locked", "type" to "tape", "locked" to true,
                "x" to 180, "y" to 350, "w" to 430, "h" to 18,
                "color" to "#d02020", "revealed" to false, "revision" to 41,
                "vendorLocked" to json("keep" to JSONArray(listOf(JSONObject.NULL, "interleaved")))),
            stroke(1, 500, 500)).map { it.copyJson() }
    }

    private fun snapshot(page: NotePage) = NotePage(page.id, page.meta.copyJson(),
        page.objects.map { it.copyJson() }.toMutableList())

    private fun assertJsonEqual(actual: Any?, expected: Any?, path: String,
                                ignoreKeys: Set<String> = emptySet()) {
        when {
            actual is JSONObject && expected is JSONObject -> {
                val keys = actual.keys().asSequence().toSet() - ignoreKeys
                check(keys == expected.keys().asSequence().toSet() - ignoreKeys) { "$path: JSON fields changed" }
                for (key in keys) assertJsonEqual(actual.get(key), expected.get(key), "$path.$key")
            }
            actual is JSONArray && expected is JSONArray -> {
                check(actual.length() == expected.length()) { "$path: array length changed" }
                for (index in 0 until actual.length())
                    assertJsonEqual(actual.get(index), expected.get(index), "$path[$index]")
            }
            actual is Number && expected is Number -> check(actual.toDouble() == expected.toDouble()) {
                "$path: number changed ($actual != $expected)"
            }
            else -> check(actual == expected) { "$path: value changed ($actual != $expected)" }
        }
    }

    private fun assertPageEqual(actual: NotePage, expected: NotePage, label: String) {
        check(actual.id == expected.id) { "$label: page changed" }
        assertJsonEqual(actual.meta, expected.meta, "$label.meta")
        check(actual.objects.map { it.getString("id") } == expected.objects.map { it.getString("id") }) {
            "$label: interleaved object order changed"
        }
        actual.objects.zip(expected.objects).forEach { (value, source) ->
            // No exclusions or coordinate tolerance here: saved publication snapshots must match exactly.
            assertJsonEqual(value, source, "$label.${source.getString("id")}")
        }
    }

    private fun assertVisibleState(actual: NotePage, original: NotePage, dx: Float, dy: Float,
                                   revisionStep: Int, label: String) {
        check(actual.id == original.id) { "$label: page changed" }
        assertJsonEqual(actual.meta, original.meta, "$label.meta")
        check(actual.objects.map { it.getString("id") } == original.objects.map { it.getString("id") }) {
            "$label: interleaved object order changed"
        }
        actual.objects.zip(original.objects).forEach { (value, source) ->
            val path = "$label.${source.getString("id")}"
            if (source.optString("type") != "stroke") {
                assertJsonEqual(value, source, path)
            } else {
                assertJsonEqual(value, source, path, setOf("points", "revision"))
                check(value.getLong("revision") == source.getLong("revision") + revisionStep) {
                    "$path: publication revision did not advance once per visible edit"
                }
                val points = value.getJSONArray("points")
                val before = source.getJSONArray("points")
                check(points.length() == before.length()) { "$path: raw point count changed" }
                for (index in 0 until points.length()) {
                    val point = points.getJSONObject(index)
                    val prior = before.getJSONObject(index)
                    assertJsonEqual(point, prior, "$path.points[$index]", setOf("x", "y"))
                    for ((axis, delta) in listOf("x" to dx, "y" to dy)) {
                        val coordinate = point.getDouble(axis)
                        val expected = prior.getDouble(axis) + delta
                        check(coordinate.isFinite() && abs(coordinate - expected) <= COORDINATE_TOLERANCE) {
                            "$path.points[$index].$axis: $coordinate != $expected"
                        }
                    }
                }
            }
        }
    }

    private fun assertScalarJsonEqual(actual: Any?, expected: Any?, path: String) {
        when (expected) {
            is JSONObject -> {
                check(actual is JSONObject) { "$path: expected an object" }
                val keys = actual.keys().asSequence().toList()
                check(keys == expected.keys().asSequence().toList()) { "$path: field order or keys changed" }
                for (key in keys) assertScalarJsonEqual(actual.get(key), expected.get(key), "$path.$key")
            }
            is JSONArray -> {
                check(actual is JSONArray) { "$path: expected an array" }
                check(actual.length() == expected.length()) { "$path: array length changed" }
                for (index in 0 until expected.length())
                    assertScalarJsonEqual(actual.opt(index), expected.opt(index), "$path[$index]")
            }
            is Number -> {
                check(actual is Number && actual.javaClass == expected.javaClass) {
                    "$path: normalized number class changed (${actual?.javaClass} != ${expected.javaClass})"
                }
                if (expected is Double) {
                    check(java.lang.Double.doubleToRawLongBits(actual.toDouble()) ==
                        java.lang.Double.doubleToRawLongBits(expected)) { "$path: Double bits changed" }
                } else {
                    check(actual == expected) { "$path: normalized number changed ($actual != $expected)" }
                }
            }
            else -> check(actual?.javaClass == expected?.javaClass && actual == expected) {
                "$path: scalar value or class changed ($actual != $expected)"
            }
        }
    }

    private fun transformScalarChecks() {
        fun numbers(values: List<Number>) = JSONArray().apply {
            for (value in values) put(value)
        }
        val two53 = 9_007_199_254_740_992.0
        val vendor = json(
            "floats" to numbers(listOf(.23f, -0.0f, Float.MIN_VALUE, java.lang.Float.MIN_NORMAL,
                Float.MAX_VALUE, -Float.MAX_VALUE)),
            "doubles" to numbers(listOf(-0.0, Double.MIN_VALUE, java.lang.Double.MIN_NORMAL,
                Double.MAX_VALUE, -Double.MAX_VALUE, Math.nextUp(1.0), Math.nextDown(1.0),
                two53 - 1.0, two53, two53 + 1.0, two53 + 2.0, Long.MAX_VALUE.toDouble())),
            "integers" to numbers(listOf<Number>(Byte.MIN_VALUE, Byte.MAX_VALUE,
                Short.MIN_VALUE, Short.MAX_VALUE, Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE,
                -1L, 0L, 1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(),
                Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1,
                9_007_199_254_740_993L, Long.MIN_VALUE, Long.MAX_VALUE)),
            "bigDecimals" to numbers(listOf(BigDecimal("0.2300"), BigDecimal("-0.0"),
                BigDecimal("1E-100"), BigDecimal("9007199254740993"),
                BigDecimal("9223372036854775808"), BigDecimal("12345678901234567890.123456789"))),
            "bigIntegers" to numbers(listOf(BigInteger.ZERO, BigInteger.ONE,
                BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger("18446744073709551617"))),
            "null" to JSONObject.NULL,
            // JSONArray.put(Object) retains raw Java null, unlike its Collection constructor's wrap().
            "rawNullArray" to JSONArray().put(null as Any?).put(JSONObject.NULL).put("after-null"),
            "booleans" to JSONArray(listOf(true, false)),
            "strings" to JSONArray(listOf("\"\\/\b\u000c\n\r\t\u0000", "한글 😀",
                "0", "-0", "1.0", "1e3", "001", "9007199254740993", "null", "true")),
            "nestedObject" to json("items" to JSONArray(listOf(json("leaf" to "unchanged"),
                JSONArray(listOf("nested", JSONObject.NULL, json("empty" to JSONArray())))))),
            "nestedArray" to JSONArray(listOf(JSONArray(listOf(json("value" to .23f))),
                json("items" to JSONArray(listOf(JSONObject.NULL, -0.0))))),
            "escaped-\"\u0000-한글-😀" to "preserved"
        )
        check(vendor.getJSONArray("bigDecimals").get(0) is BigDecimal &&
            vendor.getJSONArray("bigIntegers").get(0) is BigInteger) {
            "Scalar fixture must retain java.math Number values before normalization"
        }
        val source = json("id" to "transform-scalar-equivalence", "type" to "stroke", "width" to 4,
            "points" to JSONArray(listOf(json("x" to 1, "y" to 2))), "vendor" to vendor)
        val before = source.toString()
        val expected = source.copyJson().getJSONObject("vendor")
        val actual = InkGeometry.transform(source, 0f, 0f).getJSONObject("vendor")
        assertScalarJsonEqual(actual, expected, "Android transform scalar equivalence")

        actual.getJSONObject("nestedObject").getJSONArray("items").getJSONObject(0).put("leaf", "changed")
        actual.getJSONObject("nestedObject").getJSONArray("items").getJSONArray(1).put(0, "changed")
        actual.getJSONArray("nestedArray").getJSONArray(0).getJSONObject(0).put("value", "changed")
        actual.getJSONArray("nestedArray").getJSONObject(1).getJSONArray("items").put(0, "changed")
        actual.getJSONArray("rawNullArray").put(0, "changed")
        check(source.toString() == before) { "Nested transformed metadata mutation changed the source stroke" }
    }

    private fun settle(repository: NoteRepository) {
        val done = CountDownLatch(1)
        repository.executor.execute { done.countDown() }
        check(done.await(60, TimeUnit.SECONDS)) { "Lasso ownership page load did not settle" }
    }

    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle) {
        transformScalarChecks()
        result.putString("lassoOwnershipScalarEquivalence",
            "PASS: Android copyJson-equivalent scalar classes/Double bits, nested metadata order/nulls/strings, isolated transformed mutations")
        val repository = activity.repository
        val document = repository.create("lasso-delayed-save-ownership", "root", "blank")
        val page = requireNotNull(repository.page(repository.pageIds(document.id).first()))
        page.objects.addAll(fixture())
        repository.putPage(document.id, page, 0)
        val original = snapshot(page)
        val buffered = mutableListOf<PublishedEdit>()
        var view: InkCanvasView? = null
        var finalRuntime: NotePage? = null
        try {
            ui {
                val canvasView = InkCanvasView(activity, repository)
                view = canvasView
                canvasView.drawHold = false
                canvasView.scribbleErase = false
                canvasView.visibility = View.INVISIBLE
                (activity.window.decorView as ViewGroup).addView(canvasView, FrameLayout.LayoutParams(WIDTH, HEIGHT))
                canvasView.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
                canvasView.layout(0, 0, WIDTH, HEIGHT)
                canvasView.open(listOf(page.id), listOf(page.meta))
            }
            // Attached View.post is delivered before the next UI block; a detached View queues open's load.
            settle(repository)
            ui {
                val canvasView = requireNotNull(view)
                assertPageEqual(checkNotNull(canvasView.currentPage) { "Open's asynchronous page was not delivered" },
                    original, "loaded before replacement")
                (canvasView.parent as ViewGroup).removeView(canvasView)
                canvasView.visibility = View.VISIBLE
                canvasView.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
                canvasView.layout(0, 0, WIDTH, HEIGHT)
                canvasView.replacePage(page)
                canvasView.tool = InkCanvasView.Tool.LASSO
                val input = StylusContactChecks.Input(canvasView)
                var publication = ""
                var expectedX = 0f
                var expectedY = 0f
                canvasView.onEdit = { id, change, positions ->
                    check(id == page.id && publication.isNotEmpty()) { "Unexpected lasso publication" }
                    // Copy the small visible page, not change.after: shared delta mutations must remain observable.
                    val expected = snapshot(requireNotNull(canvasView.currentPage))
                    assertVisibleState(expected, original, expectedX, expectedY, buffered.size + 1, publication)
                    buffered += PublishedEdit(publication, id, change, positions, expected)
                }
                fun edit(label: String, x: Float, y: Float, action: () -> Unit) {
                    publication = label
                    expectedX = x
                    expectedY = y
                    val count = buffered.size
                    action()
                    check(buffered.size == count + 1) { "$label: expected one visible edit" }
                    assertPageEqual(page, buffered.last().expected, "$label runtime")
                    publication = ""
                }
                fun preview(dx: Float, dy: Float): Pair<Float, Float> {
                    canvasView.selectAll()
                    val first = page.objects.first { it.optString("type") == "stroke" }
                        .getJSONArray("points").getJSONObject(0)
                    val x = first.getDouble("x").toFloat()
                    val y = first.getDouble("y").toFloat()
                    input.touch(MotionEvent.ACTION_DOWN, x, y)
                    input.touch(MotionEvent.ACTION_MOVE, x + dx, y + dy,
                        history = listOf(x + dx / 3 to y + dy / 3, x + dx * 2 / 3 to y + dy * 2 / 3))
                    return x + dx to y + dy
                }
                fun drag(dx: Float, dy: Float) {
                    val before = snapshot(page)
                    val count = buffered.size
                    val end = preview(dx, dy)
                    check(buffered.size == count) { "Lasso preview published before lift" }
                    assertPageEqual(page, before, "uncommitted drag preview")
                    input.touch(MotionEvent.ACTION_UP, end.first, end.second)
                }

                edit("drag A", 80f, 48f) { drag(80f, 48f) }
                edit("drag B", 56f, 84f) { drag(-24f, 36f) }
                check(canvasView.canUndo && !canvasView.canRedo)
                val beforeCancel = snapshot(page)
                val beforeCancelEdits = buffered.size
                val cancelledEnd = preview(130f, -45f)
                assertPageEqual(page, beforeCancel, "third preview")
                input.touch(MotionEvent.ACTION_CANCEL, cancelledEnd.first, cancelledEnd.second)
                input.touch(MotionEvent.ACTION_MOVE, cancelledEnd.first + 20, cancelledEnd.second + 20)
                input.touch(MotionEvent.ACTION_UP, cancelledEnd.first + 30, cancelledEnd.second + 30)
                check(buffered.size == beforeCancelEdits) { "Cancelled third preview committed a stale drag" }
                assertPageEqual(page, beforeCancel, "cancelled third preview")
                check(canvasView.canUndo && !canvasView.canRedo)

                // B.before may share A.after. Undo must not revise either earlier delayed publication.
                edit("undo B", 80f, 48f) { canvasView.undo() }
                edit("undo A", 0f, 0f) { canvasView.undo() }
                check(!canvasView.canUndo && canvasView.canRedo) { "Cancel added an undo entry" }
                edit("redo A", 80f, 48f) { canvasView.redo() }
                edit("redo B", 56f, 84f) { canvasView.redo() }
                check(canvasView.canUndo && !canvasView.canRedo)

                val beforeActiveUndo = snapshot(page)
                val interruptedEnd = preview(95f, 70f)
                assertPageEqual(page, beforeActiveUndo, "preview before active undo")
                edit("undo B during drag", 80f, 48f) { canvasView.undo() }
                val afterActiveUndo = snapshot(page)
                val afterActiveUndoEdits = buffered.size
                input.touch(MotionEvent.ACTION_MOVE, interruptedEnd.first + 25, interruptedEnd.second + 25)
                input.touch(MotionEvent.ACTION_UP, interruptedEnd.first + 40, interruptedEnd.second + 40)
                check(buffered.size == afterActiveUndoEdits) { "Active-drag undo allowed a stale lift commit" }
                assertPageEqual(page, afterActiveUndo, "stale contact after undo")
                check(canvasView.canRedo) { "Stale drag destroyed redo" }
                edit("redo B after cancelled contact", 56f, 84f) { canvasView.redo() }
                check(canvasView.canUndo && !canvasView.canRedo)
                finalRuntime = snapshot(requireNotNull(canvasView.currentPage))
            }

            assertPageEqual(requireNotNull(repository.page(page.id)), original, "storage while all writes wait")
            val publications = JSONArray()
            for (edit in buffered) {
                repository.commit(document.id, edit.pageId, edit.change, edit.positions)
                val saved = requireNotNull(repository.page(page.id))
                assertPageEqual(saved, edit.expected, "delayed save ${edit.label}")
                publications.put(json("edit" to edit.label,
                    "revisions" to JSONArray(edit.expected.objects.map { it.getLong("revision") })))
            }
            val runtime = requireNotNull(finalRuntime)
            val saved = requireNotNull(repository.page(page.id))
            assertPageEqual(saved, runtime, "final saved/runtime agreement")
            ui { assertPageEqual(requireNotNull(requireNotNull(view).currentPage), saved, "runtime after delayed saves") }
            result.putString("lassoOwnershipPublications", publications.toString())
            result.putString("lassoOwnership", "PASS: two delayed-save drags, raw metadata/nulls/order/publication revisions, cancel, undo/redo, active-drag undo without stale lift, final saved/runtime agreement")
        } finally {
            ui {
                view?.let { canvasView ->
                    canvasView.onEdit = { _, _, _ -> }
                    try {
                        canvasView.close()
                    } finally {
                        (canvasView.parent as? ViewGroup)?.removeView(canvasView)
                    }
                }
                view = null
            }
        }
    }
}
