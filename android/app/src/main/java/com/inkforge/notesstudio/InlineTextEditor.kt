package com.inkforge.notesstudio

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.*
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Native IME editing above the page, with a single document undo entry per editing session. */
internal class InlineTextEditor(
    private val activity: Activity,
    private val host: FrameLayout,
    private val canvas: InkCanvasView,
    private val ui: ClassicUi,
    private val defaults: () -> JSONObject,
    private val saveDefault: (JSONObject) -> Unit,
    private val copyObject: (JSONObject) -> Unit,
    private val changed: () -> Unit
) {
    private var frame: TextFrame? = null
    private var input: EditText? = null
    private var menu: HorizontalScrollView? = null
    private var popup: PopupWindow? = null
    private var pageId: String? = null
    private var original: JSONObject? = null
    private var draft: JSONObject? = null
    private var applying = false
    private var closing = false
    private var editing = false
    private var modified = false
    private val cursorFormats=LinkedHashMap<String,Any?>()
    private var formatAnchor=-1
    private var inputScale = 1f
    var pinned = false; private set
    val active get() = draft != null
    internal val editingObject get() = draft?.copyJson()
    private val density get() = ui.density
    private fun dp(value: Int) = ui.dp(value)
    private val blue = Color.rgb(36, 128, 235)
    private val ime get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var toolStyle: JSONObject = defaults().copyJson()

    init {
        host.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r-l!=or-ol || b-t!=ob-ot) host.post {
                if (active && !dragging) { if (editing) ensureCaret(); reposition() }
            }
        }
    }

    fun newBox(x: Float, y: Float, fullPage: Boolean = false) {
        if (canvas.readOnly) return
        finish()
        val page = canvas.currentPage ?: return
        val left = if (fullPage) 48f else x.coerceIn(8f, max(8f, page.width-80f))
        val top = if (fullPage) 48f else y.coerceIn(8f, max(8f, page.height-48f))
        val obj = toolStyle.copyJson().apply {
            put("id", uid("text")); put("type", "text"); put("text", ""); put("textLayoutVersion", 1)
            put("x", left); put("y", top); put("w", if (fullPage) page.width-96f else min(360f, page.width-left-8f))
            put("fontSize", f("fontSize", 28f)); put("color", optString("color", "#172033"))
            put("fullPageText", fullPage); put("h", TextBoxLayout.height(this))
        }
        open(page.id, null, obj, true)
    }

    fun select(obj: JSONObject) {
        if (canvas.readOnly || obj.optString("type")!="text") return
        if (draft?.optString("id")==obj.optString("id")) { beginTyping(); return }
        finish()
        val page = canvas.currentPage ?: return
        open(page.id, obj.copyJson(), obj.copyJson(), false)
    }

    // The editor uses the platform Activity theme and native IME/selection behavior throughout.
    @android.annotation.SuppressLint("AppCompatCustomView")
    private fun open(id: String, before: JSONObject?, value: JSONObject, typeNow: Boolean) {
        pageId=id; original=before; draft=value.put("textLayoutVersion",1); editing=false; modified=false;cursorFormats.clear();formatAnchor=-1
        canvas.hideEditingText(before?.optString("id"))
        val box=TextFrame(); frame=box
        val editor=object : EditText(activity) {
            override fun onSelectionChanged(selStart:Int,selEnd:Int) {
                super.onSelectionChanged(selStart,selEnd)
                if(!applying) post { if(input===this)showMenu() }
            }
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if(keyCode==KeyEvent.KEYCODE_BACK && event.action==KeyEvent.ACTION_UP && editing) {
                    stopTyping(); return true
                }
                return super.onKeyPreIme(keyCode,event)
            }
        }.apply {
            contentDescription=ui.translate("페이지 텍스트 입력")
            setSingleLine(false)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            gravity=Gravity.TOP or Gravity.LEFT; includeFontPadding=true
            if(android.os.Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=true
            setPadding(0,0,0,0); background=null; minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0
            breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE; hyphenationFrequency=Layout.HYPHENATION_FREQUENCY_NONE
            isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false
            setHorizontallyScrolling(false)
            val nativeActions=object:ActionMode.Callback {
                override fun onCreateActionMode(mode:ActionMode?,menu:Menu?):Boolean {menu?.clear();return true}
                override fun onPrepareActionMode(mode:ActionMode?,menu:Menu?):Boolean {menu?.clear();return true}
                override fun onActionItemClicked(mode:ActionMode?,item:MenuItem?)=false
                override fun onDestroyActionMode(mode:ActionMode?)=Unit
            }
            customSelectionActionModeCallback=nativeActions;customInsertionActionModeCallback=nativeActions
            setOnFocusChangeListener { _, _ -> box.invalidate() }
            addTextChangedListener(object : TextWatcher {
                private var insertedAt=-1
                private var formatStart=-1
                private var formatEnd=-1
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    insertedAt=if(!applying && before==0 && count==1 && s?.get(start)=='\n')start else -1
                    formatStart=-1;formatEnd=-1
                    if(!applying&&cursorFormats.isNotEmpty()) {
                        if(start<=formatAnchor&&start+before>=formatAnchor) {
                            formatStart=start;formatEnd=start+count;formatAnchor=formatEnd
                        } else { cursorFormats.clear();formatAnchor=-1 }
                    }
                }
                override fun afterTextChanged(s: Editable?) {
                    if (!applying) {
                        if(s!=null && formatStart>=0 && formatEnd>formatStart) {
                            for((kind,value) in cursorFormats)TextBoxLayout.span(json("kind" to kind,"value" to value),draft?:JSONObject())?.let {
                                s.setSpan(it,formatStart,formatEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        if(s!=null&&insertedAt>=0)continueList(s,insertedAt)
                        insertedAt=-1;modified=true; capture(); updateHeight(); reposition(); box.invalidate()
                        post { ensureCaret() }
                    }
                }
            })
        }
        input=editor; box.addView(editor)
        host.addView(box,FrameLayout.LayoutParams(1,1)); box.elevation=dp(9).toFloat()
        applyText(true); showMenu(); reposition()
        if(typeNow)beginTyping() else setEditable(false)
        changed()
    }

    private fun setEditable(value: Boolean) {
        val editor=input?:return
        editing=value && draft?.optBoolean("locked")!=true
        editor.isFocusable=editing; editor.isFocusableInTouchMode=editing
        editor.isCursorVisible=editing; editor.isLongClickable=editing
        if(!editing)editor.clearFocus()
        frame?.invalidate()
        showMenu()
    }
    fun beginTyping() {
        val obj=draft?:return
        if(obj.optBoolean("locked"))return
        setEditable(true)
        input?.let { editor ->
            editor.requestFocus(); editor.setSelection(editor.text.length)
            editor.post { if(input===editor && editing) { ime.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT)
                canvas.ensureTextVisible(obj,dp(92).toFloat()); reposition() } }
        }
    }
    fun stopTyping() {
        capture(); setEditable(false)
        input?.let { ime.hideSoftInputFromWindow(it.windowToken,0) }
        showMenu()
    }
    fun onToolChanged() {
        if(canvas.tool!=InkCanvasView.Tool.TEXT) { pinned=false; popup?.dismiss(); finish() }
    }
    fun onOutsideTouch(event: MotionEvent) {
        if(!active || event.actionMasked!=MotionEvent.ACTION_DOWN || popup!=null)return
        fun inside(view: View?): Boolean {
            if(view==null)return false
            val rect=Rect(); return view.getGlobalVisibleRect(rect) && rect.contains(event.rawX.toInt(),event.rawY.toInt())
        }
        if(!inside(frame) && !inside(menu)) finish(returnToLasso=inside(canvas))
    }
    fun finish(cancel: Boolean = false, returnToLasso: Boolean = false) {
        if(closing || !active)return
        closing=true
        try {
            capture()
            val before=original; val value=draft; val id=pageId
            popup?.dismiss(); popup=null
            input?.let { ime.hideSoftInputFromWindow(it.windowToken,0) }
            frame?.let(host::removeView); menu?.let(host::removeView)
            frame=null; input=null; menu=null; draft=null; original=null; pageId=null; editing=false;menuMode="";dragging=false
            canvas.hideEditingText(null)
            if(!cancel && id!=null && value!=null && (before==null || modified)) {
                val after=value.takeUnless { it.optString("text").isBlank() }
                canvas.commitText(id,before,after)
            }
            if(returnToLasso && !pinned && canvas.tool==InkCanvasView.Tool.TEXT)canvas.tool=InkCanvasView.Tool.LASSO
            changed()
        } finally { closing=false }
    }
    private fun capture() {
        val obj=draft?:return; val editor=input?:return
        obj.put("text",editor.text.toString()).put("textRuns",TextBoxLayout.runs(editor.text,inputScale,obj.f("fontSize",28f)))
    }
    private fun updateHeight() {
        val obj=draft?:return
        obj.put("h",TextBoxLayout.height(obj).coerceAtLeast(24f))
    }
    private fun ensureCaret() {
        if(!editing||dragging)return
        val obj=draft?:return;val editor=input?:return;val layout=editor.layout?:return
        val line=layout.getLineForOffset(editor.selectionEnd.coerceIn(0,editor.length()))
        canvas.ensureTextCursorVisible(obj.f("x")+obj.f("textPadding"),
            obj.f("y")+obj.f("textPadding")+layout.getLineBottom(line)/canvas.scale,dp(92).toFloat())
    }
    private fun applyText(replace: Boolean) {
        val obj=draft?:return; val editor=input?:return
        applying=true
        try {
            val start=editor.selectionStart; val end=editor.selectionEnd
            inputScale=canvas.scale
            editor.setTextSize(TypedValue.COMPLEX_UNIT_PX,obj.f("fontSize",28f)*inputScale)
            editor.setTextColor(TextBoxLayout.color(obj.optString("color","#172033")))
            editor.typeface=TextBoxLayout.typeface(obj)
            editor.paintFlags=editor.paintFlags.let {
                (it and Paint.UNDERLINE_TEXT_FLAG.inv() and Paint.STRIKE_THRU_TEXT_FLAG.inv()) or
                    (if(obj.optBoolean("underline"))Paint.UNDERLINE_TEXT_FLAG else 0) or
                    (if(obj.optBoolean("strike"))Paint.STRIKE_THRU_TEXT_FLAG else 0)
            }
            editor.gravity=Gravity.TOP or when(obj.optString("alignment")) {
                "center" -> Gravity.CENTER_HORIZONTAL; "right" -> Gravity.RIGHT; else -> Gravity.LEFT
            }
            editor.setLineSpacing(0f,obj.f("lineSpacing",1.2f))
            if(replace) {
                editor.setText(TextBoxLayout.styled(obj,inputScale),TextView.BufferType.EDITABLE)
                if(start>=0)editor.setSelection(start.coerceAtMost(editor.length()),end.coerceIn(0,editor.length()))
            }
            updateHeight()
        } finally { applying=false }
    }
    fun reposition() {
        val obj=draft?:return; val box=frame?:return; val editor=input?:return
        if(abs(inputScale-canvas.scale)>.0001f) { capture(); applyText(true) }
        val scale=canvas.scale; val point=canvas.screenPoint(obj.f("x"),obj.f("y"))
        val gutter=dp(32); val w=ceil(obj.f("w",360f)*scale).toInt().coerceAtLeast(8)
        val h=ceil(obj.f("h",40f)*scale).toInt().coerceAtLeast(8)
        val lp=FrameLayout.LayoutParams(w+gutter*2,h+gutter*2).apply {
            leftMargin=point.x.roundToInt()-gutter; topMargin=point.y.roundToInt()-gutter
        }
        val old=box.layoutParams as? FrameLayout.LayoutParams
        if(old==null || old.width!=lp.width || old.height!=lp.height || old.leftMargin!=lp.leftMargin || old.topMargin!=lp.topMargin)box.layoutParams=lp
        box.rotation=Math.toDegrees(obj.f("rotation").toDouble()).toFloat()
        val padding=(obj.f("textPadding")*scale).roundToInt()
        val elp=FrameLayout.LayoutParams((w-padding*2).coerceAtLeast(1),(h-padding*2).coerceAtLeast(1)).apply {
            leftMargin=gutter+padding; topMargin=gutter+padding
        }
        val eo=editor.layoutParams as? FrameLayout.LayoutParams
        if(eo==null || eo.width!=elp.width || eo.height!=elp.height || eo.leftMargin!=elp.leftMargin || eo.topMargin!=elp.topMargin)editor.layoutParams=elp
        box.invalidate()
        menu?.let { bar ->
            if(dragging){bar.visibility=View.INVISIBLE;return@let}
            bar.visibility=View.VISIBLE
            val bounds=InkGeometry.bounds(obj);val anchor=canvas.screenPoint(bounds.left,bounds.top)
            val end=canvas.screenPoint(bounds.right,bounds.bottom)
            val visible=Rect();host.getWindowVisibleDisplayFrame(visible)
            val hostAt=IntArray(2);host.getLocationOnScreen(hostAt)
            val bottom=min(host.height,visible.bottom-hostAt[1]);val topLimit=max(dp(4),visible.top-hostAt[1]+dp(4))
            val available=max(dp(48),host.width-dp(16))
            bar.measure(View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(dp(48),View.MeasureSpec.EXACTLY))
            val width=bar.measuredWidth.coerceAtMost(available)
            val centre=((anchor.x+end.x)/2).coerceIn(0f,host.width.toFloat())
            val left=(centre-width/2).roundToInt().coerceIn(dp(8),max(dp(8),host.width-width-dp(8)))
            val above=anchor.y.roundToInt()-dp(if(editing)70 else 86)
            val below=end.y.roundToInt()+dp(18)
            val top=when {
                above>=topLimit->above
                below+dp(48)<=bottom-dp(8)->below
                else->topLimit
            }.coerceIn(topLimit,max(topLimit,bottom-dp(56)))
            val oldMenu=bar.layoutParams as? FrameLayout.LayoutParams
            if(oldMenu==null||oldMenu.width!=width||oldMenu.leftMargin!=left||oldMenu.topMargin!=top)bar.layoutParams=FrameLayout.LayoutParams(width,dp(48)).apply{leftMargin=left;topMargin=top}
        }
        host.removeCallbacks(updatePopover);if(popup!=null)host.post(updatePopover)
    }

    private val panelInk = Color.rgb(35,35,38)
    private val panelFill = 0xfff2f2f7.toInt()
    private var menuMode=""
    private var defaultToolbar:LinearLayout?=null
    private var dragging=false
    private var popupAnchor:View?=null
    private var popupColumn:LinearLayout?=null
    private var popupWidth=0
    private var popupFooter:View?=null
    private val updatePopover=Runnable{positionPopup()}
    private fun positionPopup() {
        val window=popup?:return;val anchor=popupAnchor?:return;val column=popupColumn?:return
        if(!window.isShowing)return
        if(!anchor.isAttachedToWindow){window.dismiss();return}
        val visible=Rect();host.getWindowVisibleDisplayFrame(visible)
        val at=IntArray(2);anchor.getLocationOnScreen(at)
        val width=popupWidth
        column.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val contentHeight=column.measuredHeight+(if(popupFooter!=null)dp(50)else 0)
        val wanted=contentHeight+dp(9)
        val belowRoom=visible.bottom-at[1]-anchor.height-dp(10);val aboveRoom=at[1]-visible.top-dp(10)
        val rightRoom=visible.right-at[0]-anchor.width-dp(10);val leftRoom=at[0]-visible.left-dp(10)
        val body=window.contentView as? TextPopover
        if(wanted>max(aboveRoom,belowRoom)&&max(rightRoom,leftRoom)>=width+dp(9)) {
            val right=rightRoom>=leftRoom
            val height=min(contentHeight,visible.height()-dp(16)).coerceAtLeast(dp(86))
            val x=if(right)at[0]+anchor.width+dp(2)else at[0]-width-dp(11)
            val y=(at[1]+anchor.height/2-height/2).coerceIn(visible.top+dp(8),max(visible.top+dp(8),visible.bottom-height-dp(8)))
            body?.place(if(right)"left" else "right",at[1]+anchor.height/2f-y)
            window.update(x,y,width+dp(9),height)
        } else {
            val below=wanted<=belowRoom||belowRoom>=aboveRoom
            val height=min(wanted,max(dp(86),if(below)belowRoom else aboveRoom))
            val x=(at[0]+anchor.width/2-width/2).coerceIn(visible.left+dp(8),max(visible.left+dp(8),visible.right-width-dp(8)))
            val y=(if(below)at[1]+anchor.height+dp(2)else at[1]-height-dp(2)).coerceIn(visible.top+dp(8),max(visible.top+dp(8),visible.bottom-height-dp(8)))
            body?.place(if(below)"top" else "bottom",at[0]+anchor.width/2f-x)
            window.update(x,y,width,height)
        }
    }

    private fun card(anchor:View,title:String="",content:LinearLayout.()->Unit) {
        popup?.dismiss()
        popupAnchor=anchor
        val column=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(12))
            if(title.isNotEmpty())addView(ui.text(title,15f,panelInk,true).apply {
                gravity=Gravity.CENTER;setPadding(dp(4),dp(4),dp(4),dp(13))
            })
            content()
        }
        val footer=column.getChildAt(column.childCount-1)?.takeIf{it.tag=="text-panel-tabs"}
        if(footer!=null)column.removeView(footer)
        popupColumn=column;popupFooter=footer
        val width=min(dp(296),host.width-dp(20)).coerceAtLeast(dp(180))
        popupWidth=width
        val visible=Rect();host.getWindowVisibleDisplayFrame(visible)
        column.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val location=IntArray(2);anchor.getLocationOnScreen(location)
        val below=location[1]+anchor.height+dp(2)
        val belowRoom=visible.bottom-below-dp(8)
        val aboveRoom=location[1]-visible.top-dp(10)
        val wanted=column.measuredHeight+dp(9)+(if(footer!=null)dp(50)else 0)
        val useBelow=wanted<=belowRoom || belowRoom>=aboveRoom
        val height=min(wanted,max(dp(86),if(useBelow)belowRoom else aboveRoom))
        val x=(location[0]+anchor.width/2-width/2).coerceIn(visible.left+dp(8),max(visible.left+dp(8),visible.right-width-dp(8)))
        val y=(if(useBelow)below else location[1]-height-dp(2))
            .coerceIn(visible.top+dp(8),max(visible.top+dp(8),visible.bottom-height-dp(8)))
        val body=TextPopover(activity,useBelow,location[0]+anchor.width/2f-x).apply {
            addView(LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL
                addView(ScrollView(activity).apply{isFillViewport=true;isVerticalScrollBarEnabled=true;isScrollbarFadingEnabled=false;addView(column)},LinearLayout.LayoutParams(-1,0,1f))
                if(footer!=null)addView(footer,LinearLayout.LayoutParams(-1,dp(36)).apply{setMargins(dp(12),dp(2),dp(12),dp(12))})
            },FrameLayout.LayoutParams(-1,-1))
        }
        val window=PopupWindow(body,width,height,false).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(14).toFloat()
            isOutsideTouchable=true;inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        window.setOnDismissListener { if(popup===window) { popup=null;popupAnchor=null;popupColumn=null;popupFooter=null;refreshMenu() } }
        popup=window;window.showAtLocation(host,Gravity.TOP or Gravity.LEFT,x,y)
        positionPopup();refreshMenu()
    }
    private fun LinearLayout.group(content:LinearLayout.()->Unit):LinearLayout {
        val group=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;background=ui.rounded(Color.WHITE,10f);content() }
        addView(group,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        return group
    }
    private fun LinearLayout.menuRow(label:String,symbol:String?=null,checked:Boolean=false,chevron:Boolean=false,
                                     color:Int=panelInk,close:Boolean=true,action:()->Unit):LinearLayout {
        if(childCount>0)addView(View(activity).apply{setBackgroundColor(0xffe9e9ed.toInt())},LinearLayout.LayoutParams(-1,dp(1)).apply{leftMargin=dp(14)})
        val row=ui.row().apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(12),0);minimumHeight=dp(44)
            contentDescription=ui.translate(label);isClickable=true;isFocusable=true
            setOnClickListener{if(close)popup?.dismiss();action()}
        }
        row.addView(ui.text(label,15f,color).apply{gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(44),1f))
        val icon=if(checked)"check" else if(chevron)"chevron" else symbol
        if(icon!=null)row.addView(TextIcon(activity,icon).apply{ink=if(checked)blue else color;isClickable=false;isFocusable=false},LinearLayout.LayoutParams(dp(21),dp(24)))
        addView(row,LinearLayout.LayoutParams(-1,-2));return row
    }
    private fun LinearLayout.segments(values:List<Triple<String,String,Boolean>>,action:(Int)->Unit) {
        val row=ui.row().apply { gravity=Gravity.CENTER;setPadding(dp(3),dp(3),dp(3),dp(3));background=ui.rounded(0xffe4e4e9.toInt(),9f) }
        values.forEachIndexed { index,(label,symbol,selected)->
            row.addView(TextIcon(activity,symbol).apply {
                contentDescription=ui.translate(label);ink=if(selected)blue else panelInk
                background=ui.rounded(if(selected)Color.WHITE else Color.TRANSPARENT,7f)
                setOnClickListener{action(index)}
            },LinearLayout.LayoutParams(0,dp(38),1f).apply{leftMargin=dp(1);rightMargin=dp(1)})
        }
        addView(row,LinearLayout.LayoutParams(-1,dp(44)).apply{bottomMargin=dp(10)})
    }
    private fun LinearLayout.tabs(labels:List<String>,selected:Int,action:(Int)->Unit) {
        val row=ui.row().apply { setPadding(dp(3),dp(3),dp(3),dp(3));background=ui.rounded(0xffdedee5.toInt(),8f) }
        labels.forEachIndexed { index,label->row.addView(ui.text(label,13f,panelInk,index==selected).apply {
            gravity=Gravity.CENTER;contentDescription=ui.translate(label);background=ui.rounded(if(index==selected)Color.WHITE else Color.TRANSPARENT,6f)
            isClickable=true;setOnClickListener{action(index)}
        },LinearLayout.LayoutParams(0,dp(30),1f)) }
        row.tag="text-panel-tabs"
        addView(row,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(8)})
    }
    private fun LinearLayout.stepper(label:String,initial:Float,min:Float,max:Float,step:Float=1f,read:(()->Float)?=null,update:(Float)->Unit) {
        var value=initial.coerceIn(min,max)
        val row=ui.row().apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(4),dp(5),dp(4));background=ui.rounded(Color.WHITE,10f) }
        val output=ui.text("${number(value)} pt  $label",14f,panelInk).apply{contentDescription=ui.translate("$label 값")}
        row.addView(output,LinearLayout.LayoutParams(0,dp(38),1f))
        listOf("−" to -step,"+" to step).forEach { (glyph,delta)->
            row.addView(ui.text(glyph,23f,blue).apply {
                gravity=Gravity.CENTER;contentDescription=ui.translate("$label ${if(delta<0)"줄이기" else "늘리기"}");isClickable=true
                setOnClickListener{value=((read?.invoke()?:value)+delta).coerceIn(min,max);output.text="${number(value)} pt  $label";update(value)}
            },LinearLayout.LayoutParams(dp(38),dp(38)))
        }
        addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
    }
    private fun number(value:Float)=if(abs(value-value.roundToInt())<.05f)"${value.roundToInt()}" else String.format(java.util.Locale.US,"%.1f",value)
    private val textColors=listOf("검정" to "#172033","회색" to "#73737b","흰색" to "#ffffff","빨강" to "#d93939",
        "주황" to "#e67b2e","노랑" to "#ffe066","초록" to "#2a9d66","파랑" to "#147bd1","보라" to "#7a57c7","분홍" to "#df80ad")
    private fun colorOptions(anchor:View,highlight:Boolean=false,propertyKey:String?=null,custom:Boolean=false) {
        val key=propertyKey?:if(highlight)"highlight" else "color"
        val style=cursorStyle();val selected=style.optString(key,if(key=="color")"#172033" else "#00000000")
        val title=if(propertyKey!=null)"배경 색상" else if(highlight)"텍스트 강조" else "텍스트 색상"
        fun choose(value:String) { if(propertyKey!=null)property(propertyKey,value)else format(key,value) }
        card(anchor,title) {
            if(!custom) {
                textColors.chunked(5).forEach { colors->addView(ui.row().apply {
                    gravity=Gravity.CENTER
                    colors.forEach{(label,value)->addView(TextIcon(activity,"swatch").apply {
                        isSelected=selected.equals(value,true)
                        swatch=TextBoxLayout.color(value);contentDescription=ui.translate(label)
                        background=ui.rounded(if(selected.equals(value,true))0xffd4e7ff.toInt() else Color.TRANSPARENT,10f)
                        setOnClickListener{choose(value);popup?.dismiss()}
                    },LinearLayout.LayoutParams(0,dp(48),1f))}
                },LinearLayout.LayoutParams(-1,dp(50))) }
            } else {
                val hex=EditText(activity).apply {
                    contentDescription=ui.translate("HEX 색상");setSingleLine();setText(selected.removePrefix("#").takeLast(6).uppercase())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP,15f);setTextColor(panelInk);background=ui.rounded(Color.WHITE,8f)
                    setPadding(dp(12),0,dp(12),0);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    imeOptions=EditorInfo.IME_ACTION_DONE
                    setOnTouchListener { _,event->if(event.actionMasked==MotionEvent.ACTION_DOWN) { popup?.let{it.isFocusable=true;it.inputMethodMode=PopupWindow.INPUT_METHOD_NEEDED;it.update()} };false }
                    setOnEditorActionListener { _,action,_->if(action==EditorInfo.IME_ACTION_DONE){
                        val value="#${text.toString().trim().removePrefix("#")}"
                        if(Regex("#[0-9a-fA-F]{6}").matches(value)){choose(value);popup?.dismiss();input?.requestFocus();true}else{error="6자리 HEX 색상";true}
                    }else false }
                }
                addView(TextColorWheel(activity,TextBoxLayout.color(selected)){value->
                    hex.setText(value.removePrefix("#"));choose(value)
                },LinearLayout.LayoutParams(-1,dp(180)))
                addView(hex,LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(8)})
            }
            if(highlight||propertyKey!=null)group{menuRow("색상 없음",close=true){choose("#00000000")}}
            tabs(listOf("프리셋","사용자 지정"),if(custom)1 else 0){colorOptions(anchor,highlight,propertyKey,it==1)}
        }
    }
    private fun currentStyle()=draft?:toolStyle
    private fun cursorStyle():JSONObject {
        val style=currentStyle().copyJson();val editor=input?:return style
        if(!editing)return style
        val at=editor.selectionStart.coerceAtLeast(0)
        fun set(kind:String,value:Any?) { when(kind) {
            "bold"->style.put("fontWeight",if(value==true)700 else 400)
            "font"->style.put("fontFamily",value)
            "size"->style.put("fontSize",value)
            else->style.put(kind,value)
        } }
        val runs=TextBoxLayout.runs(editor.text,inputScale,style.f("fontSize",28f))
        val probe=if(editor.selectionStart==editor.selectionEnd)(at-1).coerceAtLeast(0)else at
        for(i in 0 until runs.length()) { val run=runs.getJSONObject(i)
            if(probe>=run.optInt("start")&&probe<run.optInt("end"))set(run.optString("kind"),run.opt("value")?:true)
        }
        if(at==formatAnchor)for((kind,value)in cursorFormats)set(kind,value)
        return style
    }
    private fun fontName(style:JSONObject)=when(style.optString("fontFamily","sans-serif")) {
        "serif"->"Classic";"monospace"->"Technical";else->"Modern"
    }
    private fun sizeOptions(anchor:View) {
        val size=cursorStyle().f("fontSize",28f)
        card(anchor,"글자 크기") {
            val value=ui.text("${number(size)} pt",25f,panelInk).apply{gravity=Gravity.CENTER;contentDescription=ui.translate("글자 크기 표시")}
            addView(value,LinearLayout.LayoutParams(-1,dp(42)))
            val seek=SeekBar(activity).apply {
                contentDescription=ui.translate("글자 크기 슬라이더");max=172;progress=(size-8).roundToInt()
                progressTintList=android.content.res.ColorStateList.valueOf(blue);thumbTintList=android.content.res.ColorStateList.valueOf(blue)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){value.text="${p+8} pt";format("size",(p+8).toFloat())}}
                    override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}
                })
            }
            addView(seek,LinearLayout.LayoutParams(-1,dp(44)))
            stepper("글자 크기",size,8f,180f,read={cursorStyle().f("fontSize",28f)}){value.text="${number(it)} pt";seek.progress=(it-8).roundToInt();format("size",it)}
        }
    }
    private fun fontOptions(anchor:View) {
        val style=cursorStyle()
        card(anchor) {
            group {
                listOf(Triple("Modern","기본 고딕","sans-serif"),Triple("Classic","명조","serif"),Triple("Technical","고정 폭","monospace")).forEach { (name,label,family)->
                    val row=menuRow(label,checked=style.optString("fontFamily","sans-serif")==family){format("font",family)}
                    (row.getChildAt(0) as TextView).apply{text=name;typeface=Typeface.create(family,Typeface.NORMAL);textSize=19f}
                }
            }
        }
    }
    private fun formatOptions(anchor:View) {
        val obj=cursorStyle()
        card(anchor,"서식") {
            segments(listOf(Triple("굵게","bold",obj.optInt("fontWeight",400)>=600),Triple("기울임","italic",obj.optBoolean("italic")),
                Triple("밑줄","underline",obj.optBoolean("underline")),Triple("취소선","strike",obj.optBoolean("strike")))) {
                val key=listOf("bold","italic","underline","strike")[it]
                format(key,if(key=="bold")obj.optInt("fontWeight",400)<600 else !obj.optBoolean(key));formatOptions(anchor)
            }
            group { menuRow("텍스트 강조",chevron=true,close=false){colorOptions(anchor,true)} }
        }
    }
    private fun alignmentOptions(anchor:View,spacingOnly:Boolean=false) {
        val obj=currentStyle();val normal=TextBoxLayout.paint(obj).fontSpacing
        val multiplier=obj.f("lineSpacing",1.2f)
        card(anchor,if(spacingOnly)"줄 간격" else "정렬과 줄 간격") {
            if(!spacingOnly)segments(listOf("left","center","right").mapIndexed { i,key->
                Triple(listOf("왼쪽 정렬","가운데 정렬","오른쪽 정렬")[i],"align-$key",obj.optString("alignment","left")==key)
            }){property("alignment",listOf("left","center","right")[it]);alignmentOptions(anchor)}
            group {
                val row=ui.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(12),0)}
                row.addView(ui.text("자동 줄 간격",14f,panelInk),LinearLayout.LayoutParams(0,dp(44),1f))
                row.addView(Switch(activity).apply {
                    contentDescription=ui.translate("자동 줄 간격");isChecked=abs(multiplier-1.2f)<.001f
                    buttonTintList=android.content.res.ColorStateList.valueOf(blue)
                    setOnCheckedChangeListener{_,checked->property("lineSpacing",if(checked)1.2f else 1f);alignmentOptions(anchor,spacingOnly)}
                },LinearLayout.LayoutParams(-2,dp(40)));addView(row)
            }
            val current=(normal*multiplier).coerceAtLeast(1f)
            stepper("줄 간격",current,normal*.8f,normal*3,read={normal*currentStyle().f("lineSpacing",1.2f)}){property("lineSpacing",it/normal)}
            addView(SeekBar(activity).apply{
                contentDescription=ui.translate("줄 간격 슬라이더");max=220;progress=((multiplier-.8f)*100).roundToInt()
                progressTintList=android.content.res.ColorStateList.valueOf(blue);thumbTintList=android.content.res.ColorStateList.valueOf(blue)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user)property("lineSpacing",.8f+p/100f)}
                    override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){alignmentOptions(anchor,spacingOnly)}
                })
            },LinearLayout.LayoutParams(-1,dp(40)))
        }
    }
    private fun listOptions(anchor:View) {
        val obj=currentStyle();val text=obj.optString("text")
        card(anchor,"목록") {
            group {
                menuRow("목록 없음",checked=!Regex("(?m)^\\s*(?:[•*\\-]|\\d+\\.) ").containsMatchIn(text)){listFormat(null)}
                menuRow("글머리 기호","list",text.trimStart().startsWith("• ")){listFormat(false)}
                menuRow("번호 목록","numbered",Regex("^\\d+\\. ").containsMatchIn(text.trimStart())){listFormat(true)}
            }
        }
    }
    private fun boxStyleOptions(anchor:View,advanced:Boolean=false) {
        val obj=currentStyle()
        card(anchor,"텍스트 상자 스타일") {
            if(!advanced) {
                val row=ui.row().apply{gravity=Gravity.CENTER}
                listOf("배경 없음" to "#00000000","흰색 배경" to "#ffffff","노란색 배경" to "#fff3b0").forEach { (label,color)->
                    row.addView(ui.text("Aa",23f,panelInk).apply{
                        gravity=Gravity.CENTER;contentDescription=ui.translate(label);isClickable=true
                        background=ui.rounded(TextBoxLayout.color(color),6f,if(obj.optString("textBackground","#00000000")==color)blue else 0xffd5d5da.toInt(),2f)
                        setOnClickListener{property("textBackground",color);boxStyleOptions(anchor,false)}
                    },LinearLayout.LayoutParams(0,dp(62),1f).apply{setMargins(dp(4),dp(8),dp(4),dp(16))})
                };addView(row)
            } else {
                group { menuRow("배경 색상",chevron=true,close=false){colorOptions(anchor,propertyKey="textBackground")} }
                group {
                    val row=ui.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(12),0)}
                    row.addView(ui.text("테두리",15f,panelInk),LinearLayout.LayoutParams(0,dp(44),1f))
                    row.addView(Switch(activity).apply{contentDescription=ui.translate("테두리");isChecked=obj.optBoolean("textBorder");setOnCheckedChangeListener{_,value->property("textBorder",value)}},LinearLayout.LayoutParams(-2,dp(44)))
                    addView(row)
                }
                stepper("안쪽 여백",obj.f("textPadding"),0f,40f,read={currentStyle().f("textPadding")}){property("textPadding",it)}
            }
            tabs(listOf("프리셋","고급"),if(advanced)1 else 0){boxStyleOptions(anchor,it==1)}
        }
    }
    fun toolbar(): LinearLayout = ui.row().apply {
        gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(4))
        fun control(label:String,symbol:String,action:(View)->Unit):TextIcon {
            val button=TextIcon(activity,symbol).apply {
                contentDescription=ui.translate(label);setOnClickListener{action(it)};ViewCompat.setTooltipText(this,ui.translate(label))
            }
            addView(button,LinearLayout.LayoutParams(dp(42),dp(40)));return button
        }
        fun textControl(label:String,action:(View)->Unit):TextView {
            val button=ui.text("",16f,panelInk).apply{contentDescription=ui.translate(label);gravity=Gravity.CENTER;isClickable=true;isFocusable=false;setPadding(dp(8),0,dp(8),0);setOnClickListener{action(it)}}
            addView(button,LinearLayout.LayoutParams(-2,dp(36)).apply{leftMargin=dp(3);rightMargin=dp(3)})
            return button
        }
        fun separator(){addView(View(activity).apply{setBackgroundColor(0xffd9d9df.toInt())},LinearLayout.LayoutParams(dp(1),dp(29)).apply{leftMargin=dp(5);rightMargin=dp(5)})}
        control("텍스트 색상","color"){colorOptions(it)}
        textControl("글자 크기"){sizeOptions(it)}.apply{minimumWidth=dp(44);background=ui.rounded(Color.WHITE,9f)}
        textControl("글꼴"){fontOptions(it)}.apply{minimumWidth=dp(if(active)42 else 105);paintFlags=paintFlags or if(active)Paint.UNDERLINE_TEXT_FLAG else 0}
        control("텍스트 서식","format"){formatOptions(it)}
        control("정렬과 줄 간격","align-left"){alignmentOptions(it)}
        if(active)control("목록","list"){listOptions(it)}else control("줄 간격","spacing"){alignmentOptions(it,true)}
        if(!editing){separator();control("텍스트 상자 스타일","style"){boxStyleOptions(it)}}
        if(active) {
            separator()
            control("잘라내기","cut"){cutText()}
            if(editing){control("복사","copy"){copyText()};control("붙여넣기","paste"){pasteText()}}
            else {control("복제","duplicate"){duplicate()};control("삭제","trash"){delete()}.ink=0xffe34747.toInt()}
            control("텍스트 더 보기","more"){more(it)}
        } else {
            control("기본값으로 저장","default"){saveCurrentDefault()}
            separator();control("텍스트 도구 고정","pin"){togglePin()}
            addView(ui.text("텍스트 도구 고정",13f,panelInk).apply{setPadding(0,0,dp(6),0);isClickable=true;setOnClickListener{togglePin()}},LinearLayout.LayoutParams(-2,dp(36)))
        }
        refreshToolbar(this)
        if(!active)defaultToolbar=this
    }
    private fun refreshToolbar(row:LinearLayout) {
        val obj=cursorStyle()
        for(i in 0 until row.childCount){val v=row.getChildAt(i)
            when(v.contentDescription?.toString()) {
                ui.translate("텍스트 색상")->(v as TextIcon).swatch=TextBoxLayout.color(obj.optString("color","#172033"))
                ui.translate("글자 크기")->(v as TextView).text=number(obj.f("fontSize",28f))
                ui.translate("글꼴")->(v as TextView).apply{text=if(active)"Aa" else fontName(obj)+" ⌄";typeface=TextBoxLayout.typeface(obj);tag=obj.optString("fontFamily","sans-serif")}
                ui.translate("텍스트 서식")->(v as TextIcon).ink=if(obj.optInt("fontWeight",400)>=600||obj.optBoolean("italic")||obj.optBoolean("underline")||obj.optBoolean("strike"))blue else panelInk
                ui.translate("정렬과 줄 간격")->(v as TextIcon).symbol="align-${obj.optString("alignment","left")}"
                ui.translate("텍스트 상자 스타일")->(v as TextIcon).swatch=TextBoxLayout.color(obj.optString("textBackground","#00000000"))
                ui.translate("텍스트 도구 고정")->(v as TextIcon).ink=if(pinned)blue else panelInk
            }
            if(v !is TextView)v.background=ui.rounded(if(v===popupAnchor)0xffdedee5.toInt() else Color.TRANSPARENT,9f)
            if(editing && v.contentDescription?.toString() in listOf(ui.translate("잘라내기"),ui.translate("복사"))) {
                v.isEnabled=(input?.selectionEnd?:0)>(input?.selectionStart?:0);v.alpha=if(v.isEnabled)1f else .35f
            }
            v.invalidate()
        }
    }
    fun refreshControls(){refreshMenu();defaultToolbar?.let(::refreshToolbar)}
    private fun refreshMenu(){(menu?.getChildAt(0) as? LinearLayout)?.let(::refreshToolbar)}
    private fun showMenu() {
        if(!active)return
        val mode=if(editing)"typing" else "selected"
        menu?.let { bar ->
            if(menuMode!=mode){popup?.dismiss();bar.removeAllViews();bar.addView(toolbar(),FrameLayout.LayoutParams(-2,-1));menuMode=mode}
            refreshMenu();reposition();return
        }
        menuMode=mode
        val bar=HorizontalScrollView(activity).apply {
            contentDescription=ui.translate("텍스트 서식 메뉴");isHorizontalScrollBarEnabled=false;clipToOutline=true
            background=ui.rounded(0xfff4f4f6.toInt(),14f,0xffd9d9df.toInt());elevation=dp(9).toFloat()
            addView(toolbar(),FrameLayout.LayoutParams(-2,-1))
        }
        menu=bar;host.addView(bar);reposition()
    }
    private fun property(key: String, value: Any) {
        if(draft?.optBoolean("locked")==true)return
        modified=true; capture(); currentStyle().put(key,value); applyText(false); reposition(); showMenu(); changed()
    }
    private fun format(kind: String, value: Any?) {
        if(draft?.optBoolean("locked")==true)return
        modified=true; capture()
        val obj=currentStyle(); val editor=input
        val start=editor?.selectionStart?:-1; val end=editor?.selectionEnd?:-1
        if(editor!=null && editing && (editor.length()>0 || kind=="highlight") && start>=0 && start==end) {
            // IMEs replace their composing range; attach the requested style to each inserted range.
            if(formatAnchor!=start)cursorFormats.clear()
            formatAnchor=start;cursorFormats[kind]=value?:"#00000000"
            showMenu();changed();return
        } else if(editor!=null && editing && start>=0 && end>start) {
            cursorFormats.clear();formatAnchor=-1
            val previous=obj.optJSONArray("textRuns")?:JSONArray(); val updated=JSONArray()
            for(i in 0 until previous.length()) {
                val run=previous.getJSONObject(i); val a=run.optInt("start"); val b=run.optInt("end")
                if(run.optString("kind")!=kind || b<=start || a>=end) { updated.put(run); continue }
                if(a<start)updated.put(run.copyJson().put("end",start))
                if(b>end)updated.put(run.copyJson().put("start",end))
            }
            if(value!=null) updated.put(json("start" to start,"end" to end,"kind" to kind).apply {
                put("value",value)
            })
            obj.put("textRuns",updated); applyText(true)
        } else {
            val runs=obj.optJSONArray("textRuns")?:JSONArray()
            obj.put("textRuns",JSONArray().apply { for(i in 0 until runs.length()) {
                val run=runs.getJSONObject(i); if(run.optString("kind")!=kind)put(run)
            } })
            when(kind) {
                "bold" -> obj.put("fontWeight",if(value==true)700 else 400)
                "size" -> obj.put("fontSize",value)
                "font" -> obj.put("fontFamily",value)
                "highlight" -> {
                    val text=obj.optString("text")
                    if(text.isNotEmpty()&&value!=null)obj.getJSONArray("textRuns").put(json("start" to 0,"end" to text.length,"kind" to kind,"value" to value))
                }
                else -> obj.put(kind,value)
            }
            applyText(true)
        }
        reposition(); showMenu(); changed()
    }
    private fun listFormat(numbered:Boolean?) {
        if(draft?.optBoolean("locked")==true)return
        val editor=input?:return;modified=true
        val text=editor.text;val a=if(editing)editor.selectionStart.coerceAtLeast(0)else 0
        val b=if(editing)editor.selectionEnd.coerceAtLeast(a)else text.length
        val start=text.substring(0,a).lastIndexOf('\n')+1
        val finish=text.indexOf('\n',b).let{if(it<0)text.length else it}
        val lines=text.substring(start,finish).split('\n');var offset=start
        applying=true
        try {
            lines.forEachIndexed{index,line->
                val prefix=Regex("^(\\s*)(?:[•*\\-]|\\d+\\.)\\s+").find(line)?.value?:""
                val next=if(numbered==null)"" else if(numbered)"${index+1}. " else "• "
                text.replace(offset,offset+prefix.length,next);offset+=line.length-prefix.length+next.length+1
            }
            editor.setSelection((offset-1).coerceIn(0,text.length))
        } finally {applying=false}
        capture();updateHeight();reposition();refreshMenu();changed()
    }
    private fun continueList(text:Editable,newline:Int) {
        val start=text.substring(0,newline).lastIndexOf('\n')+1
        val line=text.substring(start,newline)
        val match=Regex("^(\\s*)([•*\\-]|\\d+\\.) (.*)$").matchEntire(line)?:return
        applying=true
        try {
            if(match.groupValues[3].isBlank()) { text.delete(start,newline+1);input?.setSelection(start) }
            else {
                val marker=match.groupValues[2]
                val next=marker.removeSuffix(".").toIntOrNull()?.let { "${it+1}." }?:"•"
                val prefix=match.groupValues[1]+next+" "
                text.insert(newline+1,prefix);input?.setSelection(newline+1+prefix.length)
            }
        } finally { applying=false }
    }
    private fun togglePin(){pinned=!pinned;if(pinned&&canvas.tool!=InkCanvasView.Tool.TEXT)canvas.tool=InkCanvasView.Tool.TEXT;refreshMenu();changed()}
    private fun saveCurrentDefault(){
        capture();val style=cursorStyle().copyJson()
        toolStyle=JSONObject().apply{TextBoxLayout.styleKeys.forEach{key->style.opt(key)?.let{put(key,it)}}}
        saveDefault(toolStyle.copyJson());refreshMenu();changed()
    }
    private fun duplicate(){
        capture();val copy=draft?.copyJson()?.put("id",uid("text"))?.apply{put("x",f("x")+24);put("y",f("y")+24);put("locked",false)}
        finish();if(copy!=null){canvas.add(copy);select(copy)}
    }
    private fun more(anchor:View) {
        card(anchor) {
            group{menuRow("삭제","trash",color=0xffe34747.toInt()){delete()}}
            group{
                menuRow("기본값으로 저장","default"){saveCurrentDefault()}
                menuRow("텍스트 상자 스타일","style",close=false){boxStyleOptions(anchor)}
            }
            group{
                menuRow("내용 편집","T"){beginTyping()}
                menuRow("정렬과 줄 간격",chevron=true,close=false){alignmentOptions(anchor)}
                menuRow("텍스트 서식",chevron=true,close=false){formatOptions(anchor)}
            }
            group{menuRow("복제","duplicate"){duplicate()};menuRow("복사","copy"){copyText()}}
            val actions=ui.row().apply{gravity=Gravity.CENTER}
            listOf("잘라내기" to "cut","앞으로" to "front","뒤로" to "back").forEach{(label,icon)->
                actions.addView(TextIcon(activity,icon).apply{contentDescription=ui.translate(label);setOnClickListener{
                    popup?.dismiss();if(icon=="cut")cutText()else{val id=draft?.optString("id");finish();if(id!=null)canvas.reorderTextObject(id,icon=="front")}
                }},LinearLayout.LayoutParams(0,dp(42),1f))
            };addView(actions,LinearLayout.LayoutParams(-1,dp(44)))
            group {
                menuRow(if(pinned)"텍스트 도구 고정 해제" else "텍스트 도구 고정","pin",checked=pinned){togglePin()}
                menuRow(if(draft?.optBoolean("locked")==true)"잠금 해제" else "잠금"){modified=true;draft?.let{it.put("locked",!it.optBoolean("locked"))};finish()}
                menuRow("편집 취소"){finish(cancel=true)}
                menuRow("텍스트 입력 완료","check",color=blue){finish(returnToLasso=true)}
            }
        }
    }
    private fun copyText() {
        capture();val obj=draft?:return;val field=input
        val a=field?.selectionStart?:-1;val b=field?.selectionEnd?:-1
        val selected=editing&&a>=0&&b>a
        val text=if(selected)field!!.text.substring(a,b)else obj.optString("text")
        if(!selected)copyObject(obj.copyJson())
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("",text))
    }
    private fun cutText(){
        if(draft?.optBoolean("locked")==true)return
        val field=input?:return;val a=field.selectionStart;val b=field.selectionEnd
        if(editing&&b<=a)return
        copyText();if(editing&&a>=0&&b>a)field.text.delete(a,b)else delete()
    }
    private fun pasteText(){
        if(draft?.optBoolean("locked")==true)return
        val clip=(activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?:return
        val text=clip.getItemAt(0).coerceToText(activity)
        if(!editing)beginTyping()
        val field=input?:return;val a=field.selectionStart.coerceAtLeast(0);val b=field.selectionEnd.coerceAtLeast(a)
        field.text.replace(a,b,text)
    }
    private fun delete() {
        if(draft?.optBoolean("locked")==true)return
        modified=true; applying=true; input?.setText(""); applying=false; finish(returnToLasso=true)
    }

    private inner class TextFrame : FrameLayout(activity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var operation=""
        private var startX=0f;private var startY=0f;private var start:JSONObject?=null
        private var startScale=1f
        private var startModified=false
        private var moved=false
        private val gutter get()=dp(32).toFloat()
        init {setWillNotDraw(false);clipChildren=false;clipToPadding=false;contentDescription=ui.translate("텍스트 상자")}
        private fun box()=RectF(gutter,gutter,width-gutter,height-gutter)
        override fun onDraw(target:Canvas) {
            val obj=draft?:return;val b=box();val saved=target.save();clipDraft(target,obj)
            val radius=6f*canvas.scale
            paint.style=Paint.Style.FILL;paint.color=TextBoxLayout.color(obj.optString("textBackground","#00000000"),Color.TRANSPARENT)
            target.drawRoundRect(b,radius,radius,paint)
            if(obj.optBoolean("textBorder")) {
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*canvas.scale
                paint.color=TextBoxLayout.color(obj.optString("color","#172033"));target.drawRoundRect(b,radius,radius,paint)
            }
            target.restoreToCount(saved)
        }
        private fun clipDraft(target:Canvas,obj:JSONObject) {
            val scale=canvas.scale
            val matrix=Matrix().apply {setScale(scale,scale);postTranslate(gutter-obj.f("x")*scale,gutter-obj.f("y")*scale)}
            target.concat(matrix);NoteRenderer.clipErased(target,obj)
            val inverse=Matrix();matrix.invert(inverse);target.concat(inverse)
        }
        override fun dispatchDraw(target:Canvas) {
            val obj=draft?:return;val b=box();val saved=target.save()
            clipDraft(target,obj);super.dispatchDraw(target);target.restoreToCount(saved)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1).toFloat().coerceAtLeast(1f);paint.color=blue
            target.drawRect(b,paint)
            if(obj.optBoolean("locked"))return
            val radius=dp(4).toFloat()
            fun side(x:Float){
                paint.style=Paint.Style.FILL;paint.color=Color.WHITE;target.drawCircle(x,b.centerY(),radius+dp(2),paint)
                paint.color=blue;target.drawCircle(x,b.centerY(),radius,paint)
            }
            side(b.left);side(b.right)
            if(editing) {
                // The grip stays outside native text selection and is usable even for an empty draft.
                paint.style=Paint.Style.FILL;paint.color=blue
                target.drawRoundRect(b.centerX()-dp(13),b.top-dp(15),b.centerX()+dp(13),b.top-dp(10),dp(3).toFloat(),dp(3).toFloat(),paint)
            } else {
                listOf(b.left to b.top,b.right to b.top,b.left to b.bottom,b.right to b.bottom).forEach{(x,y)->
                    paint.style=Paint.Style.FILL;paint.color=Color.WHITE;target.drawRect(x-radius,y-radius,x+radius,y+radius,paint)
                    paint.style=Paint.Style.STROKE;paint.color=blue;target.drawRect(x-radius,y-radius,x+radius,y+radius,paint)
                }
                paint.color=blue;paint.style=Paint.Style.STROKE
                target.drawLine(b.centerX(),b.top,b.centerX(),b.top-dp(22),paint)
                paint.style=Paint.Style.FILL;paint.color=Color.WHITE;target.drawCircle(b.centerX(),b.top-dp(22),dp(5).toFloat(),paint)
                paint.style=Paint.Style.STROKE;paint.color=blue;target.drawCircle(b.centerX(),b.top-dp(22),dp(5).toFloat(),paint)
            }
        }
        override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
            if(event.actionMasked!=MotionEvent.ACTION_DOWN)return operation.isNotEmpty()
            val obj=draft?:return false
            if(obj.optBoolean("locked"))return true
            val b=box();val tolerance=dp(16)
            operation=when {
                editing && abs(event.x-b.centerX())<dp(27) && event.y<b.top-dp(3)->"move"
                !editing && abs(event.x-b.centerX())<tolerance && event.y<b.top-dp(9)->"rotate"
                !editing && min(abs(event.x-b.left),abs(event.x-b.right))<tolerance && min(abs(event.y-b.top),abs(event.y-b.bottom))<min(dp(12).toFloat(),b.height()/4)->
                    "scale-${if(event.x<b.centerX())"left" else "right"}-${if(event.y<b.centerY())"top" else "bottom"}"
                abs(event.x-b.left)<tolerance && abs(event.y-b.centerY())<tolerance->"left"
                abs(event.x-b.right)<tolerance && abs(event.y-b.centerY())<tolerance->"right"
                !editing || event.y<b.top || event.y>b.bottom || event.x<b.left || event.x>b.right->"move"
                else->""
            }
            if(operation.isNotEmpty()) {
                capture();start=obj.copyJson();startX=event.rawX;startY=event.rawY;startScale=canvas.scale;startModified=modified;moved=false
            }
            return operation.isNotEmpty()
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            val obj=draft?:return false
            if(obj.optBoolean("locked"))return true
            val source=start?:return true
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE->{
                    val dx=(event.rawX-startX)/startScale;val dy=(event.rawY-startY)/startScale
                    if(!moved && hypot(event.rawX-startX,event.rawY-startY)>ViewConfiguration.get(activity).scaledTouchSlop){
                        moved=true;dragging=true;popup?.dismiss()
                    }
                    if(!moved)return true
                    modified=true
                    val angle=source.f("rotation")
                    when(operation) {
                        "move"->{obj.put("x",source.f("x")+dx);obj.put("y",source.f("y")+dy)}
                        "left","right"->{
                            val horizontal=TextTransform.rotated(dx,dy,-angle).x
                            val width=(source.f("w")+if(operation=="left")-horizontal else horizontal).coerceAtLeast(max(48f,source.f("textPadding")*2+16f))
                            obj.put("w",width);updateHeight()
                            val point=TextTransform.origin(source.f("x"),source.f("y"),source.f("w"),source.f("h"),width,obj.f("h"),angle,if(operation=="left")1f else 0f,0f)
                            obj.put("x",point.x);obj.put("y",point.y)
                        }
                        "scale-left-top","scale-left-bottom","scale-right-top","scale-right-bottom"->{
                            val left="left" in operation;val top="top" in operation
                            val base=source.f("fontSize",28f)
                            val factor=TextTransform.scale(source.f("w"),source.f("h"),dx,dy,angle,left,top)
                                .coerceIn(max(8f/base,48f/source.f("w").coerceAtLeast(1f)).coerceAtMost(180f/base),180f/base)
                            obj.put("w",source.f("w")*factor);obj.put("fontSize",base*factor);obj.put("textPadding",source.f("textPadding")*factor)
                            source.optJSONArray("textRuns")?.let {runs->obj.put("textRuns",JSONArray(runs.toString()).apply{
                                for(i in 0 until length())getJSONObject(i).takeIf{it.optString("kind")=="size"}?.let{it.put("value",it.f("value")*factor)}
                            })}
                            applyText(true)
                            val point=TextTransform.origin(source.f("x"),source.f("y"),source.f("w"),source.f("h"),obj.f("w"),obj.f("h"),angle,if(left)1f else 0f,if(top)1f else 0f)
                            obj.put("x",point.x);obj.put("y",point.y)
                        }
                        "rotate"->{
                            val point=canvas.screenPoint(source.f("x")+source.f("w")/2,source.f("y")+source.f("h")/2)
                            val location=IntArray(2);host.getLocationOnScreen(location)
                            val cx=point.x+location[0];val cy=point.y+location[1]
                            val delta=atan2(event.rawY-cy,event.rawX-cx)-atan2(startY-cy,startX-cx)
                            var next=angle+delta
                            val rightAngle=(next/(Math.PI.toFloat()/2)).roundToInt()*(Math.PI.toFloat()/2)
                            if(abs(next-rightAngle)<.035f)next=rightAngle
                            obj.put("rotation",next)
                        }
                    }
                    reposition()
                }
                MotionEvent.ACTION_UP->{
                    val tapped=!moved&&operation=="move";operation="";start=null;dragging=false
                    parent.requestDisallowInterceptTouchEvent(false)
                    if(tapped){beginTyping();input?.let{field->field.setSelection(field.getOffsetForPosition(event.x-field.left,event.y-field.top).coerceIn(0,field.length()))}}
                    showMenu();performClick()
                }
                MotionEvent.ACTION_CANCEL->{
                    draft=source;modified=startModified;operation="";start=null;dragging=false;parent.requestDisallowInterceptTouchEvent(false);applyText(true);showMenu()
                }
            }
            return true
        }
        override fun performClick():Boolean{super.performClick();return true}
    }
}
