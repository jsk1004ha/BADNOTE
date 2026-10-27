package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SparseInkHistoryTest {
    private fun item(id:String)=json("id" to id,"type" to "stroke","points" to JSONArray()
        .put(json("x" to 12,"y" to 34,"extension" to json("nullValue" to JSONObject.NULL,"tag" to id))))
    private fun page(vararg ids:String)=NotePage("page",JSONObject(),IndexedObjectList(ids.map(::item)))
    private fun ids(page:NotePage)=page.objects.map{it.getString("id")}

    @Test fun editorChangesAndCancellationStayVisibleToTheSuppliedPagesOwner() {
        val first=item("first");val second=item("second");val third=item("third")
        val owner=NotePage("page",JSONObject(),mutableListOf(first,second,third))
        val original=owner.objects.map{it.toString()}
        val editor=owner.prepareForEditing()
        val replacement=item("pen")
        val change=ObjectChange(listOf(first),listOf(replacement),mapOf("first" to 0),mapOf("pen" to 0))
        change.applyOwnedAfter(editor)
        assertEquals(listOf("pen","second","third"),ids(owner))
        assertEquals(replacement.toString(),owner.objects[0].toString())
        change.reversed().apply(editor)
        assertEquals(listOf("first","second","third"),ids(owner))
        assertEquals(original,owner.objects.map{it.toString()})
    }

    @Test fun sparseAppendUndoRedoKeepsEveryUnrelatedObjectAndPointExtension() {
        val page=page("a","b","c")
        val original=page.objects.map{it.toString()}
        val appended=item("pen")
        val change=ObjectChange(emptyList(),listOf(appended),afterPositions=mapOf("pen" to 3))
        val history=EditHistory()
        change.apply(page);history.push(change)
        assertEquals(listOf("a","b","c","pen"),ids(page))
        requireNotNull(history.undo()).apply(page)
        assertEquals(listOf("a","b","c"),ids(page))
        assertEquals(original,page.objects.map{it.toString()})
        requireNotNull(history.redo()).apply(page)
        assertEquals(listOf("a","b","c","pen"),ids(page))
        assertTrue(page.objects.last().array("points").getJSONObject(0).getJSONObject("extension").isNull("nullValue"))
        assertEquals("pen",page.objects.last().array("points").getJSONObject(0).getJSONObject("extension").getString("tag"))
    }

    @Test fun sparseSplitAndDistantDeletionRestoreExactOriginalOrder() {
        val page=page("a","b","c","d","e","f")
        val before=listOf(page.objects[1],page.objects[4])
        val split=listOf(item("b"),item("fragment"))
        val change=ObjectChange(before,split,mapOf("b" to 1,"e" to 4),mapOf("b" to 1,"fragment" to 2))
        repeat(4) {
            change.apply(page)
            assertEquals(listOf("a","b","fragment","c","d","f"),ids(page))
            change.reversed().apply(page)
            assertEquals(listOf("a","b","c","d","e","f"),ids(page))
            assertEquals(before[0].toString(),page.objects[1].toString())
            assertEquals(before[1].toString(),page.objects[4].toString())
        }
    }

    @Test fun historyEvictionAndRedoStillReplayCompleteChanges() {
        val first=ObjectChange(emptyList(),listOf(item("first")),afterPositions=mapOf("first" to 0))
        val second=ObjectChange(emptyList(),listOf(item("second")),afterPositions=mapOf("second" to 1))
        val history=EditHistory(first.bytes+second.bytes-1)
        val page=page()
        first.apply(page);history.push(first)
        second.apply(page);history.push(second)
        requireNotNull(history.undo()).apply(page)
        assertEquals(listOf("first"),ids(page))
        assertFalse(history.canUndo)
        requireNotNull(history.redo()).apply(page)
        assertEquals(listOf("first","second"),ids(page))
    }
}
