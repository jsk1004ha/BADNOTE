package com.inkforge.notesstudio

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only visible thumbnails are bound. A drag previews an insertion; persistence happens once, on drop. */
internal class PageThumbnailGrid(
    context: Context,
    private val ui: ClassicUi,
    private val pageIds: List<String>,
    private val metadata: List<JSONObject>,
    val selectedId: String?,
    private val thumbnail: (String) -> View,
    private val isCurrent: () -> Boolean,
    private val canDrag: () -> Boolean,
    private val finishContact: () -> Unit,
    private val navigate: (String) -> Unit,
    private val menu: (String, View) -> Unit,
    private val reorder: (List<String>) -> Unit
) : RecyclerView(context) {
    private class Session(val pageId: String, val native: Boolean)
    private val previewIds = pageIds.toMutableList()
    private val stableIds = pageIds.withIndex().associate { it.value to it.index.toLong() }
    private val metadataById = pageIds.zip(metadata).toMap()
    private var session: Session? = null
    private var disposed = false
    private var insertion = -1
    private var pointerX = 0f
    private var pointerY = 0f
    private var inside = false
    private var scrolling = false
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.accent
        strokeWidth = ui.dp(4).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val edgeScroll = object : Runnable {
        override fun run() {
            scrolling = false
            if (session == null || !inside || !isCurrent()) return
            val edge = ui.dp(64).toFloat().coerceAtMost(height / 3f)
            val velocity = when {
                pointerY < edge -> -((edge - pointerY) / edge).coerceIn(0f, 1f)
                pointerY > height - edge -> ((pointerY - height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            val dy = (velocity * ui.dp(18)).roundToInt()
            if (dy == 0 || !canScrollVertically(if (dy > 0) 1 else -1)) return
            scrollBy(0, dy)
            updateInsertion(pointerX, pointerY)
            scrolling = true
            postOnAnimation(this)
        }
    }

    init {
        layoutManager = GridLayoutManager(context, 1)
        setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(14))
        clipToPadding = false
        itemAnimator = DefaultItemAnimator().apply {
            moveDuration = 180L
            supportsChangeAnimations = false
        }
        contentDescription = ui.translate("페이지")
        adapter = object : Adapter<Holder>() {
            init { setHasStableIds(true) }
            override fun getItemCount() = previewIds.size
            override fun getItemId(position: Int) = stableIds.getValue(previewIds[position])
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder()
            override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)
            override fun onViewRecycled(holder: Holder) {
                holder.card.removeAllViews()
                holder.itemView.alpha = 1f
            }
            override fun onViewAttachedToWindow(holder: Holder) {
                holder.updateDragActions()
            }
        }
        addItemDecoration(object : ItemDecoration() {
            override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: State) {
                if (session == null || insertion < 0 || !inside) return
                val position = previewIds.indexOf(session?.pageId)
                val item = layoutManager?.findViewByPosition(position) ?: return
                markerPaint.style = Paint.Style.STROKE
                canvas.drawRoundRect(item.left + item.paddingLeft.toFloat(), item.top + item.paddingTop.toFloat(),
                    item.right - item.paddingRight.toFloat(), item.bottom - item.paddingBottom - ui.dp(48).toFloat(),
                    ui.dp(8).toFloat(), ui.dp(8).toFloat(), markerPaint)
            }
        })
        setOnDragListener { _, event -> receiveDrag(event) }
    }

    private inner class Holder : ViewHolder(ui.col().apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
    }) {
        val card = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = (MeasureSpec.getSize(widthMeasureSpec) * 1.414f).roundToInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }.apply {
            setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
            clipToOutline = true
            elevation = ui.dp(3).toFloat()
            isFocusable = true
        }
        private val number: TextView = ui.text("", 12f, ui.muted, true)
        private val more = ui.iconButton("more", "페이지 메뉴", ui.muted, 48, 48, 20) { openMenu() }
        private var dropBeforeAction = View.NO_ID
        private var dropAfterAction = View.NO_ID
        private var cancelAction = View.NO_ID

        init {
            val item = itemView as LinearLayout
            item.addView(card, LinearLayout.LayoutParams(-1, -2))
            val label = ui.row()
            number.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            label.addView(number, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            label.addView(more)
            item.addView(label)
            card.setOnClickListener { boundId()?.takeIf { session == null && isCurrent() }?.let(navigate) }
            if (pageIds.size > 1) {
                card.setOnLongClickListener { startDrag(this, false) }
                ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_LONG_CLICK,
                    ui.translate("페이지 이동 시작")) { _, _ -> startDrag(this, true) }
                if (Build.VERSION.SDK_INT >= 32) {
                    ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_DRAG_START,
                        ui.translate("페이지 이동 시작")) { _, _ -> startDrag(this, true) }
                }
            }
        }

        private fun boundId() = bindingAdapterPosition.takeIf { it != NO_POSITION }?.let(previewIds::getOrNull)
        private fun openMenu() {
            boundId()?.takeIf { session == null && isCurrent() }?.let { menu(it, more) }
        }

        fun bind(position: Int) {
            val id = previewIds[position]
            card.background = ui.rounded(Color.WHITE, 8f,
                if (id == selectedId) ui.accent else ui.color("#d7dbe1"), if (id == selectedId) 2f else 1f)
            card.contentDescription = "${ui.translate("페이지")} ${position + 1}"
            card.isSelected = id == selectedId
            card.removeAllViews()
            card.addView(thumbnail(id), FrameLayout.LayoutParams(-1, -1))
            if (metadataById[id]?.optBoolean("bookmarked") == true)
                card.addView(ui.icon("bookmark", ui.accent, 20),
                    FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP or Gravity.RIGHT))
            number.text = "${position + 1}"
            more.contentDescription = "${ui.translate("페이지 메뉴")} ${position + 1}"
            ViewCompat.setTooltipText(more, more.contentDescription)
            updateDragActions()
        }

        fun updateDragActions() {
            itemView.alpha = if (boundId() == session?.pageId) .15f else 1f
            val position = bindingAdapterPosition
            if (position != NO_POSITION) {
                number.text = "${position + 1}"
                card.contentDescription = "${ui.translate("페이지")} ${position + 1}"
                more.contentDescription = "${ui.translate("페이지 메뉴")} ${position + 1}"
                ViewCompat.setTooltipText(more, more.contentDescription)
            }
            for (action in intArrayOf(dropBeforeAction, dropAfterAction, cancelAction))
                if (action != View.NO_ID) ViewCompat.removeAccessibilityAction(card, action)
            dropBeforeAction = View.NO_ID; dropAfterAction = View.NO_ID; cancelAction = View.NO_ID
            if (Build.VERSION.SDK_INT >= 32) {
                ViewCompat.removeAccessibilityAction(card, AccessibilityActionCompat.ACTION_DRAG_DROP.id)
                ViewCompat.removeAccessibilityAction(card, AccessibilityActionCompat.ACTION_DRAG_CANCEL.id)
            }
            if (session == null) return
            if (Build.VERSION.SDK_INT < 32) {
                dropBeforeAction = ViewCompat.addAccessibilityAction(card, ui.translate("이 페이지 앞에 놓기")) { _, _ ->
                    dropAt(bindingAdapterPosition)
                }
                cancelAction = ViewCompat.addAccessibilityAction(card, ui.translate("페이지 이동 취소")) { _, _ ->
                    cancelDrag(); true
                }
            }
            dropAfterAction = ViewCompat.addAccessibilityAction(card, ui.translate("이 페이지 뒤에 놓기")) { _, _ ->
                val position = bindingAdapterPosition
                if (position == NO_POSITION) false else dropAt(position + 1)
            }
            if (Build.VERSION.SDK_INT >= 32) {
                ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_DRAG_DROP,
                    ui.translate("이 페이지 앞에 놓기")) { _, _ -> dropAt(bindingAdapterPosition) }
                ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_DRAG_CANCEL,
                    ui.translate("페이지 이동 취소")) { _, _ -> cancelDrag(); true }
            }
        }
    }

    private fun startDrag(holder: Holder, accessible: Boolean): Boolean {
        val position = holder.bindingAdapterPosition
        if (disposed || session != null || position == NO_POSITION || pageIds.size < 2 || !isCurrent() || !canDrag())
            return false
        finishContact()
        // Before native accessibility drag support, use the same session/drop semantics without trapping TalkBack in a pointer drag.
        val drag = Session(previewIds[position], !accessible || Build.VERSION.SDK_INT >= 32)
        session = drag
        val flags = if (accessible && Build.VERSION.SDK_INT >= 32) View.DRAG_FLAG_ACCESSIBILITY_ACTION else 0
        if (drag.native && !ViewCompat.startDragAndDrop(holder.card, ClipData.newPlainText("page", drag.pageId),
                View.DragShadowBuilder(holder.card), drag, flags)) {
            session = null
            return false
        }
        updateVisibleDragState()
        return true
    }

    private fun receiveDrag(event: DragEvent): Boolean {
        val drag = session ?: return false
        if (event.localState !== drag || disposed) return false
        if (!isCurrent()) { cancelDrag(); return false }
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                inside = event.x >= 0 && event.x < width && event.y >= 0 && event.y < height
                pointerX = event.x; pointerY = event.y
                if (inside) {
                    updateInsertion(pointerX, pointerY)
                    if (!scrolling) { scrolling = true; postOnAnimation(edgeScroll) }
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> clearFeedback()
            DragEvent.ACTION_DROP -> {
                if (event.x < 0 || event.x >= width || event.y < 0 || event.y >= height) return false
                updateInsertion(event.x, event.y)
                return dropAt(insertion, cancelNative = false)
            }
            DragEvent.ACTION_DRAG_ENDED -> cancelDrag(false)
        }
        return true
    }

    private fun updateInsertion(x: Float, y: Float) {
        var nearest: View? = null
        var distance = Float.MAX_VALUE
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val d = abs(y - (child.top + child.bottom) / 2f)
            if (d < distance) { distance = d; nearest = child }
        }
        // Hit-test layout slots, not animated translations or pending adapter moves:
        // a stationary pointer must not chase a thumbnail as it slides out of the way.
        val item = nearest ?: return
        val position = getChildLayoutPosition(item).takeIf { it != NO_POSITION } ?: return
        insertion = (position + if (y >= (item.top + item.bottom) / 2f) 1 else 0)
            .coerceIn(0, previewIds.size)
        previewAt(insertion)
        invalidate()
    }

    private fun previewAt(boundary: Int) {
        val source = previewIds.indexOf(session?.pageId)
        if (source < 0) return
        val target = (boundary - if (source < boundary) 1 else 0).coerceIn(0, previewIds.lastIndex)
        movePreview(source, target)
    }

    private fun movePreview(source: Int, target: Int) {
        if (source == target) return
        val manager = layoutManager as GridLayoutManager
        val first = manager.findFirstVisibleItemPosition()
        val offset = manager.findViewByPosition(first)?.let { manager.getDecoratedTop(it) - paddingTop }
        previewIds.add(target, previewIds.removeAt(source))
        // Stable page identity keeps the existing thumbnail; RecyclerView animates
        // every intervening page into its next slot, including across grid rows.
        adapter?.notifyItemMoved(source, target)
        // Keep the visible slot in place. Anchoring to the moved page instead would
        // shift the scroll offset and change the insertion target under a still pointer.
        if (first != NO_POSITION && offset != null) manager.scrollToPositionWithOffset(first, offset)
        updateVisibleDragState()
        postOnAnimation { if (!disposed) updateVisibleDragState() }
    }

    private fun dropAt(boundary: Int, cancelNative: Boolean = true): Boolean {
        val drag = session ?: return false
        if (disposed || !isCurrent() || boundary !in 0..pageIds.size) { cancelDrag(); return false }
        val source = previewIds.indexOf(drag.pageId)
        if (source < 0) { cancelDrag(); return false }
        previewAt(boundary)
        val order = previewIds.toList()
        finishDrag(cancelNative, restore = false)
        if (order != pageIds) reorder(order)
        return true
    }

    private fun updateVisibleDragState() {
        for (index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as? Holder)?.updateDragActions()
    }

    private fun clearFeedback(restore: Boolean = true) {
        if (restore) session?.let { drag ->
            val source = previewIds.indexOf(drag.pageId)
            val target = pageIds.indexOf(drag.pageId)
            if (source >= 0 && target >= 0) movePreview(source, target)
        }
        inside = false; insertion = -1; scrolling = false
        removeCallbacks(edgeScroll)
        invalidate()
    }

    fun cancelDrag(cancelNative: Boolean = true) {
        finishDrag(cancelNative, restore = true)
    }

    private fun finishDrag(cancelNative: Boolean, restore: Boolean) {
        val drag = session
        clearFeedback(restore)
        session = null
        // The starting thumbnail can be recycled while scrolling. End the platform
        // drag through the attached list so a detached source cannot leave it active.
        if (cancelNative && drag != null && drag.native && Build.VERSION.SDK_INT >= 24) cancelDragAndDrop()
        updateVisibleDragState()
    }

    fun dispose() {
        disposed = true
        cancelDrag()
        adapter = null
    }

    override fun onDetachedFromWindow() {
        cancelDrag()
        super.onDetachedFromWindow()
    }
}
