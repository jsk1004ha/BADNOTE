package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringReader

class CompatibilityRegressionTest {
    @Test fun legacyTapeKeepsEndpointsThroughLoadMoveScaleAndRoundtrip() {
        val old = json("id" to "t", "type" to "tape", "x1" to 420, "y1" to 620, "x2" to 770, "y2" to 674)
        val page = NotePage.from(json("id" to "p", "objects" to JSONArray(listOf(old))))
        assertEquals(InkBounds(420f,620f,770f,674f), InkGeometry.bounds(page.objects.single()))
        val moved = InkGeometry.transform(page.objects.single(),10f,20f,2f,420f,620f)
        assertEquals(InkBounds(430f,640f,1130f,748f),InkGeometry.bounds(moved))
        assertEquals(InkGeometry.bounds(old),InkGeometry.bounds(NotePage.from(page.json()).objects.single()))
        val native = json("type" to "tape", "x" to 10, "y" to 20, "w" to 80, "h" to 30)
        assertEquals(InkBounds(20f,40f,180f,100f),InkGeometry.bounds(InkGeometry.transform(native,0f,0f,2f)))
    }
    @Test fun plainPrefixesAndLargeTextArePreservedWhileBinaryFieldsDecode() {
        val pages = mutableListOf<JSONObject>(); val decoded = ByteArrayOutputStream(); var assets = 0
        val longText = "data: " + "가".repeat(5000)
        val note = json("title" to "data: experiment results", "tags" to JSONArray(listOf("asset: allocation", "data: tag")),
            "pages" to JSONArray(listOf(json("id" to "p", "backgroundImage" to "data:image/png;base64,AQIDBA==",
                "objects" to JSONArray(listOf(json("type" to "text", "text" to longText),
                    json("type" to "text", "text" to "data:image/png;base64,AQIDBA==")))))))
        val meta = LegacyJsonReader(StringReader(note.toString()), { assets++; "asset:real" to decoded }, {pages += it}).read()
        assertEquals(1,assets); assertArrayEquals(byteArrayOf(1,2,3,4),decoded.toByteArray())
        assertEquals(note.getString("title"),meta.getString("title"))
        assertEquals("asset: allocation",meta.array("tags").getString(0))
        assertEquals(longText,pages.single().array("objects").getJSONObject(0).getString("text"))
        assertEquals("data:image/png;base64,AQIDBA==",pages.single().array("objects").getJSONObject(1).getString("text"))
    }
    @Test fun malformedActualBinaryStillFails() {
        for (source in listOf("data: broken", "data:image/png,abc", "data:image/png;base64,@@==")) {
            assertThrows(Exception::class.java) {
                LegacyJsonReader(StringReader(json("pages" to JSONArray(listOf(json("backgroundImage" to source)))).toString()),
                    {"asset:x" to ByteArrayOutputStream()}, {}).read()
            }
        }
    }
    @Test fun archiveRewritesOnlyBinaryFieldsAndRejectsMissingRealAssets() {
        val value = json("title" to "asset: allocation", "tags" to JSONArray(listOf("asset: tag")),
            "pages" to JSONArray(listOf(json("backgroundImage" to "asset:bg", "objects" to JSONArray(listOf(
                json("type" to "text", "text" to "asset: text"), json("type" to "image", "src" to "asset:image")))))),
            "audio" to JSONArray(listOf(json("src" to "asset:audio"))))
        val assets = mutableSetOf<String>(); NoteRepository.collectAssets(value,assets)
        assertEquals(setOf("bg","image","audio"),assets)
        NoteRepository.rewriteAssets(value,mapOf("bg" to "new-bg","image" to "new-image","audio" to "new-audio"))
        assertEquals("asset: allocation",value.getString("title")); assertEquals("asset: tag",value.array("tags").getString(0))
        val page = value.array("pages").getJSONObject(0)
        assertEquals("asset: text",page.array("objects").getJSONObject(0).getString("text"))
        assertEquals("asset:new-image",page.array("objects").getJSONObject(1).getString("src"))
        assertThrows(IllegalArgumentException::class.java) { NoteRepository.rewriteAssets(json("src" to "asset:missing"),emptyMap()) }
    }
    @Test fun rotationBoundsHitAndPartialEraseUseVisibleGeometry() {
        val image = json("type" to "image", "x" to 100, "y" to 100, "w" to 100, "h" to 40, "rotation" to Math.PI/2)
        val bounds = InkGeometry.bounds(image)
        assertEquals(130f,bounds.left,.001f); assertEquals(70f,bounds.top,.001f)
        assertTrue(InkGeometry.hit(image,InkPoint(150f,80f),0f))
        assertFalse(InkGeometry.hit(image,InkPoint(110f,120f),0f))
        val stroke = json("id" to "s", "type" to "stroke", "width" to 2, "rotation" to Math.PI/2,
            "points" to JSONArray(listOf(InkPoint(0f,0f).json(),InkPoint(100f,0f).json())))
        val fragments = InkGeometry.eraseParts(stroke,InkPoint(50f,0f),9f)
        assertEquals(2,fragments.size)
        assertEquals(50f,InkGeometry.points(fragments[0]).first().x,.001f)
        assertEquals(-10f,InkGeometry.points(fragments[0]).last().y,.001f)
        assertEquals(0f,fragments[0].f("rotation"),0f)
    }
    @Test fun outlineRetainsManualEntriesAndDerivesLegacyTitlesAndHeadings() {
        val page = NotePage("p",json("title" to "Chapter"),mutableListOf(
            json("id" to "heading", "type" to "text", "fontSize" to 30, "text" to "Heading\nbody"),
            json("type" to "text", "fontSize" to 29, "text" to "ordinary")))
        val doc = DocumentInfo("d",json("outline" to JSONArray(listOf(json("title" to "Manual", "pageId" to "p")))))
        val entries = doc.outlineEntries(sequenceOf(page))
        assertEquals(listOf("Manual","Chapter","Heading"),entries.map{it.getString("title")})
        assertEquals("heading",entries.last().getString("objectId")); assertEquals(0,entries.last().getInt("pageIndex"))
    }
    @Test fun documentPageModeOverridesDefaultAndSurvivesSerialization() {
        val single = DocumentInfo("s",json("settings" to json("pageMode" to "single", "other" to 7)))
        assertFalse(single.continuous(true))
        assertFalse(DocumentInfo("s",JSONObject(single.data.toString())).continuous(true))
        assertTrue(DocumentInfo("c",json("settings" to json("pageMode" to "continuous"))).continuous(false))
        assertFalse(DocumentInfo("default",JSONObject()).continuous(false))
    }
}
