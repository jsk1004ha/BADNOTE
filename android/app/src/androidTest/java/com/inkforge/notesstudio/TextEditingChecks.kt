package com.inkforge.notesstudio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real finger/stylus input plus Android composing InputConnection; release data is never touched. */
internal object TextEditingChecks {
    fun run(activity:MainActivity,instrument:Instrumentation,result:Bundle) {
        check(activity.packageName.endsWith(".debug"))
        PrecisionEraseChecks.run(result)
        fun <T> main(block:()->T)=ui(instrument,block)
        fun view()=requireNotNull(activity.inkView)
        fun editor()=field(activity,"textEditor") as InlineTextEditor
        fun input()=descendants(activity.window.decorView).filterIsInstance<EditText>().first {
            it.isShown && it.contentDescription=="페이지 텍스트 입력"
        }
        fun saved()=await("text saves") { main { field(activity,"saving")==false && (field(activity,"saves") as Collection<*>).isEmpty() } }
        val repo=activity.repository
        val doc=repo.create("텍스트 입력 · 지우개 검증","root","blank")
        val id=repo.pageIds(doc.id).single()
        main { activity.openDocument(doc.id) }
        await("text fixture page") { main { activity.inkView?.currentPage?.id==id && view().width>0 } }
        var editCount=0
        main { val original=view().onEdit;view().onEdit={page,change,positions->editCount++;original(page,change,positions)}
            view().tool=InkCanvasView.Tool.TEXT;view().stylusOnly=true }
        fun point(x:Float,y:Float)=main {
            view().screenPoint(x,y).also { p->val location=IntArray(2);view().getLocationOnScreen(location);p.offset(location[0].toFloat(),location[1].toFloat()) }
        }
        fun tapPage(x:Float,y:Float,stylus:Boolean=false,hold:Long=0) = tap(instrument,point(x,y),stylus,hold)
        var objectReads=0
        fun objectNow()=main { checkNotNull(editor().editingObject){"Draft closed at checkpoint ${++objectReads}"}.also{objectReads++} }
        fun textObject()=main { view().currentPage!!.objects.single{it.optString("type")=="text"} }

        // Finger taps must create text even though finger handwriting is disabled.
        tapPage(160f,210f)
        await("visible inline field") { main { editor().active && input().hasFocus() && input().width>0 } }
        await("soft keyboard shown") { main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true } }
        check(main { field(activity,"modal")==null && view().currentPage!!.objects.isEmpty() }) { "Text still uses a dialog or saves empty boxes" }
        main {
            val bar=descendants(activity.window.decorView).first { it.contentDescription=="텍스트 서식 메뉴" }
            val controls=descendants(bar).filter { it.isClickable && it.contentDescription!=null }
            check(controls.size==10) { "Missing text controls: ${controls.map{it.contentDescription}}" }
        }
        // New, empty typing previews must reflect font/size/color immediately in both views.
        click(activity,instrument,"글꼴");capture(activity,instrument,"font-panel",result);popupClick(activity,instrument,"명조");capture(activity,instrument,"font-applied",result)
        check(objectNow().optString("fontFamily")=="serif")
        check(main { descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().any {
            it.contentDescription=="글꼴"&&it.tag=="serif" } }) { "Floating font label stayed stale" }
        click(activity,instrument,"글자 크기");repeat(4){popupClick(activity,instrument,"글자 크기 늘리기")};capture(activity,instrument,"size-panel",result);dismissPopup(activity,instrument)
        check(objectNow().f("fontSize")==32f && main { abs(input().textSize-32f*view().scale)<1f })
        check(main{descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().any{it.isShown&&it.contentDescription=="글자 크기"&&it.text.toString()=="32"}}){"Font size in the floating bar is stale"}
        click(activity,instrument,"텍스트 색상");capture(activity,instrument,"color-panel",result)
        popupClick(activity,instrument,"사용자 지정");capture(activity,instrument,"color-custom",result)
        popupClick(activity,instrument,"프리셋");popupClick(activity,instrument,"파랑")
        check(objectNow().optString("color")=="#147bd1")
        click(activity,instrument,"텍스트 상자 스타일")
        capture(activity,instrument,"style-panel",result)
        popupClick(activity,instrument,"노란색 배경")
        check(objectNow().optString("textBackground")=="#fff3b0")
        popupClick(activity,instrument,"고급");capture(activity,instrument,"style-advanced",result)
        popupClick(activity,instrument,"안쪽 여백 늘리기");check(objectNow().f("textPadding")==1f)
        dismissPopup(activity,instrument)
        click(activity,instrument,"정렬과 줄 간격");capture(activity,instrument,"alignment-panel",result);dismissPopup(activity,instrument)
        click(activity,instrument,"목록");capture(activity,instrument,"list-panel",result);dismissPopup(activity,instrument)
        click(activity,instrument,"텍스트 더 보기");capture(activity,instrument,"more-panel",result);dismissPopup(activity,instrument)
        // Grip is outside the EditText: move an empty draft while the keyboard is still open.
        val previewBefore=objectNow()
        val grip=point(previewBefore.f("x")+previewBefore.f("w")/2,previewBefore.f("y")).apply {y-=main{activity.resources.displayMetrics.density*12}}
        drag(instrument,grip,PointF(grip.x+50,grip.y+25))
        check(objectNow().f("x")>previewBefore.f("x")+20) { "Typing preview grip cannot move the box" }
        val content="한글 입력 테스트\nGoodnotes처럼 페이지 위에서 바로 입력합니다.\n줄바꿈과 English 123, 긴 문장도 상자 너비에 맞춰 표시합니다."
        main {
            val field=input();val connection=field.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("ㅎ",1);connection.setComposingText("한",1);connection.setComposingText("한글",1)
            connection.commitText(content,1);connection.finishComposingText()
            check(field.text.toString()==content) { "Korean composition changed text" }
            field.setSelection(0,2)
        }
        click(activity,instrument,"굵게");capture(activity,instrument,"format-panel",result);dismissPopup(activity,instrument)
        check(objectNow().optJSONArray("textRuns")?.length()==1) { "Selection formatting was lost" }
        check(objectNow().f("h")>60f) { "Multiline text did not grow" }
        instrument.waitForIdleSync()
        main { val field=input();val obj=objectNow();val layout=TextBoxLayout.layout(obj)
            check(field.layout.height<=field.height+2) { "Text clipped: field=${field.width}x${field.height} nativeHeight=${field.layout.height} nativeLines=${field.layout.lineCount} savedHeight=${layout.height} savedLines=${layout.lineCount} scale=${view().scale}" }
        }
        capture(activity,instrument,"typing",result)
        click(activity,instrument,"텍스트 입력 완료")
        await("text committed") { main { !editor().active && view().currentPage!!.objects.size==1 } }
        val inserted=textObject().copyJson();val textId=inserted.getString("id")
        check(editCount==1) { "Every character became a separate document undo: $editCount" }
        check(inserted.optString("text")==content && inserted.optInt("textLayoutVersion")==1)
        main { view().undo() };check(main { view().currentPage!!.objects.isEmpty() }) { "Undo did not remove insertion" }
        main { view().redo() };check(textObject().optString("text")==content) { "Redo changed composed text" }
        saved();check(repo.page(id)!!.objects.single().optString("text")==content)

        // First tap selects the box; next tap enters native cursor/selection editing.
        main { view().tool=InkCanvasView.Tool.TEXT;view().resetZoom() }
        tapPage(inserted.f("x")+100,inserted.f("y")+30)
        await("select existing text") { main { editor().active } }
        check(main { !input().hasFocus() }) { "Selection unexpectedly placed a cursor" }
        val stableCount=editCount
        main { editor().finish() }
        check(editCount==stableCount) { "Selection alone rewrote the document" }
        tapPage(inserted.f("x")+100,inserted.f("y")+30)
        await("select again") { main { editor().active } }
        val selectedBounds=main { global(input()) }
        tap(instrument,PointF(selectedBounds.exactCenterX(),selectedBounds.top+selectedBounds.height()*.65f))
        await("tap again edits") { main { input().hasFocus() && ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true } }
        instrument.waitForIdleSync();SystemClock.sleep(350)
        main { val connection=input().onCreateInputConnection(EditorInfo())!!; input().setSelection(input().length());connection.commitText("\n두 번째 편집",1) }
        click(activity,instrument,"텍스트 입력 완료")
        check(textObject().optString("text").endsWith("두 번째 편집"))
        main { view().undo() };check(textObject().optString("text")==content)
        main { view().redo() }

        await("keyboard hidden before transform") {main{ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}}
        instrument.waitForIdleSync();SystemClock.sleep(300)
        // Moving and horizontal resizing use real handles, keeping font size for width-only resize.
        main { view().tool=InkCanvasView.Tool.TEXT;view().resetZoom() }
        val beforeMove=textObject().copyJson()
        tapPage(beforeMove.f("x")+100,beforeMove.f("y")+30)
        await("move selection") { main { editor().active } }
        val start=main { global(input()).let{PointF(it.exactCenterX(),it.exactCenterY())} }
        drag(instrument,start,PointF(start.x+50,start.y+40))
        check(abs(objectNow().f("x")-beforeMove.f("x"))>20f) { "Text drag did not move" }
        val beforeResize=objectNow()
        val right=point(beforeResize.f("x")+beforeResize.f("w"),beforeResize.f("y")+beforeResize.f("h")/2)
        drag(instrument,right,PointF(right.x-90,right.y))
        check(objectNow().f("w")<beforeResize.f("w")-30f && objectNow().f("fontSize",28f)==beforeResize.f("fontSize",28f)) { "Side handle did not reflow at the same text size" }
        val beforeScale=objectNow()
        val topLeft=point(beforeScale.f("x"),beforeScale.f("y"))
        val fixed=PointF(beforeScale.f("x")+beforeScale.f("w"),beforeScale.f("y")+beforeScale.f("h"))
        drag(instrument,topLeft,PointF(topLeft.x+42,topLeft.y+45))
        val scaled=objectNow()
        check(scaled.f("fontSize")<beforeScale.f("fontSize") && scaled.f("w")<beforeScale.f("w")){"Corner did not scale the font and width together"}
        check(abs(fixed.x-scaled.f("x")-scaled.f("w"))<1f && abs(fixed.y-scaled.f("y")-scaled.f("h"))<1f){"Scaling displaced the opposite corner"}
        val center=point(scaled.f("x")+scaled.f("w")/2,scaled.f("y")+scaled.f("h")/2)
        val rotate=point(scaled.f("x")+scaled.f("w")/2,scaled.f("y")).apply{y-=main{activity.resources.displayMetrics.density*22}}
        val radius=center.y-rotate.y
        drag(instrument,rotate,PointF(center.x+radius*kotlin.math.sin(.28f),center.y-radius*kotlin.math.cos(.28f)))
        val rotated=objectNow();check(abs(rotated.f("rotation")-.28f)<.025f){"Rotation handle jumped or did not rotate: ${rotated.f("rotation")}"}
        fun localPoint(obj:JSONObject,x:Float,y:Float):PointF {
            val c=kotlin.math.cos(obj.f("rotation"));val ss=kotlin.math.sin(obj.f("rotation"))
            return PointF(obj.f("x")+obj.f("w")/2+x*c-y*ss,obj.f("y")+obj.f("h")/2+x*ss+y*c)
        }
        val held=localPoint(rotated,-rotated.f("w")/2,-rotated.f("h")/2)
        val side=localPoint(rotated,rotated.f("w")/2,0f).let{point(it.x,it.y)}
        drag(instrument,side,PointF(side.x+45*kotlin.math.cos(.28f),side.y+45*kotlin.math.sin(.28f)))
        val reflowed=objectNow();val heldAfter=localPoint(reflowed,-reflowed.f("w")/2,-reflowed.f("h")/2)
        check(reflowed.f("w")>rotated.f("w")+20 && abs(reflowed.f("fontSize")-rotated.f("fontSize"))<.001f){"Rotated side handle changed font size or missed width"}
        check(abs(held.x-heldAfter.x)<1f&&abs(held.y-heldAfter.y)<1f){"Rotated reflow moved the opposite edge"}
        capture(activity,instrument,"selected",result)
        click(activity,instrument,"텍스트 입력 완료")
        saved()
        val updated=textObject().copyJson()
        main { activity.showLibrary();activity.openDocument(doc.id) }
        await("reopen typed text") { main { activity.inkView?.currentPage?.id==id } }
        check(textObject().optString("text")==updated.optString("text") && textObject().f("w")==updated.f("w"))
        check(textObject().optJSONArray("textRuns").toString()==updated.optJSONArray("textRuns").toString())

        // A tap while the selected page is being loaded is replayed once after its save barrier.
        val delayed=mutableListOf<()->Unit>()
        val normalBeforeRead=main { view().beforePageRead }
        main { view().tool=InkCanvasView.Tool.TEXT;view().beforePageRead={delayed.add(it)};view().open(listOf(id),listOf(repo.pageMeta(id)!!),0) }
        tapPage(800f,160f)
        check(main { !editor().active && delayed.isNotEmpty() })
        main { view().beforePageRead=normalBeforeRead;delayed.toList().forEach { it() };delayed.clear() }
        await("queued text tap after load") { main { editor().active } }
        check(main{input().hasFocus()}){"Queued blank-page tap selected an existing object: ${objectNow()}"}
        main { editor().finish(cancel=true) }
        check(main { view().currentPage!!.objects.size==1 }) { "Empty/cancelled text box was saved" }

        // Stylus taps are equally valid; pin keeps text selected for repeated boxes.
        main { view().tool=InkCanvasView.Tool.TEXT;view().resetZoom() }
        tapPage(800f,280f,stylus=true)
        await("stylus text") { main { editor().active && ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true } }
        click(activity,instrument,"텍스트 도구 고정")
        main { input().onCreateInputConnection(EditorInfo())!!.commitText("고정 도구",1) }
        click(activity,instrument,"텍스트 입력 완료")
        check(main { view().tool==InkCanvasView.Tool.TEXT }) { "Pinned text tool was not retained" }
        await("keyboard hidden before next pinned box"){main{ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}}
        instrument.waitForIdleSync();SystemClock.sleep(300)
        // The rotated transform fixture occupies x=600 at this height; choose clear paper.
        tapPage(800f,420f)
        await("next pinned box") { main { editor().active && ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true } }
        main { input().onCreateInputConnection(EditorInfo())!!.commitText("앞 ",1) }
        click(activity,instrument,"굵게");dismissPopup(activity,instrument)
        click(activity,instrument,"글꼴");popupClick(activity,instrument,"고정 폭")
        check(main { descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().any {
            it.contentDescription=="글꼴"&&it.tag=="monospace" } }) { "Pending cursor font missing from floating bar" }
        main { input().onCreateInputConnection(EditorInfo())!!.commitText("굵게",1) }
        check(objectNow().array("textRuns").objects().any { it.optString("kind")=="bold" && it.optInt("start")==2 && it.optInt("end")==4 }) { "Cursor format range mismatch: ${objectNow()}, spans=${main{input().text.getSpans(0,input().length(),Any::class.java).map{span->"${span.javaClass.name}:${input().text.getSpanStart(span)}:${input().text.getSpanEnd(span)}:${input().text.getSpanFlags(span)}"}}}" }
        main { input().setText("1. 하나");input().setSelection(input().length());input().onCreateInputConnection(EditorInfo())!!.commitText("\n",1) }
        check(objectNow().optString("text")=="1. 하나\n2. ") { "Numbered list did not continue" }
        main { input().onCreateInputConnection(EditorInfo())!!.commitText("\n",1) }
        check(objectNow().optString("text")=="1. 하나\n") { "Empty list did not exit" }
        main { editor().finish(cancel=true);view().tool=InkCanvasView.Tool.PEN }
        check(main { !editor().pinned }) { "Pin survived switching to another tool" }
        await("keyboard hidden before page long press") {
            main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false }
        }
        instrument.waitForIdleSync();SystemClock.sleep(300)
        main { view().tool=InkCanvasView.Tool.TEXT;view().resetZoom() }
        tapPage(650f,500f,hold=650)
        await("full page text") { main { editor().active } }
        check(objectNow().optBoolean("fullPageText") && objectNow().f("w")==904f)
        main { editor().finish(cancel=true) }

        erasers(activity,instrument,result)
        main { view().tool=InkCanvasView.Tool.HAND }
        val zoom=main { view().zoomPercent() }
        click(activity,instrument,"축소")
        check(main { view().zoomPercent()<zoom }) { "Minus button did not zoom out" }
        capture(activity,instrument,"minus",result)
        saved()
        result.putString("textEditing","PASS: anchored grouped popovers, continuous ten-control typing menu, empty-draft style and grip, cursor toolbar sync, finger with stylus-only, stylus, deferred page load, keyboard, Korean composition, unclipped multiline, selection/cursor formatting, lists/full-page text, select/edit, drag/reflow/corner scale/rotation/rotated anchor, undo/redo, persistence, empty/cancel, pin, minus zoom")
    }

    private fun erasers(activity:MainActivity,instrument:Instrumentation,result:Bundle) {
        fun <T> main(block:()->T)=ui(instrument,block)
        val view=main { activity.inkView!! }
        val original=main { NotePage.from(view.currentPage!!.json()) }
        val save=main { view.onEdit.also { view.onEdit={_,_,_->} } }
        val pageId=original.id
        fun text(id:String,x:Float,y:Float)=json("id" to id,"type" to "text","x" to x,"y" to y,"w" to 220,"h" to 60,"text" to "텍스트 지우기","fontSize" to 28)
        val items=listOf(text("erase-text",250f,220f),text("locked-text",250f,220f).put("locked",true),text("hidden-text",250f,220f).put("hidden",true),
            json("id" to "erase-highlight","type" to "stroke","brush" to "highlighter","width" to 26,"color" to "#ffe066","opacity" to .32,
                "points" to JSONArray(listOf(InkPoint(100f,420f).json(),InkPoint(800f,420f).json()))),
            json("id" to "erase-shape","type" to "shape","shape" to "rectangle","x1" to 100,"y1" to 570,"x2" to 400,"y2" to 740,"width" to 4))
        fun reset(precise:Boolean) = main {
            view.replacePage(NotePage(pageId,original.meta.copyJson(),items.map{it.copyJson()}.toMutableList()))
            view.tool=InkCanvasView.Tool.ERASER;view.preciseEraser=precise;view.wholeEraser=false;view.eraserRadius=18f;view.resetZoom()
        }
        fun ids()=main { view.currentPage!!.objects.map{it.optString("id")} }
        fun erase(x:Float,y:Float,cancel:Boolean=false,barrel:Boolean=false) = main {
            val point=view.screenPoint(x,y); val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)) {
                val properties=MotionEvent.PointerProperties().apply { id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS }
                val coords=MotionEvent.PointerCoords().apply { this.x=point.x;this.y=point.y;pressure=.6f;size=.1f }
                val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,1,arrayOf(properties),arrayOf(coords),0,
                    if(barrel)MotionEvent.BUTTON_STYLUS_PRIMARY else 0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                view.onTouchEvent(event);event.recycle()
            }
        }
        for(precise in listOf(false,true)) {
            reset(precise)
            erase(350f,250f);check((if(precise)"erase-text" in ids() else "erase-text" !in ids()) && "locked-text" in ids() && "hidden-text" in ids()) { "Text eraser eligibility wrong" }
            main { view.undo() };check(ids()==items.map{it.getString("id")})
            main { view.redo() };check(if(precise)"erase-text" in ids() else "erase-text" !in ids())
            if(precise)main {
                check(view.currentPage!!.objects.first{it.optString("id")=="erase-text"}.has(ObjectEraseMask.KEY))
                check(view.currentPage!!.objects.filter{it.optBoolean("locked")||it.optBoolean("hidden")}.none{it.has(ObjectEraseMask.KEY)})
            }
            erase(450f,420f)
            val highlights=main { view.currentPage!!.objects.filter{it.optString("brush")=="highlighter"} }
            check(if(precise)highlights.size==1&&highlights.single().has(ObjectEraseMask.KEY) else highlights.isEmpty()) { "Highlighter eraser mode wrong: precise=$precise fragments=${highlights.size}" }
            erase(100f,650f);check(if(precise)main{view.currentPage!!.objects.first{it.optString("id")=="erase-shape"}.has(ObjectEraseMask.KEY)} else "erase-shape" !in ids()) { "Shape was protected from eraser" }
            main { view.undo() };check("erase-shape" in ids())
            val before=ids();erase(100f,650f,cancel=true);check(ids()==before) { "Cancelled erase was committed" }
        }
        reset(false);main { view.tool=InkCanvasView.Tool.PEN }
        erase(350f,250f,barrel=true);check("erase-text" !in ids()) { "Barrel eraser could not erase text" }
        main { view.undo();view.replacePage(original);view.onEdit=save }
        result.putString("typedObjectEraser","PASS: text, highlighter stroke/precision cutout, geometric outline, locked/hidden protection, barrel eraser, cancel, undo/redo")
    }
    private fun tap(instrument:Instrumentation,p:PointF,stylus:Boolean=false,hold:Long=0) {
        val time=SystemClock.uptimeMillis()
        fun send(action:Int) {
            val properties=MotionEvent.PointerProperties().apply { id=0;toolType=if(stylus)MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER }
            val coords=MotionEvent.PointerCoords().apply { x=p.x;y=p.y;pressure=.6f;size=.1f }
            val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,
                if(stylus)InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,0)
            try { instrument.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN);if(hold>0)SystemClock.sleep(hold);send(MotionEvent.ACTION_UP);instrument.waitForIdleSync()
    }
    private fun drag(instrument:Instrumentation,from:PointF,to:PointF) {
        val time=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { instrument.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN,from.x,from.y)
        repeat(12) { val f=(it+1)/12f;send(MotionEvent.ACTION_MOVE,from.x+(to.x-from.x)*f,from.y+(to.y-from.y)*f);SystemClock.sleep(16) }
        send(MotionEvent.ACTION_UP,to.x,to.y);instrument.waitForIdleSync()
    }
    private fun click(activity:MainActivity,instrument:Instrumentation,label:String) {
        if(label in listOf("굵게","기울임","밑줄","취소선")) {
            click(activity,instrument,"텍스트 서식");popupClick(activity,instrument,label);return
        }
        dismissPopup(activity,instrument)
        if(label in listOf("텍스트 도구 고정","텍스트 입력 완료","텍스트 상자 스타일") && !ui(instrument){descendants(activity.window.decorView).any{it.isShown&&it.isClickable&&it.contentDescription==label}}) { click(activity,instrument,"텍스트 더 보기");popupClick(activity,instrument,label);return }
        val typing=ui(instrument){descendants(activity.window.decorView).filterIsInstance<EditText>().any{it.contentDescription=="페이지 텍스트 입력"&&it.hasFocus()}}
        if(typing)await("IME ready for toolbar:$label"){ui(instrument){ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}}
        fun target()=descendants(activity.window.decorView).firstOrNull { it.isShown && it.isClickable && it.contentDescription==label } ?: error("Missing text control:$label")
        ui(instrument) { target().let { it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true) } }
        instrument.waitForIdleSync()
        var prior:Rect?=null;var stableAt=SystemClock.uptimeMillis();var rect=Rect()
        await("settled text control:$label") {
            val current=ui(instrument) { global(target()) }
            val clearOfIme=ui(instrument) { val bottom=ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?:0
                current.bottom<=activity.window.decorView.height-bottom }
            if(current!=prior) { prior=current;stableAt=SystemClock.uptimeMillis() }
            rect=current;clearOfIme&&SystemClock.uptimeMillis()-stableAt>=650
        }
        tap(instrument,PointF(rect.exactCenterX(),rect.exactCenterY()))
    }
    private fun dismissPopup(activity:MainActivity,instrument:Instrumentation) {
        ui(instrument){(InlineTextEditor::class.java.getDeclaredField("popup").apply{isAccessible=true}.get(field(activity,"textEditor")) as? android.widget.PopupWindow)?.dismiss()}
        instrument.waitForIdleSync()
    }
    private fun popupClick(activity:MainActivity,instrument:Instrumentation,label:String) {
        fun popup()=(InlineTextEditor::class.java.getDeclaredField("popup").apply{isAccessible=true}
            .get(field(activity,"textEditor")) as? android.widget.PopupWindow)
        fun target()=popup()?.contentView?.let { root->descendants(root).firstOrNull {
            it.isShown&&it.isClickable&&it.contentDescription?.toString()==label } }
        try {await("text popup:$label") { ui(instrument){target()!=null} }}catch(error:Throwable){
            capture(activity,instrument,"failed-popup",Bundle())
            throw IllegalStateException("Popup $label missing; active=${ui(instrument){(field(activity,"textEditor") as InlineTextEditor).active}}; popup=${ui(instrument){popup()!=null}}",error)
        }
        ui(instrument){target()!!.let{it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true)}}
        var prior:Rect?=null;var stableAt=SystemClock.uptimeMillis();var bounds=Rect()
        await("settled text popup:$label") {
            val current=ui(instrument){target()?.let{v->val xy=IntArray(2);v.getLocationOnScreen(xy);Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)}}
            if(current==null)false else {
                if(current!=prior){prior=current;stableAt=SystemClock.uptimeMillis()}
                bounds=current;!current.isEmpty&&SystemClock.uptimeMillis()-stableAt>=300
            }
        }
        tap(instrument,PointF(bounds.exactCenterX(),bounds.exactCenterY()))
        instrument.waitForIdleSync()
    }
    private fun capture(activity:MainActivity,instrument:Instrumentation,name:String,result:Bundle) {
        instrument.waitForIdleSync();SystemClock.sleep(200)
        val bitmap=instrument.uiAutomation.takeScreenshot()!!
        val file=File(activity.filesDir,"text-editor-${activity.resources.configuration.screenWidthDp}dp-$name.png")
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        result.putString("text${name}Screenshot",file.absolutePath)
    }
    private fun global(view:View)=Rect().also { check(view.getGlobalVisibleRect(it)) }
    private fun field(activity:MainActivity,name:String):Any?=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun descendants(view:View):List<View> = buildList { add(view);if(view is ViewGroup)for(i in 0 until view.childCount)addAll(descendants(view.getChildAt(i))) }
    private fun <T> ui(instrument:Instrumentation,block:()->T):T {
        if(Looper.myLooper()==Looper.getMainLooper())return block()
        val value=AtomicReference<T>();val error=AtomicReference<Throwable>()
        instrument.runOnMainSync { try { value.set(block()) } catch(e:Throwable) { error.set(e) } }
        error.get()?.let{throw it};return value.get()
    }
    private fun await(label:String,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+30000
        while(SystemClock.uptimeMillis()<end) { if(condition())return;SystemClock.sleep(40) }
        error("Timeout:$label")
    }
}
