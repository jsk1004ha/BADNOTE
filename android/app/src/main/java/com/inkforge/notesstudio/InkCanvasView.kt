package com.inkforge.notesstudio

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class InkCanvasView(context:Context,private val repository:NoteRepository):View(context){
    enum class Tool{PEN,HIGHLIGHTER,ERASER,LASSO,HAND,TEXT,STICKY,IMAGE,SHAPE,TAPE,LASER}
    private enum class ContactState { IDLE,DRAWING,ERASING,SELECTING,PANNING,PINCHING,CANCELLED }
    var tool=Tool.PEN
        set(value){cancelContact();field=value;selection.clear();invalidate();onSelection(emptyList());onToolChanged()}
    var onToolChanged:()->Unit={}
    var onViewportChanged:()->Unit={}
    var brush="fountain"
    var inkColor="#172033"
    var inkWidth=4f
    var highlighterWidth=22f
    var eraserRadius=22f
    var preciseEraser=false
    var wholeEraser=false
    var shape="line"
    var stylusOnly=true
    var readOnly=false
    var scribbleErase=true
    var drawHold=true
    var telemetry=false
    var hudOpacity=1f
    private var telemetryText="펜 입력 대기"
    var continuous=true
    var ruler=false
    var rulerAngle=0f
    var penSettings=JSONObject()
    var onPageChanged:(Int)->Unit={}
    var onEdit:(String,ObjectChange,Map<String,Int>)->Unit={_,_,_->}
    var onInsert:(Tool,Float,Float)->Unit={_,_,_->}
    var onSelection:(List<JSONObject>)->Unit={}
    var onObjectTap:(JSONObject)->Unit={}
    var onStatus:(String)->Unit={}
    var onAppendPage:()->Unit={}
    private val pages=LinkedHashMap<String,NotePage>()
    private val pending=HashSet<String>()
    private sealed class Layer {
        data class Commands(val picture:Picture):Layer()
        data class Image(val objectData:JSONObject):Layer()
    }
    private val pictures=HashMap<String,List<Layer>>()
    private val spatialIndexes=HashMap<String,PageSpatialIndex>()
    private val histories=HashMap<String,EditHistory>()
    private val revisionClock=HashMap<String,Long>()
    private val captureSessionId=java.util.UUID.randomUUID().toString()
    val canUndo get()=currentPage?.id?.let{histories[it]?.canUndo}?:false
    val canRedo get()=currentPage?.id?.let{histories[it]?.canRedo}?:false
    private var pageIds:List<String> = emptyList()
    private var metadata:List<JSONObject> = emptyList()
    private var tops=FloatArray(0)
    var currentIndex=0;private set
    val currentPage get()=pageIds.getOrNull(currentIndex)?.let{pages[it]}
    private val background=BackgroundLoader(context,repository,{sceneChanged()},{onStatus(it)})
    private val renderer=NoteRenderer()
    private var frontBuffer:InkFrontSurface?=null
    private var sceneDirty=true
    private var frontContact=false
    private enum class DrawMode { LEGACY, SCENE, OVERLAY }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val selection=linkedSetOf<String>()
    private var zoom=1f
    private var panX=0f
    private var panY=0f
    private val margin get()=0f
    private val basePageWidth get()=min(880*resources.displayMetrics.density,(width-(if(resources.configuration.screenWidthDp<=840)18 else 104)*resources.displayMetrics.density).coerceAtLeast(width*.5f))
    val scale get()=(basePageWidth/1000f).coerceAtLeast(.1f)*zoom
    private val originX get()=(width-1000f*scale)/2+panX
    private val originY get()=margin+panY
    private var pointer=-1
    private var contactState=ContactState.IDLE
    private var contactPage:NotePage?=null
    private var points=mutableListOf<InkPoint>()
    private var contactTool:Tool?=null
    private var live:JSONObject?=null
    private var erasing=false
    private val stylusButtons=StylusButtonState()
    private var keyEraser=false
    private var eraserPoint:InkPoint?=null
    private val eraseBefore=linkedMapOf<String,JSONObject>()
    private val erasePositions=linkedMapOf<String,Int>()
    private val eraseAfter=linkedMapOf<String,JSONObject>()
    private val contactAdded=linkedSetOf<String>()
    private var moveBefore=emptyList<JSONObject>()
    private var moveStart:InkPoint?=null
    private var touchX=0f;private var touchY=0f;private var touchSpan=0f;private var touchTravel=0f
    private var touchStartTime=0L;private var maxTouches=0
    private var heldShape=false
    private val hold=Runnable{convertHeldShape()}

    init{isFocusable=true;isFocusableInTouchMode=true;setBackgroundColor(0xfff7f7f8.toInt());contentDescription="필기 페이지"}
    internal fun attachFrontBuffer(host:InkFrontSurface){frontBuffer=host;sceneDirty=true;invalidate()}
    internal fun frontBufferRecreated(){sceneChanged()}
    internal fun frontBufferPresented(presented:Boolean){
        if(presented&&contactState!=ContactState.IDLE&&!frontContact){frontBuffer?.hideForLegacy();return}
        setBackgroundColor(if(presented)Color.TRANSPARENT else 0xfff7f7f8.toInt());invalidate()
    }
    private fun sceneChanged(){sceneDirty=true;if(!frontContact)frontBuffer?.hideForLegacy();invalidate()}
    private fun recordScene():Picture?{
        if(width<=0||height<=0)return null
        return Picture().apply{val c=beginRecording(width,height);drawDocument(c,DrawMode.SCENE);endRecording()}
    }
    private fun publishScene(commitFront:Boolean=false){
        val picture=recordScene()?:return
        frontBuffer?.renderScene(picture,width,height,commitFront)
        sceneDirty=false
    }
    fun open(ids:List<String>,metas:List<JSONObject>,index:Int=0){
        cancelContact();pageIds=ids;metadata=metas;pages.clear();pictures.clear();spatialIndexes.clear();histories.clear();revisionClock.clear();pending.clear();selection.clear()
        currentIndex=index.coerceIn(0,(ids.size-1).coerceAtLeast(0));layoutPages();zoom=1f;panX=0f;panY=-(tops.getOrNull(currentIndex)?:0f)*scale
        requestPage(currentIndex);sceneChanged()
    }
    fun updatePages(ids:List<String>,metas:List<JSONObject>,index:Int){
        cancelContact();pageIds=ids;metadata=metas;pages.keys.retainAll(ids.toSet());pictures.clear();spatialIndexes.keys.retainAll(ids.toSet());layoutPages();goTo(index)
    }
    fun refreshPageList(ids:List<String>,metas:List<JSONObject>){
        val selected=pageIds.getOrNull(currentIndex)
        val oldTop=pageTop(currentIndex)
        pageIds=ids;metadata=metas;pages.keys.retainAll(ids.toSet());spatialIndexes.keys.retainAll(ids.toSet());layoutPages()
        currentIndex=selected?.let{ids.indexOf(it)}?.takeIf{it>=0}?:currentIndex.coerceIn(0,(ids.size-1).coerceAtLeast(0))
        panY+=(oldTop-pageTop(currentIndex))*scale
        requestPage(currentIndex);sceneChanged()
    }
    fun replacePage(page:NotePage){pages[page.id]=page;pictures.remove(page.id);spatialIndexes.remove(page.id);sceneChanged()}
    private fun indexFor(page:NotePage)=spatialIndexes.getOrPut(page.id){PageSpatialIndex().apply{rebuild(page.objects)}}
    private fun layoutPages(){var y=0f;tops=FloatArray(pageIds.size){i->val value=y;y+=metadata.getOrNull(i)?.f("height",1414f)?:1414f;y+=28f;value}}
    fun goTo(index:Int){
        if(pageIds.isEmpty())return
        cancelContact();setCurrent(index.coerceIn(0,pageIds.lastIndex));panY=-pageTop(currentIndex)*scale;panX=0f;requestPage(currentIndex);sceneChanged()
    }
    fun changePageMode(value:Boolean){continuous=value;goTo(currentIndex)}
    fun resetZoom(){cancelContact();zoom=1f;panX=0f;panY=-pageTop(currentIndex)*scale;pictures.clear();sceneChanged();onViewportChanged()}
    fun zoomBy(multiplier:Float){cancelContact();zoomAt(multiplier,width/2f,height/2f);sceneChanged()}
    fun zoomPercent()=(zoom*100).roundToInt()
    private fun zoomAt(factor:Float,x:Float,y:Float){val old=scale;val px=(x-originX)/old;val py=(y-originY)/old
        zoom=(zoom*factor).coerceIn(.08f,8f);panX=x-px*scale-(width-1000f*scale)/2;panY=y-py*scale-margin
        if((old>2.6f)!=(scale>2.6f))pictures.clear()
        onViewportChanged()}
    private fun pageTop(index:Int)=if(continuous)tops.getOrElse(index){0f} else 0f
    private fun setCurrent(index:Int){if(index!=currentIndex){currentIndex=index;selection.clear();onSelection(emptyList());onPageChanged(index)}}
    private fun requestPage(index:Int){
        val id=pageIds.getOrNull(index)?:return
        if(pages.containsKey(id)||!pending.add(id))return
        repository.executor.execute{
            val result=try{repository.page(id)}catch(_:Exception){null}
            post{pending.remove(id);if(id in pageIds&&result!=null){pages[id]=result;sceneChanged()}}
        }
    }
    private fun pageIndexAt(x:Float,y:Float):Int? {
        val docX=(x-originX)/scale;val docY=(y-originY)/scale
        if(docX<0||docX>1000)return null
        val range=if(continuous)pageIds.indices else currentIndex..currentIndex
        return range.firstOrNull{docY>=pageTop(it)&&docY<=pageTop(it)+(metadata.getOrNull(it)?.f("height",1414f)?:1414f)}
    }
    fun screenPoint(x:Float,y:Float,index:Int=currentIndex)=PointF(originX+x*scale,originY+(pageTop(index)+y)*scale)
    private fun point(event:MotionEvent,index:Int,history:Int=-1):InkPoint {
        val x=if(history>=0)event.getHistoricalX(index,history) else event.getX(index)
        val y=if(history>=0)event.getHistoricalY(index,history) else event.getY(index)
        val pressure=if(history>=0)event.getHistoricalPressure(index,history) else event.getPressure(index)
        val time=if(history>=0)event.getHistoricalEventTime(history) else event.eventTime
        val tilt=if(history>=0)event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,index,history) else event.getAxisValue(MotionEvent.AXIS_TILT,index)
        val orientation=if(history>=0)event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION,index,history) else event.getAxisValue(MotionEvent.AXIS_ORIENTATION,index)
        // Android orientation starts at screen-up; PointerEvent azimuth starts at screen-right.
        // A zero tilt has no directional projection, so leave web angles absent in that case.
        val angle=if(stylus(event,index)&&tilt>0f&&tilt<PI/2) orientation.toDouble() else null
        val azimuth=angle?.let{((it-PI/2+2*PI)%(2*PI)).toFloat()}
        val projected=angle?.let{tan(tilt.toDouble())}
        val tx=if(angle!=null&&projected!=null)Math.toDegrees(atan(projected*sin(angle))).toFloat() else null
        val ty=if(angle!=null&&projected!=null)Math.toDegrees(atan(-projected*cos(angle))).toFloat() else null
        val page=contactPage?:currentPage
        var px=((x-originX)/scale).coerceIn(0f,page?.width?:1000f)
        var py=((y-originY)/scale-pageTop(currentIndex)).coerceIn(0f,page?.height?:1414f)
        if(ruler&&(contactTool==Tool.PEN||contactTool==Tool.HIGHLIGHTER)&&points.isNotEmpty()){
            val first=points.first();val radians=rulerAngle*PI.toFloat()/180;val dx=cos(radians);val dy=sin(radians)
            val along=(px-first.x)*dx+(py-first.y)*dy;px=first.x+dx*along;py=first.y+dy*along
        }
        return InkPoint(px,py,pressure.coerceIn(0f,1.5f),time,tilt,orientation,event.getPointerId(index),
            azimuth,tx,ty)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){if(oldw>0&&(w!=oldw||h!=oldh))cancelContact()
        super.onSizeChanged(w,h,oldw,oldh);if(oldw==0)panY=-pageTop(currentIndex)*scale;sceneChanged()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val host=frontBuffer
        if(host!=null&&sceneDirty&&contactState==ContactState.IDLE)publishScene()
        drawDocument(canvas,if(host?.presented==true)DrawMode.OVERLAY else DrawMode.LEGACY)
    }
    private fun drawDocument(canvas:Canvas,mode:DrawMode){
        val scene=mode!=DrawMode.OVERLAY
        val overlay=mode!=DrawMode.SCENE
        if(mode==DrawMode.SCENE)canvas.drawColor(0xfff7f7f8.toInt())
        if(scene)background.beginFrame()
        if(pageIds.isEmpty()){if(scene)background.endFrame();return}
        val visible=mutableSetOf<String>()
        var nearest=currentIndex;var distance=Float.MAX_VALUE
        val range=if(continuous)pageIds.indices else currentIndex..currentIndex
        for(i in range){
            val top=originY+pageTop(i)*scale;val pageHeight=metadata.getOrNull(i)?.f("height",1414f)?:1414f
            if(top>height+500||top+pageHeight*scale< -500)continue
            val id=pageIds[i];visible+=id;if(scene)requestPage(i)
            val d=abs((top+min(pageHeight*scale/2,height/2f))-height/2f);if(d<distance){nearest=i;distance=d}
            canvas.save();canvas.translate(originX,top);canvas.scale(scale,scale)
            if(scene){paint.color=Color.WHITE;paint.style=Paint.Style.FILL;canvas.drawRect(0f,0f,1000f,pageHeight,paint)}
            val page=pages[id]
            if(page!=null){
                canvas.save();canvas.clipRect(0f,0f,page.width,page.height)
                if(scene){
                    renderer.template(canvas,page)
                    background.draw(canvas,page,RectF(-originX/scale,-top/scale,(width-originX)/scale,(height-top)/scale),scale)
                    val layers=pictures.getOrPut(id){recordLayers(page)}
                    val visibleRect=InkBounds(-originX/scale,-top/scale,(width-originX)/scale,(height-top)/scale)
                    for(layer in layers)when(layer){
                        is Layer.Commands->canvas.drawPicture(layer.picture)
                        is Layer.Image->{val obj=layer.objectData;val b=InkGeometry.bounds(obj)
                            if(b.overlaps(visibleRect))background.image(obj.optString("src"),(max(b.width,b.height)*scale).roundToInt(),id)?.let{bitmap->
                                renderer.drawImage(canvas,obj,bitmap)
                            }}
                    }
                }
                if(overlay&&contactPage?.id==id){
                    live?.takeUnless{mode==DrawMode.OVERLAY&&frontContact}?.let{if(it.optString("type")=="stroke")renderer.stroke(canvas,it,points,scale.toDouble())
                        else renderer.draw(canvas,it,scale.toDouble())}
                    if(contactTool==Tool.LASSO&&moveBefore.isEmpty()&&points.isNotEmpty()){
                        paint.color=0xff1976d2.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=2/scale;paint.pathEffect=DashPathEffect(floatArrayOf(7/scale,5/scale),0f)
                        val path=Path();points.forEachIndexed{index,p->if(index==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)};canvas.drawPath(path,paint);paint.pathEffect=null;paint.style=Paint.Style.FILL
                    }
                }
                if(overlay&&i==currentIndex){
                    paint.color=0xff1976d2.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f/scale
                    selected().forEach{val b=InkGeometry.bounds(it).expanded(4/scale);canvas.drawRect(b.left,b.top,b.right,b.bottom,paint)}
                    eraserPoint?.let{canvas.drawCircle(it.x,it.y,eraserRadius/scale,paint)}
                    if(ruler){paint.color=0x80648198.toInt();canvas.drawLine(40f,120f,950f,120f,paint)}
                    paint.style=Paint.Style.FILL
                }
                canvas.restore()
            }else if(scene){paint.color=0xff81909e.toInt();paint.textSize=24f;canvas.drawText("페이지를 불러오는 중…",50f,80f,paint)}
            if(overlay&&i==currentIndex){paint.color=0x401397ed;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density/scale;canvas.drawRect(0f,0f,1000f,pageHeight,paint);paint.style=Paint.Style.FILL}
            canvas.restore()
        }
        if(scene)background.endFrame()
        if(overlay&&telemetry){
            val density=resources.displayMetrics.density
            paint.style=Paint.Style.FILL;paint.color=0xb326292e.toInt();paint.alpha=(179*hudOpacity).toInt()
            canvas.drawRoundRect(RectF(14*density,height-92*density,340*density,height-58*density),17*density,17*density,paint)
            paint.color=Color.WHITE;paint.alpha=(255*hudOpacity).toInt();paint.textSize=12*density
            canvas.drawText(telemetryText,27*density,height-70*density,paint);paint.alpha=255
        }
        if(scene&&pointer<0&&nearest!=currentIndex)post{if(pointer<0)setCurrent(nearest)}
        // No unbounded canvas, bitmap, or page-object cache in a 1,000-page document.
        if(scene&&pages.size>7){pages.keys.toList().filter{it !in visible&&it!=contactPage?.id&&it!=pageIds.getOrNull(currentIndex)}.forEach{pages.remove(it);pictures.remove(it);spatialIndexes.remove(it)}}
    }
    private fun recordLayers(page:NotePage):List<Layer>{
        val layers=mutableListOf<Layer>()
        var picture=Picture();var count=0
        var canvas=picture.beginRecording(ceil(page.width).toInt(),ceil(page.height).toInt())
        fun flush(){picture.endRecording();if(count>0)layers+=Layer.Commands(picture);count=0}
        page.objects.filter{!it.optBoolean("hidden")}.forEach{obj->
            if(obj.optString("type")=="image"){
                flush();layers+=Layer.Image(obj);picture=Picture();canvas=picture.beginRecording(ceil(page.width).toInt(),ceil(page.height).toInt())
            }else{renderer.draw(canvas,obj,scale.toDouble());count++}
        }
        flush();return layers
    }
    private fun stylus(event:MotionEvent,index:Int)=event.getToolType(index)==MotionEvent.TOOL_TYPE_STYLUS||event.getToolType(index)==MotionEvent.TOOL_TYPE_ERASER
    private fun updateButtons(event:MotionEvent){
        val action=event.actionMasked
        stylusButtons.update(action,event.buttonState,if(action==MotionEvent.ACTION_BUTTON_PRESS||action==MotionEvent.ACTION_BUTTON_RELEASE)event.actionButton else 0)
    }
    private fun buttonEraser(event:MotionEvent,index:Int)=event.getToolType(index)==MotionEvent.TOOL_TYPE_ERASER||keyEraser||stylusButtons.getButtons()!=0
    fun barrelKey(held:Boolean){
        keyEraser=held
        val p=eraserPoint?:points.lastOrNull()
        if(pointer>=0&&p!=null)transitionContact(held||tool==Tool.ERASER,p)
        invalidate()
    }
    override fun onGenericMotionEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS||event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE){
            updateButtons(event)
            if(pointer>=0){val index=event.findPointerIndex(pointer);if(index>=0){val p=point(event,index);val erase=buttonEraser(event,index)||tool==Tool.ERASER
                transitionContact(erase,p)}}
            invalidate();return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onHoverEvent(event:MotionEvent):Boolean{
        updateButtons(event)
        if(event.actionMasked==MotionEvent.ACTION_HOVER_EXIT){eraserPoint=null;invalidate();return true}
        val index=event.actionIndex
        if(stylus(event,index)&&buttonEraser(event,index)){
            pageIndexAt(event.x,event.y)?.let{if(it==currentIndex){eraserPoint=point(event,index);invalidate()}}
        }else{eraserPoint=null;invalidate()}
        return true
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        if(telemetry)telemetryText="${if(stylus(event,0))"펜"else"터치"} · 압력 ${String.format(java.util.Locale.US,"%.2f",event.pressure)} · 기울기 ${Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT).toDouble()).roundToInt()}°"
        requestFocus();parent?.requestDisallowInterceptTouchEvent(true)
        val action=event.actionMasked;val index=event.actionIndex
        if(contactState==ContactState.CANCELLED&&action!=MotionEvent.ACTION_DOWN)return true
        if(action==MotionEvent.ACTION_DOWN&&contactState==ContactState.CANCELLED)contactState=ContactState.IDLE
        if((0 until event.pointerCount).any{stylus(event,it)})updateButtons(event)
        if(action==MotionEvent.ACTION_CANCEL){cancelContact();maxTouches=0;return true}
        if(pointer>=0){
            val pIndex=event.findPointerIndex(pointer)
            if(pIndex<0){cancelContact();return true}
            if(action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(index)!=pointer)return true
            if(action==MotionEvent.ACTION_POINTER_DOWN)return true // Palm/finger while writing cannot change the viewport.
            if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&Build.VERSION.SDK_INT>=33&&event.flags and MotionEvent.FLAG_CANCELED!=0){cancelContact();return true}
            if(action==MotionEvent.ACTION_MOVE){
                requestUnbufferedDispatch(event)
                val p=point(event,pIndex)
                // Batched samples precede this event's button state. Consume them in the old mode.
                for(h in 0 until event.historySize)consumePoint(point(event,pIndex,h))
                val shouldErase=tool==Tool.ERASER||buttonEraser(event,pIndex)
                if(shouldErase!=erasing)transitionContact(shouldErase,p) else consumePoint(p)
                invalidate();return true
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
                for(h in 0 until event.historySize)consumePoint(point(event,pIndex,h))
                val p=point(event,pIndex)
                val shouldErase=tool==Tool.ERASER||buttonEraser(event,pIndex)
                if(shouldErase!=erasing)transitionContact(shouldErase,p) else consumePoint(p)
                finishContact();performClick();return true
            }
            return true
        }
        if((action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)&&
            (stylus(event,index)||(!stylusOnly&&event.pointerCount==1&&tool!=Tool.HAND))&&!readOnly){
            val pageIndex=pageIndexAt(event.getX(index),event.getY(index))?:return true
            setCurrent(pageIndex);requestPage(pageIndex)
            val page=currentPage?:return true
            if(tool==Tool.HAND)return handlePan(event)
            pointer=event.getPointerId(index);contactPage=page;requestUnbufferedDispatch(event)
            eraseBefore.clear();eraseAfter.clear();erasePositions.clear();erasePositions.putAll(positions(page));contactAdded.clear()
            val p=point(event,index)
            if(tool==Tool.ERASER||buttonEraser(event,index))beginErase(p) else beginInk(p)
            invalidate();return true
        }
        return handlePan(event)
    }
    private fun handlePan(event:MotionEvent):Boolean{
        val count=event.pointerCount
        var cx=0f;var cy=0f;for(i in 0 until count){cx+=event.getX(i);cy+=event.getY(i)};cx/=count;cy/=count
        val span=if(count>=2)hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1)) else 0f
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{contactState=ContactState.PANNING;touchX=cx;touchY=cy;touchSpan=span;touchTravel=0f;touchStartTime=event.eventTime;maxTouches=1}
            MotionEvent.ACTION_POINTER_DOWN->{contactState=ContactState.PINCHING;touchX=cx;touchY=cy;touchSpan=span;maxTouches=max(maxTouches,count)}
            MotionEvent.ACTION_MOVE->{val dx=cx-touchX;val dy=cy-touchY;touchTravel+=hypot(dx,dy)
                if(count>=2&&touchSpan>0)zoomAt(span/touchSpan,cx,cy)
                panX+=dx;panY+=dy;touchX=cx;touchY=cy;touchSpan=span;sceneChanged()}
            MotionEvent.ACTION_POINTER_UP->{contactState=ContactState.PANNING;touchSpan=0f;val remaining=(0 until count).filter{it!=event.actionIndex}
                if(remaining.isNotEmpty()){touchX=remaining.sumOf{event.getX(it).toDouble()}.toFloat()/remaining.size;touchY=remaining.sumOf{event.getY(it).toDouble()}.toFloat()/remaining.size}}
            MotionEvent.ACTION_UP->{
                if(touchTravel<18*resources.displayMetrics.density&&event.eventTime-touchStartTime<280){
                    if(maxTouches==2&&!readOnly)undo() else if(maxTouches>=3&&!readOnly)redo()
                    else pageIndexAt(event.x,event.y)?.let{i->setCurrent(i);val p=point(event,0);val obj=currentPage?.objects?.lastOrNull{InkGeometry.bounds(it).contains(p.x,p.y)}
                        if(obj?.optString("type")=="tape")toggleTape(obj) else if(obj!=null)onObjectTap(obj)}
                }
                if(!continuous&&abs(panY)>height*.65f&&zoom<=1.05f){val direction=if(panY<0)1 else -1;goTo(currentIndex+direction)}
                maxTouches=0;contactState=ContactState.IDLE;sceneChanged()
            }
        };return true
    }
    private fun beginInk(p:InkPoint){
        erasing=false;eraserPoint=null;points=mutableListOf(p);heldShape=false;contactTool=tool
        contactState=if(tool==Tool.LASSO)ContactState.SELECTING else ContactState.DRAWING
        val page=contactPage?:return
        val cssWidth=(basePageWidth/resources.displayMetrics.density).toDouble().coerceAtLeast(1.0)
        val screenWidth=if(tool==Tool.HIGHLIGHTER)highlighterWidth else inkWidth
        val pageWidth=LegacyBrush.toolStrokeWidthToPage(cssWidth,screenWidth.toDouble())
        when(tool){
            Tool.PEN,Tool.HIGHLIGHTER,Tool.LASER->live=json("id" to uid("stroke"),"type" to "stroke","brush" to if(tool==Tool.HIGHLIGHTER)"highlighter" else brush,
                "color" to if(tool==Tool.HIGHLIGHTER&&inkColor=="#172033")"#ffe066" else inkColor,
                "width" to pageWidth,"screenWidth" to screenWidth,
                "opacity" to if(tool==Tool.HIGHLIGHTER).32f else 1f,"settings" to penSettings.copyJson(),"createdAt" to System.currentTimeMillis(),
                "captureSeq" to repository.reserveCaptureSequence(page.id),"captureSessionId" to captureSessionId,
                "timeBasis" to "session-monotonic","revision" to 1)
            Tool.SHAPE->live=json("id" to uid("shape"),"type" to "shape","shape" to shape,"color" to inkColor,
                "width" to pageWidth,"screenWidth" to inkWidth,"x1" to p.x,"y1" to p.y,"x2" to p.x,"y2" to p.y)
            Tool.TAPE->live=json("id" to uid("tape"),"type" to "tape","color" to "#4c91dd","x" to p.x,"y" to p.y,"w" to 1,"h" to 1)
            Tool.LASSO->{moveBefore=selected().filter{!it.optBoolean("locked")}.takeIf{list->list.any{InkGeometry.bounds(it).expanded(10/scale).contains(p.x,p.y)}}?.map{it.copyJson()}?:emptyList()
                moveStart=if(moveBefore.isNotEmpty())p else null
                if(moveBefore.isEmpty()){selection.clear();onSelection(emptyList())}}
            Tool.TEXT,Tool.STICKY,Tool.IMAGE->{onInsert(tool,p.x,p.y);pointer=-1;contactPage=null;points.clear()}
            else->Unit
        }
        val stroke=live
        val host=frontBuffer
        frontContact=stroke!=null&&tool==Tool.PEN&&host?.presented==true&&frontEligible(stroke)&&
            host.renderFront(frontFrame(stroke))
        if(!frontContact)host?.hideForLegacy()
    }
    private fun frontEligible(stroke:JSONObject):Boolean{
        val name=stroke.optString("brush")
        return name in setOf("fountain","ballpoint")&&stroke.f("opacity",1f)==1f&&
            Color.alpha(renderer.color(stroke.optString("color","#172033")))==255
    }
    private fun frontFrame(stroke:JSONObject)=InkFrontFrame(
        stroke.copyJson().put("points",JSONArray(points.map{it.json()})).toString(),
        originX,originY+pageTop(currentIndex)*scale,scale,
        contactPage?.width?:1000f,contactPage?.height?:1414f)
    private fun updateFront(){
        if(frontContact){val stroke=live
            if(stroke==null||frontBuffer?.renderFront(frontFrame(stroke))!=true){
                frontBuffer?.cancelFront();frontContact=false;frontBuffer?.hideForLegacy()
            }
        }
    }
    private fun movePoint(p:InkPoint){
        val page=contactPage?:return
        if(heldShape)return
        when(contactTool){
            Tool.PEN,Tool.HIGHLIGHTER,Tool.LASER->{
                val moved=points.isEmpty()||InkGeometry.distance(points.last(),p)*scale>.25f
                points+=p
                if(moved){
                    removeCallbacks(hold);if(drawHold&&contactTool==Tool.PEN&&!ruler)postDelayed(hold,650)
                }
                updateFront()
            }
            Tool.SHAPE->{live?.put("x2",p.x)?.put("y2",p.y)}
            Tool.TAPE->{val a=points.first();live?.put("x",min(a.x,p.x))?.put("y",min(a.y,p.y))?.put("w",abs(a.x-p.x))?.put("h",abs(a.y-p.y))}
            Tool.LASSO->{val start=moveStart
                if(start!=null){val transformed=moveBefore.map{InkGeometry.transform(it,p.x-start.x,p.y-start.y)}
                    ObjectChange(moveBefore,transformed,afterPositions=positions(page)).apply(page);pictures.remove(page.id);spatialIndexes.remove(page.id)
                }else if(points.isEmpty()||InkGeometry.distance(points.last(),p)*scale>2)points+=p}
            else->Unit
        }
    }
    private fun convertHeldShape(){
        if(pointer<0||erasing||contactTool!=Tool.PEN||points.size<5)return
        if(scribbleErase&&InkGeometry.deliberateScratch(points,scale/resources.displayMetrics.density))return
        val recognized=InkGeometry.recognizeShape(points)?:return
        if(frontContact){frontBuffer?.cancelFront();frontContact=false;frontBuffer?.hideForLegacy()}
        val a=points.first();val b=points.last()
        val x1=if(recognized=="line")a.x else points.minOf{it.x};val y1=if(recognized=="line")a.y else points.minOf{it.y}
        val x2=if(recognized=="line")b.x else points.maxOf{it.x};val y2=if(recognized=="line")b.y else points.maxOf{it.y}
        live=json("id" to uid("shape"),"type" to "shape","shape" to recognized,"color" to inkColor,"width" to inkWidth,"x1" to x1,"y1" to y1,"x2" to x2,"y2" to y2)
        heldShape=true;invalidate()
    }
    private fun beginErase(p:InkPoint){
        frontBuffer?.hideForLegacy()
        erasing=true;contactTool=Tool.ERASER;contactState=ContactState.ERASING;eraserPoint=null;eraseAlong(p)
    }
    private fun consumePoint(p:InkPoint){if(erasing)eraseAlong(p) else movePoint(p)}
    private fun transitionContact(toEraser:Boolean,p:InkPoint){
        if(pointer<0||toEraser==erasing||tool !in setOf(Tool.PEN,Tool.HIGHLIGHTER,Tool.ERASER,Tool.LASER))return
        if(toEraser){if(frontContact){frontBuffer?.cancelFront();frontContact=false;frontBuffer?.hideForLegacy()};finalizeInkSegment();beginErase(p)}
        else{erasing=false;eraserPoint=null;beginInk(p)}
    }
    private fun eraseAlong(p:InkPoint){
        val old=eraserPoint;val steps=if(old==null)1 else ceil(InkGeometry.distance(old,p)/(eraserRadius/scale*.4f)).toInt().coerceAtLeast(1)
        for(step in 1..steps){val t=step.toFloat()/steps;eraseAt(if(old==null)p else p.copy(x=old.x+(p.x-old.x)*t,y=old.y+(p.y-old.y)*t))}
        eraserPoint=p
    }
    private fun stageReplace(page:NotePage,obj:JSONObject,fragments:List<JSONObject>){
        val id=obj.getString("id");val pos=page.objects.indexOfFirst{it.optString("id")==id}
        if(pos<0)return
        if(id !in contactAdded && id !in eraseBefore)eraseBefore[id]=obj.copyJson()
        contactAdded.remove(id);eraseAfter.remove(id)
        page.objects.removeAt(pos);indexFor(page).remove(id)
        page.objects.addAll(pos,fragments)
        fragments.forEach { fragment -> val nextId=fragment.getString("id")
            if(nextId !in eraseBefore)contactAdded.add(nextId)
            eraseAfter[nextId]=fragment.copyJson();indexFor(page).put(fragment)
        }
    }
    private fun eraseAt(p:InkPoint){
        val page=contactPage?:return;val radius=eraserRadius/scale
        val area=InkBounds(p.x-radius,p.y-radius,p.x+radius,p.y+radius)
        val hit=indexFor(page).query(area).filter{obj->
            !obj.optBoolean("locked")&&!obj.optBoolean("hidden")&&
                !(obj.optString("type")=="stroke"&&obj.optString("brush")=="highlighter")&&
                (wholeEraser&&tool==Tool.ERASER||obj.optString("type")=="stroke")&&
                InkGeometry.hit(obj,p,radius)
        }
        if(hit.isEmpty())return
        for(obj in hit){val fragments=if(preciseEraser&&obj.optString("type")=="stroke")InkGeometry.eraseParts(obj,p,radius)else emptyList()
            if(fragments.size==1&&fragments[0].optString("id")==obj.optString("id")&&
                fragments[0].f("rotation")==obj.f("rotation")){
                val old=InkGeometry.points(obj);val next=InkGeometry.points(fragments[0])
                if(old.size==next.size&&old.zip(next).all{(a,b)->InkGeometry.distance(a,b)<.001f})continue
            }
            stageReplace(page,obj,fragments)
        }
        pictures.remove(page.id)
    }
    private fun finalizeInkSegment(){
        removeCallbacks(hold)
        val page=contactPage?:return
        val obj=live ?: return
        if(contactTool!=Tool.LASER){
            if(obj.optString("type")=="stroke")obj.put("points",JSONArray(points.map{it.json()}))
            val scratch=if(scribbleErase&&contactTool==Tool.PEN&&!heldShape)
                InkGeometry.scratchTargets(page.objects,points,scale/resources.displayMetrics.density)else emptyList()
            if(scratch.isNotEmpty()){
                scratch.forEach{stageReplace(page,it,emptyList())}
                onStatus("${scratch.size}개 항목을 지웠습니다.")
            }else{
                val copy=obj.copyJson();page.objects.add(copy);contactAdded.add(copy.getString("id"));indexFor(page).put(copy)
            }
            pictures.remove(page.id)
        }
        live=null;points.clear();heldShape=false
    }
    fun finishContact(){
        removeCallbacks(hold);val page=contactPage
        if(page!=null){
            if(contactTool==Tool.LASSO){
                if(moveBefore.isNotEmpty()){
                    val changed=page.objects.filter{obj->moveBefore.any{it.optString("id")==obj.optString("id")}}.map{it.copyJson()}
                    if(changed.toString()!=moveBefore.toString())record(page,ObjectChange(moveBefore,changed,positions(page),positions(page)),false)
                }else if(points.size>2){
                    page.objects.filter{obj->val b=InkGeometry.bounds(obj)
                        if(obj.optString("type")=="stroke")InkGeometry.points(obj).any{InkGeometry.inside(it,points)}
                        else InkGeometry.inside(InkPoint((b.left+b.right)/2,(b.top+b.bottom)/2),points)}.forEach{selection+=it.getString("id")}
                }
                onSelection(selected())
            }else{
                if(!erasing)finalizeInkSegment()
                if(eraseBefore.isNotEmpty()||contactAdded.isNotEmpty()){
                    val affected=eraseBefore.keys+contactAdded
                    val after=page.objects.filter{it.optString("id") in affected}.map{it.copyJson()}
                    record(page,ObjectChange(eraseBefore.values.map{it.copyJson()},after,erasePositions.toMap(),positions(page)),false)
                }
            }
        }
        if(frontContact){publishScene(commitFront=true);frontContact=false}
        else if(frontBuffer!=null)sceneChanged()
        resetContact(ContactState.IDLE)
    }
    fun cancelContact(){
        removeCallbacks(hold);val page=contactPage;val hadContact=pointer>=0||page!=null
        val cancelledFront=frontContact
        if(cancelledFront)frontBuffer?.cancelFront()
        frontContact=false
        if(page!=null){
            if(eraseBefore.isNotEmpty()||contactAdded.isNotEmpty()){
                val affected=eraseBefore.keys+contactAdded
                val current=page.objects.filter{it.optString("id") in affected}.map{it.copyJson()}
                ObjectChange(current,eraseBefore.values.map{it.copyJson()},afterPositions=erasePositions).apply(page)
            }
            if(moveBefore.isNotEmpty())ObjectChange(moveBefore,moveBefore,afterPositions=positions(page)).apply(page)
            pictures.remove(page.id);spatialIndexes.remove(page.id)
        }
        if(hadContact&&!cancelledFront)sceneChanged()
        resetContact(if(pointer>=0)ContactState.CANCELLED else ContactState.IDLE)
    }
    private fun resetContact(state:ContactState){
        eraseBefore.clear();eraseAfter.clear();erasePositions.clear();contactAdded.clear();erasing=false
        pointer=-1;contactPage=null;points.clear();live=null;moveBefore=emptyList();moveStart=null
        contactTool=null;heldShape=false;eraserPoint=null;contactState=state;invalidate()
    }
    private fun positions(page:NotePage)=page.objects.mapIndexed{i,obj->obj.getString("id") to i}.toMap()
    private fun advanceRevisions(page:NotePage,change:ObjectChange){
        val previous=change.before.associateBy{it.optString("id")}
        change.after.forEach{after->val id=after.optString("id")
            val next=maxOf(revisionClock[id]?:0,previous[id]?.optLong("revision",0)?:0,after.optLong("revision",1)-1)+1
            revisionClock[id]=next;after.put("revision",next)
            page.objects.firstOrNull{it.optString("id")==id}?.put("revision",next)
        }
    }
    private fun record(page:NotePage,change:ObjectChange,apply:Boolean=true){
        if(apply)change.apply(page)
        advanceRevisions(page,change)
        histories.getOrPut(page.id){EditHistory(8*1024*1024)}.push(change)
        if(histories.size>4)histories.keys.firstOrNull{it!=page.id}?.let{histories.remove(it)}
        pictures.remove(page.id);spatialIndexes.remove(page.id);onEdit(page.id,change,positions(page));sceneChanged()
    }
    fun selected()=currentPage?.objects?.filter{it.optString("id") in selection}?:emptyList()
    fun selectAll(){selection.clear();currentPage?.objects?.filter{!it.optBoolean("hidden")&&it.optString("type")!="ocrIndex"}?.forEach{selection+=it.getString("id")};onSelection(selected());invalidate()}
    fun add(obj:JSONObject){if(readOnly)return;val page=currentPage?:return;record(page,ObjectChange(emptyList(),listOf(obj.copyJson())))}
    fun changeObjects(before:List<JSONObject>,after:List<JSONObject>){if(readOnly)return;val page=currentPage?:return;val pos=positions(page)
        record(page,ObjectChange(before.map{it.copyJson()},after.map{it.copyJson()},pos,pos));selection.retainAll(page.objects.map{it.getString("id")}.toSet());onSelection(selected())}
    fun deleteSelection(){changeObjects(selected().filter{!it.optBoolean("locked")},emptyList())}
    fun bringSelectionToFront(){
        if(readOnly)return;val page=currentPage?:return;val list=selected().filter{!it.optBoolean("locked")};if(list.isEmpty())return
        val before=positions(page);val moved=list.map{it.optString("id")}.toSet()
        val order=page.objects.filter{it.optString("id") !in moved}+list
        val after=order.mapIndexed{i,obj->obj.getString("id") to i}.toMap()
        record(page,ObjectChange(list.map{it.copyJson()},list.map{it.copyJson()},before,after));onSelection(selected())
    }
    fun scaleSelection(factor:Float){val list=selected().filter{!it.optBoolean("locked")};if(list.isEmpty())return
        val boxes=list.map{InkGeometry.bounds(it)};val cx=boxes.minOf{it.left};val cy=boxes.minOf{it.top}
        changeObjects(list,list.map{InkGeometry.transform(it,0f,0f,factor,cx,cy)})}
    fun toggleTape(obj:JSONObject){if(!readOnly)changeObjects(listOf(obj),listOf(obj.copyJson().put("revealed",!obj.optBoolean("revealed"))))}
    fun undo(){if(readOnly)return;val page=currentPage?:return;val change=histories[page.id]?.undo()?:return;change.apply(page);advanceRevisions(page,change);pictures.remove(page.id);spatialIndexes.remove(page.id);onEdit(page.id,change,positions(page));selection.clear();onSelection(emptyList());sceneChanged()}
    fun redo(){if(readOnly)return;val page=currentPage?:return;val change=histories[page.id]?.redo()?:return;change.apply(page);advanceRevisions(page,change);pictures.remove(page.id);spatialIndexes.remove(page.id);onEdit(page.id,change,positions(page));selection.clear();onSelection(emptyList());sceneChanged()}
    fun close(){cancelContact();frontBuffer?.hideForLegacy();frontBuffer=null;background.close()}
    override fun performClick():Boolean {super.performClick();return true}
}
