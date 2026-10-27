package com.inkforge.notesstudio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real long-press/drag input and real controls; assertions cover persisted order, content and selection. */
internal object PageManagementChecks {
    fun run(activity: MainActivity, instrument: Instrumentation, result: Bundle, leaveOpen: Boolean = false) {
        check(activity.packageName.endsWith(".debug")) { "pageManagementRequiresDebugApp" }
        val repository = activity.repository
        val original = ui(instrument) { (field(activity, "document") as DocumentInfo).id to
            requireNotNull(activity.inkView).currentIndex }
        awaitSaved(activity, instrument)
        val doc = repository.create("페이지 관리 UI 검증", "root", "blank")
        val first = repository.pageIds(doc.id).single()
        val heldSaves = mutableListOf<CountDownLatch>()
        var completed = false
        try {
            ui(instrument) { activity.openDocument(doc.id) }
            fun selected(id: String) = ui(instrument) { activity.inkView?.currentPage?.id == id &&
                field(activity, "busy") == false }
            fun sidebar() = requireNotNull(field(activity, "pageSidebar") as? ViewGroup)
            fun modal() = requireNotNull(field(activity, "modal") as? ViewGroup)
            fun grid() = ui(instrument) { descendants(sidebar()).filterIsInstance<RecyclerView>().single() }
            fun openPages() {
                if (ui(instrument) { field(activity, "pageSidebar") == null })
                    click(activity, instrument, navbar(activity, instrument), "페이지 관리")
            }
            fun show(index: Int) {
                ui(instrument) { grid().scrollToPosition(index) }
                instrument.waitForIdleSync()
                await("thumbnailVisible:$index") { ui(instrument) {
                    val target = descendants(grid()).firstOrNull {
                        it.isClickable && it.contentDescription == "${translated(activity, "페이지")} ${index + 1}"
                    }
                    target != null && Rect().let { target.getGlobalVisibleRect(it) && it.height() == target.height }
                } }
            }
            fun pageAction(label: String, index: Int = ui(instrument) { activity.inkView!!.currentIndex }) {
                val selectedBefore = ui(instrument) { activity.inkView!!.currentPage!!.id }
                show(index)
                click(activity, instrument, sidebar(), "${translated(activity, "페이지 메뉴")} ${index + 1}", false)
                check(selected(selectedBefore)) { "openingPageMenuChangedCurrentPage" }
                ui(instrument) {
                    val popup=modal().getChildAt(0);val bounds=global(popup);val host=global(modal())
                    check(popup.width<host.width()-8*activity.resources.displayMetrics.density &&
                        bounds.bottom<host.bottom && bounds.top>host.top) { "pageMenuIsStillBottomSheet:$bounds:$host" }
                    check(descendants(modal()).none { it.contentDescription==translated(activity,"이 페이지로 이동") }) {
                        "pageMenuStillHasGoToAction"
                    }
                }
                click(activity, instrument, modal(), label)
            }
            fun reopen(id: String) {
                awaitSaved(activity, instrument)
                ui(instrument) { activity.showLibrary(); activity.openDocument(doc.id) }
                await("rememberedPage:$id") { selected(id) }
                openPages()
            }
            fun number(index: Int) {
                ui(instrument) { descendants(sidebar()).filterIsInstance<EditText>().single().setText(index.toString()) }
                click(activity, instrument, sidebar(), "페이지 이동")
            }
            fun holdSave(): CountDownLatch {
                awaitSaved(activity, instrument)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1).also(heldSaves::add)
                // Let the foreground assertion report its 30s timeout before this fixture watchdog fires.
                repository.executor.execute { entered.countDown(); check(release.await(60, TimeUnit.SECONDS)) }
                check(entered.await(10, TimeUnit.SECONDS)) { "pageSaveExecutorNotHeld" }
                return release
            }
            fun lastPageProtected() {
                show(0)
                click(activity, instrument, sidebar(), "${translated(activity, "페이지 메뉴")} 1", false)
                val deletion = ui(instrument) { find(modal(), translated(activity, "페이지 삭제")) }
                ui(instrument) {
                    check(!deletion.isEnabled && descendants(deletion).filter { it.isClickable }.all { !it.isEnabled }) {
                        "lastPageDeleteEnabled"
                    }
                    check(descendants(modal()).filterIsInstance<TextView>().any {
                        it.text.toString() == translated(activity, "마지막 페이지는 삭제할 수 없습니다.")
                    }) { "lastPageProtectionNotExplained" }
                }
                assertTarget(instrument, deletion)
                click(activity, instrument, modal(), "닫기")
            }
            fun drag(source: Int, destination: Int, after: Boolean) {
                show(source)
                val originalOrder = repository.pageIds(doc.id)
                val originalIds = ui(instrument) { requireNotNull(grid().adapter).let { adapter ->
                    (0 until adapter.itemCount).map { adapter.getItemId(it) }
                } }
                val input = beginDrag(activity, instrument, grid(), source)
                input.use {
                    show(destination)
                    val bounds = cardBounds(activity, instrument, grid(), destination)
                    // Keep this ordering check outside the edge-scroll zone; long edge drags are checked below.
                    input.move(PointF(bounds.exactCenterX(), bounds.top + bounds.height() * if (after) .8f else .35f))
                    val boundary = destination + if (after) 1 else 0
                    val target = boundary - if (source < boundary) 1 else 0
                    val expected = originalIds.toMutableList().apply { add(target, removeAt(source)) }
                    try {
                        await("animatedPagePreview:$source:$destination:$after") { ui(instrument) {
                            val adapter = requireNotNull(grid().adapter)
                            adapter.hasStableIds() && (0 until adapter.itemCount).map { adapter.getItemId(it) } == expected &&
                                grid().itemAnimator?.isRunning == false
                        } }
                    } catch (failure: IllegalStateException) {
                        val actual=ui(instrument){requireNotNull(grid().adapter).let{adapter ->
                            (0 until adapter.itemCount).map{adapter.getItemId(it)} to grid().itemAnimator?.isRunning
                        }}
                        throw IllegalStateException("dragPreview:$source:$destination:$after:expected=$expected:actual=$actual",failure)
                    }
                    check(repository.pageIds(doc.id) == originalOrder) { "dragPreviewPersistedBeforeDrop" }
                    input.release()
                }
                instrument.waitForIdleSync()
            }
            await("pageManagementEditor") { selected(first) }
            instrument.waitForIdleSync()
            val originalTool = ui(instrument) { activity.inkView!!.tool }
            val navigation = navbar(activity, instrument)
            val navigationBefore = ui(instrument) { global(navigation) }
            val navigationActionsBefore = ui(instrument) { descendants(navigation).filter {
                it.isClickable && it.isShown && !it.contentDescription.isNullOrEmpty()
            }.associate { it.contentDescription.toString() to global(it) } }
            ui(instrument) {
                check((navigation as android.widget.LinearLayout).orientation == android.widget.LinearLayout.HORIZONTAL)
                check(navigation.height <= 64 * activity.resources.displayMetrics.density) { "toolbarUsesTwoRows" }
            }
            listOf("페이지 관리", "펜", "지우개", "문서 검색", "현재 페이지 손글씨 인식", "더 보기").forEach { label ->
                assertTarget(instrument, ui(instrument) { find(navigation, translated(activity, label)) })
            }
            ui(instrument) {
                val entry = find(navigation, translated(activity, "페이지 관리"))
                val adjacent = find(navigation, translated(activity, "더 보기"))
                check(descendants(entry).filterIsInstance<TextView>().none { it.text.isNotBlank() }) {
                    "pageManagementHasPersistentText"
                }
                val bounds = global(entry); val neighbor = global(adjacent)
                val expected = (48 * activity.resources.displayMetrics.density).toInt()
                check(abs(entry.width - expected) <= 1 && abs(entry.height - expected) <= 1 && entry.width == adjacent.width &&
                    entry.height == adjacent.height && bounds.top == neighbor.top && bounds.right <= neighbor.left) {
                    "pageManagementAndDocumentOptionsNotSwapped:$bounds:$neighbor"
                }
                check(descendants(navigation).filter { it.isClickable && it.isShown }.all {
                    val other = global(it)
                    abs(other.centerY() - neighbor.centerY()) > expected / 2 || other.right <= neighbor.right
                }) { "documentOptionsNotRightmost" }
            }
            capture(activity, instrument, "editor", result)
            ui(instrument) { activity.inkView!!.tool=InkCanvasView.Tool.PEN }
            instrument.waitForIdleSync()
            openPages()
            ui(instrument) {
                val density=activity.resources.displayMetrics.density
                val panel=global(sidebar());val editor=global(activity.inkView!!)
                val nav=global(navbar(activity,instrument));val dock=global(field(activity,"activeDock") as View)
                check(sidebar().width<=189*density && (grid().layoutManager as GridLayoutManager).spanCount==1) {
                    "pagePanelNotCompactSingleColumn"
                }
                check(nav==navigationBefore && navigationActionsBefore.all { (label,bounds) ->
                    global(find(navbar(activity,instrument),label))==bounds
                }) { "mainToolbarMovedWhenPagePanelOpened:$navigationBefore:$nav" }
                check(abs(panel.right-editor.left)<=1 && nav.left==panel.left && nav.right==editor.right &&
                    dock.left>=editor.left && dock.right<=editor.right && abs(dock.centerX()-editor.centerX())<=1) {
                    "penPaletteNotInsideRemainingCanvas:$panel:$editor:$nav:$dock"
                }
                check(descendants(sidebar()).none { it.isClickable && it.contentDescription == translated(activity, "페이지 메뉴") }) {
                    "persistentPageMenuStillVisible"
                }
                check(descendants(sidebar()).none { it.contentDescription == translated(activity, "페이지 삭제") }) {
                    "persistentDeleteFooterStillVisible"
                }
            }
            capture(activity,instrument,"pen-palette",result)
            ui(instrument) { activity.inkView!!.tool=originalTool }
            instrument.waitForIdleSync()
            lastPageProtected()
            val add = ui(instrument) { find(sidebar(), translated(activity, "새 페이지")) }
            assertTarget(instrument, add)
            ui(instrument) { check(descendants(add).filterIsInstance<TextView>().isEmpty()) { "addPageNotIconOnly" } }
            click(activity, instrument, sidebar(), "새 페이지")
            await("addedPageSelected") { repository.pageIds(doc.id).size == 2 && selected(repository.pageIds(doc.id).last()) }
            val second = repository.pageIds(doc.id).last()
            val releaseAdd=holdSave()
            val addPendingId=uid("page-ui-add-save")
            ui(instrument){activity.inkView!!.add(json("id" to addPendingId,"type" to "text","text" to "save before insert",
                "x" to 80,"y" to 140,"w" to 250,"h" to 40))}
            pageAction("앞 페이지 추가",0)
            check(repository.pageIds(doc.id)==listOf(first,second) && repository.page(second)!!.objects.isEmpty()) {
                "relativePageAddedBeforePendingSave"
            }
            releaseAdd.countDown()
            await("pageInsertedBeforeNonCurrentFirst") {
                val order=repository.pageIds(doc.id)
                order.size==3 && order.drop(1)==listOf(first,second) && selected(order.first())
            }
            val before=repository.pageIds(doc.id).first()
            check(repository.page(second)!!.objects.single().optString("id")==addPendingId) { "relativeAddLostPendingEdit" }
            pageAction("뒷 페이지 추가",2)
            await("pageInsertedAfterNonCurrentLast") {
                val order=repository.pageIds(doc.id)
                order.size==4 && order.take(3)==listOf(before,first,second) && selected(order.last())
            }
            val after=repository.pageIds(doc.id).last()
            listOf(before to first,after to second).forEach{(created,anchor)->
                val page=repository.page(created)!!;val source=repository.page(anchor)!!
                check(page.objects.isEmpty() && page.meta.optString("template")==source.meta.optString("template") &&
                    page.width==source.width && page.height==source.height) { "relativeAddDidNotCreateMatchingBlankPage" }
            }
            reopen(after)
            check(repository.pageIds(doc.id)==listOf(before,first,second,after)) { "relativePageOrderNotPersisted" }
            pageAction("페이지 삭제",0)
            await("removeBeforeFixture") { repository.pageIds(doc.id)==listOf(first,second,after) && selected(after) }
            pageAction("페이지 삭제")
            await("removeAfterFixture") { repository.pageIds(doc.id)==listOf(first,second) && selected(second) }
            pageAction("앞 페이지 추가",0)
            await("insertBeforeAfterDeletionGap") {
                val order=repository.pageIds(doc.id)
                order.size==3 && order.drop(1)==listOf(first,second) && selected(order.first())
            }
            pageAction("페이지 삭제")
            await("removeGapFixture") { repository.pageIds(doc.id)==listOf(first,second) && selected(first) }
            click(activity, instrument, sidebar(), "${translated(activity, "페이지")} 1", false)
            await("thumbnailNavigation") { selected(first) }
            fun centered()=ui(instrument){
                val view=activity.inkView!!;val page=view.currentPage!!
                val center=view.screenPoint(page.width/2,page.height/2)
                abs(center.x-view.width/2f)<1f && abs(center.y-view.height/2f)<1f
            }
            check(centered()) { "selectedPageNotCenteredInRemainingViewport" }
            val zoom=ui(instrument){activity.inkView!!.zoomBy(1.4f);activity.inkView!!.zoomPercent()}
            number(2);await("centerSecondPage"){selected(second)}
            check(centered() && ui(instrument){activity.inkView!!.zoomPercent()==zoom}) { "centerChangedZoomOrUsedWholeWindow" }
            number(1);await("centerFirstPage"){selected(first)}

            val releaseDuplicate = holdSave()
            val pendingId = uid("page-ui-pending")
            ui(instrument) { activity.inkView!!.add(json("id" to pendingId, "type" to "text",
                "text" to "페이지 복제 전 저장", "x" to 80, "y" to 140, "w" to 300, "h" to 50)) }
            pageAction("페이지 복제")
            check(repository.pageIds(doc.id).size == 2 && repository.page(first)!!.objects.isEmpty()) {
                "pageDuplicatedBeforePendingSave"
            }
            releaseDuplicate.countDown()
            await("duplicateIncludesPendingSave") { repository.pageIds(doc.id).size == 3 && selected(repository.pageIds(doc.id)[1]) }
            val duplicate = repository.pageIds(doc.id)[1]
            check(repository.page(first)!!.objects.single().optString("id") == pendingId)
            val copied = repository.page(duplicate)!!.objects.single()
            check(copied.optString("text") == "페이지 복제 전 저장" && copied.optString("id") != pendingId) {
                "duplicateLostPendingEditOrReusedObjectIdentity:expectedId=$pendingId:copied=$copied:source=${repository.page(first)!!.objects.single()}"
            }
            pageAction("북마크")
            await("thumbnailBookmarkPersisted") { repository.pageMeta(duplicate)?.optBoolean("bookmarked") == true }
            number(1)
            await("selectionBeforeDrag") { selected(first) }
            val historyId = uid("page-ui-history")
            ui(instrument) { activity.inkView!!.add(json("id" to historyId, "type" to "text", "text" to "undo after reorder",
                "x" to 100, "y" to 220, "w" to 220, "h" to 40)) }
            awaitSaved(activity, instrument)
            drag(1, 2, true)
            await("dragForwardKeepsSelectedId") { repository.pageIds(doc.id) == listOf(first, second, duplicate) && selected(first) }
            drag(2, 0, false)
            await("dragBackwardKeepsSelectedId") { repository.pageIds(doc.id) == listOf(duplicate, first, second) && selected(first) &&
                ui(instrument) { activity.inkView!!.currentIndex == 1 } }
            ui(instrument) { activity.inkView!!.undo() }
            awaitSaved(activity, instrument)
            check(repository.page(first)!!.objects.none { it.optString("id") == historyId }) { "reorderResetUndoHistory" }
            ui(instrument) { activity.inkView!!.redo() }
            awaitSaved(activity, instrument)
            check(repository.page(first)!!.objects.any { it.optString("id") == historyId }) { "reorderResetRedoHistory" }
            ui(instrument) { activity.inkView!!.undo() }
            awaitSaved(activity, instrument)
            fun accessibleDrag(source:Int,target:Int) {
                show(source)
                ui(instrument) {
                    val card=find(grid(),"${translated(activity,"페이지")} ${source+1}")
                    val action=if(Build.VERSION.SDK_INT>=32)AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_START.id
                        else AccessibilityNodeInfo.ACTION_LONG_CLICK
                    check(card.performAccessibilityAction(action,null)){"accessibleDragStartUnavailable"}
                }
                show(target)
                ui(instrument) {
                    val card=find(grid(),"${translated(activity,"페이지")} ${target+1}")
                    val action=card.createAccessibilityNodeInfo().actionList.firstOrNull{
                        it.label?.toString()==translated(activity,"이 페이지 앞에 놓기")
                    }?:error("accessibleDropUnavailable")
                    check(card.performAccessibilityAction(action.id,null)){"accessibleDropRejected"}
                }
            }
            accessibleDrag(0,2)
            await("accessibleDragPreservesSelection"){repository.pageIds(doc.id)==listOf(first,duplicate,second)&&selected(first)}
            accessibleDrag(1,0)
            await("accessibleDragRestoresOrder"){repository.pageIds(doc.id)==listOf(duplicate,first,second)&&selected(first)}

            val beforeCancel = repository.pageIds(doc.id)
            drag(0, 0, true)
            check(repository.pageIds(doc.id) == beforeCancel && selected(first)) { "noopDragChangedOrderOrSelection" }
            show(0)
            beginDrag(activity, instrument, grid(), 0).use { it.cancelIfHeld() }
            instrument.waitForIdleSync()
            check(repository.pageIds(doc.id) == beforeCancel && selected(first)) { "cancelledDragCommitted" }
            beginDrag(activity, instrument, grid(), 0).use { outside ->
                val outsideBounds = ui(instrument) { global(find(navbar(activity, instrument), translated(activity, "페이지 관리"))) }
                outside.move(PointF(outsideBounds.exactCenterX(), outsideBounds.exactCenterY()))
                outside.release()
            }
            instrument.waitForIdleSync()
            check(repository.pageIds(doc.id) == beforeCancel && selected(first)) { "outsideDropCommitted" }
            beginDrag(activity, instrument, grid(), 0).use { closing ->
                ui(instrument) { find(sidebar(), translated(activity, "페이지 관리 닫기")).performClick() }
                closing.cancelIfHeld()
            }
            check(repository.pageIds(doc.id) == beforeCancel && selected(first)) { "sidebarCloseCommittedDrag" }
            openPages()

            val releaseReorder = holdSave()
            val reorderEdit = uid("page-ui-reorder-save")
            ui(instrument) { activity.inkView!!.add(json("id" to reorderEdit, "type" to "text", "text" to "save before reorder",
                "x" to 80, "y" to 300, "w" to 240, "h" to 40)) }
            drag(0, 2, true)
            check(repository.pageIds(doc.id) == beforeCancel && repository.page(first)!!.objects.none {
                it.optString("id") == reorderEdit
            }) { "reorderIgnoredPendingSaveBarrier" }
            releaseReorder.countDown()
            await("reorderAfterPendingSave") { repository.pageIds(doc.id) == listOf(first, second, duplicate) && selected(first) &&
                repository.page(first)!!.objects.any { it.optString("id") == reorderEdit } }
            ui(instrument) { activity.inkView!!.undo() }
            awaitSaved(activity, instrument)

            // A genuinely scrollable odd-sized grid exercises recycling, edge scroll and final-row insertion.
            repeat(10) { repository.addPage(doc.id, NotePage.blank("blank"), repository.pageIds(doc.id).size) }
            reopen(first)
            val manyOrder = repository.pageIds(doc.id)
            check(manyOrder.size == 13)
            show(0)
            val initialLast = ui(instrument) { (grid().layoutManager as GridLayoutManager).findLastVisibleItemPosition() }
            val longDrag = beginDrag(activity, instrument, grid(), 0)
            longDrag.use {
                val viewport = ui(instrument) { global(grid()) }
                longDrag.move(PointF(viewport.right - 10f, viewport.bottom - 10f))
                await("dragEdgeScrollPastViewport") { ui(instrument) {
                    (grid().layoutManager as GridLayoutManager).findLastVisibleItemPosition() > initialLast
                } }
                await("dragEdgeScrollToLastPage") { ui(instrument) {
                    val manager = grid().layoutManager as GridLayoutManager
                    manager.findLastCompletelyVisibleItemPosition() == manyOrder.lastIndex
                } }
                check(repository.pageIds(doc.id) == manyOrder && selected(first)) { "hoverPersistedOrSelectedDraggedPage" }
                longDrag.release()
            }
            val movedToEnd = manyOrder.drop(1) + first
            await("longDragPersistedAtOddFinalRow") { repository.pageIds(doc.id) == movedToEnd && selected(first) }
            show(movedToEnd.lastIndex)
            val returnDrag = beginDrag(activity, instrument, grid(), movedToEnd.lastIndex)
            returnDrag.use {
                val viewport = ui(instrument) { global(grid()) }
                returnDrag.move(PointF(viewport.left + 10f, viewport.top + 10f))
                await("dragEdgeScrollBackToStart") { ui(instrument) {
                    (grid().layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition() == 0
                } }
                returnDrag.release()
            }
            await("longDragBackToStart") { repository.pageIds(doc.id) == manyOrder && selected(first) }
            reopen(first)
            check(repository.pageIds(doc.id) == manyOrder && ui(instrument) { activity.inkView!!.currentIndex == 0 }) {
                "reopenLostDragOrderOrSelectedId"
            }
            capture(activity, instrument, "sidebar", result)

            // A page arriving during a drag makes its captured permutation stale; it must not be lost.
            show(0)
            val added = NotePage.blank("blank")
            beginDrag(activity, instrument, grid(), 0).use { stale ->
                repository.addPage(doc.id, added, manyOrder.size)
                val target = cardBounds(activity, instrument, grid(), 1)
                stale.move(PointF(target.exactCenterX(), target.bottom - 6f))
                stale.release()
            }
            instrument.waitForIdleSync()
            await("concurrentPageAdditionCancelsStaleDrag") { ui(instrument) { field(activity, "busy") == false } &&
                repository.pageIds(doc.id) == manyOrder + added.id && selected(first) }
            number(manyOrder.size+1)
            await("staleDragRefreshesNavigation"){selected(added.id)}
            show(manyOrder.size)
            number(1)
            await("staleDragReturnsToOriginalSelection"){selected(first)}

            // The menu's stable page ID is independent of the current page; deletion has no second confirmation.
            awaitSaved(activity, instrument)
            repository.pageIds(doc.id).filter { it !in setOf(first, duplicate, second) }.forEach { repository.removePage(doc.id, it) }
            reopen(first)
            show(1)
            click(activity,instrument,sidebar(),"${translated(activity,"페이지 메뉴")} 2",false)
            capture(activity,instrument,"popover",result)
            click(activity,instrument,modal(),"닫기")
            check(repository.pageIds(doc.id)==listOf(first,second,duplicate) && selected(first)) { "closingMenuChangedPages" }
            val releaseDelete=holdSave()
            val deletePendingId=uid("page-ui-delete-save")
            ui(instrument){activity.inkView!!.add(json("id" to deletePendingId,"type" to "text","text" to "keep selected page",
                "x" to 70,"y" to 400,"w" to 220,"h" to 40))}
            pageAction("페이지 삭제",1)
            check(ui(instrument){field(activity,"modal")==null} && repository.pageIds(doc.id).size==3) { "deleteReconfirmedOrIgnoredSaveBarrier" }
            releaseDelete.countDown()
            await("nonCurrentDeleteKeepsSelectedPage") { repository.pageIds(doc.id) == listOf(first, duplicate) && selected(first) }
            check(repository.page(second) == null)
            check(repository.page(first)!!.objects.any{it.optString("id")==deletePendingId}) { "deleteLostCurrentPageEdit" }
            ui(instrument){activity.inkView!!.undo()};awaitSaved(activity,instrument)
            number(2);await("selectRemainingLastPage"){selected(duplicate)}
            reopen(duplicate)
            check(ui(instrument) { activity.inkView!!.currentIndex == 1 }) { "deleteRememberedWrongIndex" }
            pageAction("페이지 삭제")
            check(ui(instrument){field(activity,"modal")==null}) { "currentDeleteReconfirmed" }
            await("lastDeleteSelectsPreviousPage") { repository.pageIds(doc.id) == listOf(first) && selected(first) }
            reopen(first)
            lastPageProtected()
            check(repository.page(first)!!.objects.single().optString("id") == pendingId) { "remainingPageChanged" }
            click(activity, instrument, sidebar(), "페이지 관리 닫기")
            check(ui(instrument) { activity.inkView!!.tool == originalTool }) { "pageManagementChangedWritingTool" }
            if (leaveOpen) openPages()
            completed = true
            result.putInt("pageManagementWidthDp", activity.resources.configuration.screenWidthDp)
            result.putString("pageManagement", "compact_single_column_panel_fixed_main_toolbar_responsive_pen_palette_swapped_actions_centered_pages_anchored_popover_targeted_delete_without_reconfirmation_pending_save_last_guard_recycled_native_accessible_drag_and_persistence_verified")
        } finally {
            heldSaves.forEach(CountDownLatch::countDown)
            awaitSaved(activity, instrument)
            if (!leaveOpen || !completed) {
                ui(instrument) { activity.openDocument(original.first, original.second) }
                await("pageManagementOriginalRestored") { ui(instrument) { field(activity, "busy") == false &&
                    (field(activity, "document") as? DocumentInfo)?.id == original.first && activity.inkView?.currentPage != null } }
                awaitSaved(activity, instrument)
                repository.deleteDocument(doc.id)
            }
        }
    }

    private class DragInput(private val instrument: Instrumentation, start: PointF) : java.io.Closeable {
        private val down = SystemClock.uptimeMillis()
        private var point = start
        private var held = false
        init {
            send(MotionEvent.ACTION_DOWN)
            held = true
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
        }
        private fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                // A native drag transfers pointer capture to a system-owned drag window, not the app UID.
                check(instrument.uiAutomation.injectInputEvent(event, true)) {
                    "dragInputRejected:action=$action:x=${point.x}:y=${point.y}"
                }
            } finally { event.recycle() }
        }
        fun move(target: PointF) {
            val start = point
            repeat(12) { step ->
                val fraction = (step + 1) / 12f
                point = PointF(start.x + (target.x - start.x) * fraction, start.y + (target.y - start.y) * fraction)
                send(MotionEvent.ACTION_MOVE)
                SystemClock.sleep(18)
            }
        }
        fun release() { if (held) { send(MotionEvent.ACTION_UP); held = false } }
        fun cancelIfHeld() { if (held) { held = false; send(MotionEvent.ACTION_CANCEL) } }
        override fun close() = cancelIfHeld()
    }

    private fun beginDrag(activity: MainActivity, instrument: Instrumentation, grid: RecyclerView, index: Int): DragInput {
        val bounds = cardBounds(activity, instrument, grid, index)
        val input = DragInput(instrument, PointF(bounds.exactCenterX(), bounds.exactCenterY()))
        try {
            check(ui(instrument) { field(activity, "modal") == null }) { "longPressOpenedPageMenu" }
            return input
        } catch (failure: Throwable) {
            try { input.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun cardBounds(activity: MainActivity, instrument: Instrumentation, grid: RecyclerView, index: Int): Rect =
        ui(instrument) { global(find(grid, "${translated(activity, "페이지")} ${index + 1}")) }
    private fun global(view: View) = Rect().also { check(view.getGlobalVisibleRect(it)) { "pageControlNotVisible" } }

    private fun navbar(activity: MainActivity, instrument: Instrumentation): ViewGroup =
        ui(instrument) { requireNotNull(field(activity, "editorNavbar") as? ViewGroup) }

    private fun click(activity: MainActivity, instrument: Instrumentation, root: ViewGroup,
                      label: String, translate: Boolean = true) {
        val target = ui(instrument) { find(root, if (translate) translated(activity, label) else label) }
        ui(instrument) { target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true) }
        instrument.waitForIdleSync()
        val bounds = visibleBounds(instrument, target)
        ui(instrument) { check(target.isEnabled && target.isClickable) { "pageControlNotClickable:${target.contentDescription}" } }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { instrument.sendPointerSync(event) } finally { event.recycle() }
        }
        instrument.waitForIdleSync()
    }

    private fun visibleBounds(instrument: Instrumentation, target: View): Rect {
        await("pageTargetLayout:${target.contentDescription}") { ui(instrument) { target.width > 0 && target.height > 0 } }
        return ui(instrument) {
            Rect().also { bounds ->
                check(target.isShown && target.getGlobalVisibleRect(bounds) &&
                    bounds.width() >= target.width - 1 && bounds.height() >= target.height - 1) {
                    "pageControlClipped:${target.contentDescription}:${target.width}x${target.height}:$bounds"
                }
            }
        }
    }

    private fun assertTarget(instrument: Instrumentation, target: View) {
        val bounds = visibleBounds(instrument, target)
        ui(instrument) {
            val minimum = (48 * target.resources.displayMetrics.density).toInt() - 1
            check(bounds.width() >= minimum && bounds.height() >= minimum) {
                "pageControlSmall:${target.contentDescription}:${target.width}x${target.height}:$bounds"
            }
            check(target.isFocusable) { "pageControlNotFocusable:${target.contentDescription}" }
        }
    }

    private fun capture(activity: MainActivity, instrument: Instrumentation, name: String, result: Bundle) {
        instrument.uiAutomation.waitForIdle(300, 5000)
        val image = requireNotNull(instrument.uiAutomation.takeScreenshot())
        val file = File(activity.filesDir, "page-management-${activity.resources.configuration.screenWidthDp}dp-$name.png")
        try { file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        result.putString("pageManagement${name.replaceFirstChar { it.uppercase() }}Screenshot", file.absolutePath)
    }

    private fun find(root: View, label: String): View = descendants(root).firstOrNull {
        it.isClickable && it.contentDescription?.toString() == label
    } ?: error("pageControlMissing:$label")

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun translated(activity: MainActivity, key: String): String =
        (field(activity, "dictionary") as JSONObject).optString(key, key)

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun awaitSaved(activity: MainActivity, instrument: Instrumentation) = await("pageManagementSaves") {
        ui(instrument) { field(activity, "saving") == false && (field(activity, "saves") as Collection<*>).isEmpty() }
    }

    private fun <T> ui(instrument: Instrumentation, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrument.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return value.get()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        error("Timeout:$label")
    }
}
