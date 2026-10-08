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
    private val histories=HashMap<String,EditHistory>()
    val canUndo get()=currentPage?.id?.let{histories[it]?.canUndo}?:false
    val canRedo get()=currentPage?.id?.let{histories[it]?.canRedo}?:false
    private var pageIds:List<String> = emptyList()
    private var metadata:List<JSONObject> = emptyList()
    private var tops=FloatArray(0)
    var currentIndex=0;private set
    val currentPage get()=pageIds.getOrNull(currentIndex)?.let{pages[it]}
    private val background=BackgroundLoader(repository){invalidate()}
    private val renderer=NoteRenderer()
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
    private var moveBefore=emptyList<JSONObject>()
    private var moveStart:InkPoint?=null
    private var touchX=0f;private var touchY=0f;private var touchSpan=0f;private var touchTravel=0f
    private var touchStartTime=0L;private var maxTouches=0
    private var heldShape=false
    private val hold=Runnable{convertHeldShape()}

    init{isFocusable=true;isFocusableInTouchMode=true;setBackgroundColor(0xfff7f7f8.toInt());contentDescription="필기 페이지"}
    fun open(ids:List<String>,metas:List<JSONObject>,index:Int=0){
        cancelContact();pageIds=ids;metadata=metas;pages.clear();pictures.clear();histories.clear();pending.clear();selection.clear()
        currentIndex=index.coerceIn(0,(ids.size-1).coerceAtLeast(0));layoutPages();zoom=1f;panX=0f;panY=-(tops.getOrNull(currentIndex)?:0f)*scale
        requestPage(currentIndex);invalidate()
    }
    fun updatePages(ids:List<String>,metas:List<JSONObject>,index:Int){
        cancelContact();pageIds=ids;metadata=metas;pages.keys.retainAll(ids.toSet());pictures.clear();layoutPages();goTo(index)
    }
    fun replacePage(page:NotePage){pages[page.id]=page;pictures.remove(page.id);invalidate()}
    private fun layoutPages(){var y=0f;tops=FloatArray(pageIds.size){i->val value=y;y+=metadata.getOrNull(i)?.f("height",1414f)?:1414f;y+=28f;value}}
    fun goTo(index:Int){
        if(pageIds.isEmpty())return
        finishContact();setCurrent(index.coerceIn(0,pageIds.lastIndex));panY=-pageTop(currentIndex)*scale;panX=0f;requestPage(currentIndex);invalidate()
    }
    fun changePageMode(value:Boolean){continuous=value;goTo(currentIndex)}
    fun resetZoom(){zoom=1f;panX=0f;panY=-pageTop(currentIndex)*scale;invalidate();onViewportChanged()}
    fun zoomBy(multiplier:Float){zoomAt(multiplier,width/2f,height/2f);invalidate()}
    fun zoomPercent()=(zoom*100).roundToInt()
    private fun zoomAt(factor:Float,x:Float,y:Float){val old=scale;val px=(x-originX)/old;val py=(y-originY)/old
        zoom=(zoom*factor).coerceIn(.08f,8f);panX=x-px*scale-(width-1000f*scale)/2;panY=y-py*scale-margin;onViewportChanged()}
    private fun pageTop(index:Int)=if(continuous)tops.getOrElse(index){0f} else 0f
    private fun setCurrent(index:Int){if(index!=currentIndex){currentIndex=index;selection.clear();onSelection(emptyList());onPageChanged(index)}}
    private fun requestPage(index:Int){
        val id=pageIds.getOrNull(index)?:return
        if(pages.containsKey(id)||!pending.add(id))return
        repository.executor.execute{
            val result=try{repository.page(id)}catch(_:Exception){null}
            post{pending.remove(id);if(id in pageIds&&result!=null){pages[id]=result;invalidate()}}
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
        val page=contactPage?:currentPage
        var px=((x-originX)/scale).coerceIn(0f,page?.width?:1000f)
        var py=((y-originY)/scale-pageTop(currentIndex)).coerceIn(0f,page?.height?:1414f)
        if(ruler&&(contactTool==Tool.PEN||contactTool==Tool.HIGHLIGHTER)&&points.isNotEmpty()){
            val first=points.first();val radians=rulerAngle*PI.toFloat()/180;val dx=cos(radians);val dy=sin(radians)
            val along=(px-first.x)*dx+(py-first.y)*dy;px=first.x+dx*along;py=first.y+dy*along
        }
        return InkPoint(px,py,pressure.coerceIn(.01f,1.5f),time,tilt,orientation)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(oldw==0)panY=-pageTop(currentIndex)*scale}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(pageIds.isEmpty())return
        val visible=mutableSetOf<String>()
        var nearest=currentIndex;var distance=Float.MAX_VALUE
        val range=if(continuous)pageIds.indices else currentIndex..currentIndex
        for(i in range){
            val top=originY+pageTop(i)*scale;val pageHeight=metadata.getOrNull(i)?.f("height",1414f)?:1414f
            if(top>height+500||top+pageHeight*scale< -500)continue
            val id=pageIds[i];visible+=id;requestPage(i)
            val d=abs((top+min(pageHeight*scale/2,height/2f))-height/2f);if(d<distance){nearest=i;distance=d}
            canvas.save();canvas.translate(originX,top);canvas.scale(scale,scale)
            paint.color=Color.WHITE;paint.style=Paint.Style.FILL;canvas.drawRect(0f,0f,1000f,pageHeight,paint)
            val page=pages[id]
            if(page!=null){
                canvas.save();canvas.clipRect(0f,0f,page.width,page.height)
                renderer.template(canvas,page)
                background.draw(canvas,page,RectF(-originX/scale,-top/scale,(width-originX)/scale,(height-top)/scale),scale)
                val layers=pictures.getOrPut(id){recordLayers(page)}
                val visibleRect=InkBounds(-originX/scale,-top/scale,(width-originX)/scale,(height-top)/scale)
                for(layer in layers)when(layer){
                    is Layer.Commands->canvas.drawPicture(layer.picture)
                    is Layer.Image->{val obj=layer.objectData;val b=InkGeometry.bounds(obj)
                        if(b.overlaps(visibleRect))background.image(obj.optString("src"),(max(b.width,b.height)*scale).roundToInt())?.let{bitmap->
                            paint.alpha=(obj.f("opacity",1f)*255).roundToInt().coerceIn(0,255);canvas.drawBitmap(bitmap,null,RectF(b.left,b.top,b.right,b.bottom),paint);paint.alpha=255
                        }}
                }
                if(contactPage?.id==id){
                    live?.let{if(it.optString("type")=="stroke")renderer.stroke(canvas,it,points) else renderer.draw(canvas,it)}
                    if(contactTool==Tool.LASSO&&moveBefore.isEmpty()&&points.isNotEmpty()){
                        paint.color=0xff1976d2.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=2/scale;paint.pathEffect=DashPathEffect(floatArrayOf(7/scale,5/scale),0f)
                        val path=Path();points.forEachIndexed{index,p->if(index==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)};canvas.drawPath(path,paint);paint.pathEffect=null;paint.style=Paint.Style.FILL
                    }
                }
                if(i==currentIndex){
                    paint.color=0xff1976d2.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f/scale
                    selected().forEach{val b=InkGeometry.bounds(it).expanded(4/scale);canvas.drawRect(b.left,b.top,b.right,b.bottom,paint)}
                    eraserPoint?.let{canvas.drawCircle(it.x,it.y,eraserRadius/scale,paint)}
                    if(ruler){paint.color=0x80648198.toInt();canvas.drawLine(40f,120f,950f,120f,paint)}
                    paint.style=Paint.Style.FILL
                }
                canvas.restore()
            }else{paint.color=0xff81909e.toInt();paint.textSize=24f;canvas.drawText("페이지를 불러오는 중…",50f,80f,paint)}
            if(i==currentIndex){paint.color=0x401397ed;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density/scale;canvas.drawRect(0f,0f,1000f,pageHeight,paint);paint.style=Paint.Style.FILL}
            canvas.restore()
        }
        if(telemetry){
            val density=resources.displayMetrics.density
            paint.style=Paint.Style.FILL;paint.color=0xb326292e.toInt();paint.alpha=(179*hudOpacity).toInt()
            canvas.drawRoundRect(RectF(14*density,height-92*density,340*density,height-58*density),17*density,17*density,paint)
            paint.color=Color.WHITE;paint.alpha=(255*hudOpacity).toInt();paint.textSize=12*density
            canvas.drawText(telemetryText,27*density,height-70*density,paint);paint.alpha=255
        }
        if(pointer<0&&nearest!=currentIndex)post{if(pointer<0)setCurrent(nearest)}
        // No unbounded canvas, bitmap, or page-object cache in a 1,000-page document.
        if(pages.size>7){pages.keys.toList().filter{it !in visible&&it!=contactPage?.id&&it!=pageIds.getOrNull(currentIndex)}.forEach{pages.remove(it);pictures.remove(it)}}
    }
    private fun recordLayers(page:NotePage):List<Layer>{
        val layers=mutableListOf<Layer>()
        var picture=Picture();var count=0
        var canvas=picture.beginRecording(ceil(page.width).toInt(),ceil(page.height).toInt())
        fun flush(){picture.endRecording();if(count>0)layers+=Layer.Commands(picture);count=0}
        page.objects.filter{!it.optBoolean("hidden")}.forEach{obj->
            if(obj.optString("type")=="image"){
                flush();layers+=Layer.Image(obj);picture=Picture();canvas=picture.beginRecording(ceil(page.width).toInt(),ceil(page.height).toInt())
            }else{renderer.draw(canvas,obj);count++}
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
        if(pointer>=0&&p!=null){
            if(held&&!erasing){live=null;points.clear();removeCallbacks(hold);beginErase(p)}
            else if(!held&&erasing&&tool!=Tool.ERASER){commitErase();beginInk(p)}
        }
        invalidate()
    }
    override fun onGenericMotionEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS||event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE){
            updateButtons(event)
            if(pointer>=0){val index=event.findPointerIndex(pointer);if(index>=0){val p=point(event,index);val erase=buttonEraser(event,index)||tool==Tool.ERASER
                if(erase&&!erasing){live=null;points.clear();removeCallbacks(hold);beginErase(p)}else if(!erase&&erasing){commitErase();beginInk(p)}}}
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
                val shouldErase=tool==Tool.ERASER||buttonEraser(event,pIndex)
                if(shouldErase&&!erasing){live=null;points.clear();removeCallbacks(hold);beginErase(p)}
                else if(!shouldErase&&erasing){commitErase();beginInk(p)}
                if(erasing)eraseAlong(p)
                else {
                    for(h in 0 until event.historySize)movePoint(point(event,pIndex,h))
                    movePoint(p)
                }
                invalidate();return true
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
                if(erasing)eraseAlong(point(event,pIndex)) else movePoint(point(event,pIndex))
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
            MotionEvent.ACTION_DOWN->{touchX=cx;touchY=cy;touchSpan=span;touchTravel=0f;touchStartTime=event.eventTime;maxTouches=1}
            MotionEvent.ACTION_POINTER_DOWN->{touchX=cx;touchY=cy;touchSpan=span;maxTouches=max(maxTouches,count)}
            MotionEvent.ACTION_MOVE->{val dx=cx-touchX;val dy=cy-touchY;touchTravel+=hypot(dx,dy)
                if(count>=2&&touchSpan>0)zoomAt(span/touchSpan,cx,cy)
                panX+=dx;panY+=dy;touchX=cx;touchY=cy;touchSpan=span;invalidate()}
            MotionEvent.ACTION_POINTER_UP->{touchSpan=0f;val remaining=(0 until count).filter{it!=event.actionIndex}
                if(remaining.isNotEmpty()){touchX=remaining.sumOf{event.getX(it).toDouble()}.toFloat()/remaining.size;touchY=remaining.sumOf{event.getY(it).toDouble()}.toFloat()/remaining.size}}
            MotionEvent.ACTION_UP->{
                if(touchTravel<18*resources.displayMetrics.density&&event.eventTime-touchStartTime<280){
                    if(maxTouches==2&&!readOnly)undo() else if(maxTouches>=3&&!readOnly)redo()
                    else pageIndexAt(event.x,event.y)?.let{i->setCurrent(i);val p=point(event,0);val obj=currentPage?.objects?.lastOrNull{InkGeometry.bounds(it).contains(p.x,p.y)}
                        if(obj?.optString("type")=="tape")toggleTape(obj) else if(obj!=null)onObjectTap(obj)}
                }
                if(!continuous&&abs(panY)>height*.65f&&zoom<=1.05f){val direction=if(panY<0)1 else -1;goTo(currentIndex+direction)}
                maxTouches=0
            }
        };return true
    }
    private fun beginInk(p:InkPoint){
        erasing=false;eraserPoint=null;points=mutableListOf(p);heldShape=false;contactTool=tool
        val page=contactPage?:return
        when(tool){
            Tool.PEN,Tool.HIGHLIGHTER,Tool.LASER->live=json("id" to uid("stroke"),"type" to "stroke","brush" to if(tool==Tool.HIGHLIGHTER)"highlighter" else brush,
                "color" to if(tool==Tool.HIGHLIGHTER&&inkColor=="#172033")"#ffe066" else inkColor,"width" to if(tool==Tool.HIGHLIGHTER)highlighterWidth else inkWidth,
                "opacity" to if(tool==Tool.HIGHLIGHTER).32f else 1f,"settings" to penSettings.copyJson(),"createdAt" to System.currentTimeMillis())
            Tool.SHAPE->live=json("id" to uid("shape"),"type" to "shape","shape" to shape,"color" to inkColor,"width" to inkWidth,"x1" to p.x,"y1" to p.y,"x2" to p.x,"y2" to p.y)
            Tool.TAPE->live=json("id" to uid("tape"),"type" to "tape","color" to "#4c91dd","x" to p.x,"y" to p.y,"w" to 1,"h" to 1)
            Tool.LASSO->{moveBefore=selected().filter{!it.optBoolean("locked")}.takeIf{list->list.any{InkGeometry.bounds(it).expanded(10/scale).contains(p.x,p.y)}}?.map{it.copyJson()}?:emptyList()
                moveStart=if(moveBefore.isNotEmpty())p else null
                if(moveBefore.isEmpty()){selection.clear();onSelection(emptyList())}}
            Tool.TEXT,Tool.STICKY,Tool.IMAGE->{onInsert(tool,p.x,p.y);pointer=-1;contactPage=null;points.clear()}
            else->Unit
        }
    }
    private fun movePoint(p:InkPoint){
        val page=contactPage?:return
        if(heldShape)return
        when(contactTool){
            Tool.PEN,Tool.HIGHLIGHTER,Tool.LASER->{
                if(points.isEmpty()||InkGeometry.distance(points.last(),p)*scale>.25f){points+=p
                    removeCallbacks(hold);if(drawHold&&contactTool==Tool.PEN&&!ruler)postDelayed(hold,650)}
            }
            Tool.SHAPE->{live?.put("x2",p.x)?.put("y2",p.y)}
            Tool.TAPE->{val a=points.first();live?.put("x",min(a.x,p.x))?.put("y",min(a.y,p.y))?.put("w",abs(a.x-p.x))?.put("h",abs(a.y-p.y))}
            Tool.LASSO->{val start=moveStart
                if(start!=null){val transformed=moveBefore.map{InkGeometry.transform(it,p.x-start.x,p.y-start.y)}
                    ObjectChange(moveBefore,transformed,afterPositions=positions(page)).apply(page);pictures.remove(page.id)
                }else if(points.isEmpty()||InkGeometry.distance(points.last(),p)*scale>2)points+=p}
            else->Unit
        }
    }
    private fun convertHeldShape(){
        if(pointer<0||erasing||contactTool!=Tool.PEN||points.size<5)return
        if(scribbleErase&&InkGeometry.deliberateScratch(points,scale/resources.displayMetrics.density))return
        val recognized=InkGeometry.recognizeShape(points)?:return
        val a=points.first();val b=points.last()
        val x1=if(recognized=="line")a.x else points.minOf{it.x};val y1=if(recognized=="line")a.y else points.minOf{it.y}
        val x2=if(recognized=="line")b.x else points.maxOf{it.x};val y2=if(recognized=="line")b.y else points.maxOf{it.y}
        live=json("id" to uid("shape"),"type" to "shape","shape" to recognized,"color" to inkColor,"width" to inkWidth,"x1" to x1,"y1" to y1,"x2" to x2,"y2" to y2)
        heldShape=true;invalidate()
    }
    private fun beginErase(p:InkPoint){
        erasing=true;contactTool=Tool.ERASER;eraseBefore.clear();eraseAfter.clear();erasePositions.clear();contactPage?.let{erasePositions.putAll(positions(it))};eraserPoint=null;eraseAlong(p)
    }
    private fun eraseAlong(p:InkPoint){
        val old=eraserPoint;val steps=if(old==null)1 else ceil(InkGeometry.distance(old,p)/(eraserRadius/scale*.4f)).toInt().coerceIn(1,200)
        for(step in 1..steps){val t=step.toFloat()/steps;eraseAt(if(old==null)p else p.copy(x=old.x+(p.x-old.x)*t,y=old.y+(p.y-old.y)*t))}
        eraserPoint=p
    }
    private fun eraseAt(p:InkPoint){
        val page=contactPage?:return;val radius=eraserRadius/scale
        val hit=page.objects.filter{if(wholeEraser&&tool==Tool.ERASER)!it.optBoolean("locked")&&!it.optBoolean("hidden") else it.optString("type")=="stroke"&&InkGeometry.hit(it,p,radius)}
        if(hit.isEmpty())return
        for(obj in hit){val id=obj.getString("id");val pos=page.objects.indexOf(obj)
            if(id !in eraseAfter&&id !in eraseBefore){eraseBefore[id]=obj.copyJson();if(id !in erasePositions)erasePositions[id]=pos}
            eraseAfter.remove(id)
            val fragments=if(preciseEraser)InkGeometry.eraseParts(obj,p,radius)else emptyList()
            page.objects.removeAt(pos);page.objects.addAll(pos,fragments)
            fragments.forEach{eraseAfter[it.getString("id")]=it}
        }
        pictures.remove(page.id)
    }
    private fun commitErase(){
        val page=contactPage?:return
        if(eraseBefore.isNotEmpty()){
            val change=ObjectChange(eraseBefore.values.toList(),eraseAfter.values.map{it.copyJson()},erasePositions.toMap(),positions(page))
            record(page,change,false)
        }
        eraseBefore.clear();eraseAfter.clear();erasePositions.clear();erasing=false;eraserPoint=null
    }
    fun finishContact(){
        removeCallbacks(hold);val page=contactPage
        if(page!=null){
            if(erasing)commitErase()
            else if(contactTool==Tool.LASSO){
                if(moveBefore.isNotEmpty()){
                    val changed=page.objects.filter{obj->moveBefore.any{it.optString("id")==obj.optString("id")}}.map{it.copyJson()}
                    if(changed.toString()!=moveBefore.toString())record(page,ObjectChange(moveBefore,changed,positions(page),positions(page)),false)
                }else if(points.size>2){
                    page.objects.filter{obj->val b=InkGeometry.bounds(obj)
                        if(obj.optString("type")=="stroke")InkGeometry.points(obj).any{InkGeometry.inside(it,points)}
                        else InkGeometry.inside(InkPoint((b.left+b.right)/2,(b.top+b.bottom)/2),points)}.forEach{selection+=it.getString("id")}
                }
                onSelection(selected())
            }else if(contactTool!=Tool.LASER){live?.let{obj->
                if(obj.optString("type")=="stroke")obj.put("points",JSONArray(points.map{it.json()}))
                val scratch=if(scribbleErase&&contactTool==Tool.PEN&&!heldShape)
                    InkGeometry.scratchTargets(page.objects,points,scale/resources.displayMetrics.density)else emptyList()
                if(scratch.isNotEmpty()){
                    record(page,ObjectChange(scratch.map{it.copyJson()},emptyList(),positions(page)))
                    onStatus("${scratch.size}개 항목을 지웠습니다.")
                }
                else record(page,ObjectChange(emptyList(),listOf(obj.copyJson())))
            }}
        }
        pointer=-1;contactPage=null;live=null;points.clear();moveBefore=emptyList();moveStart=null;contactTool=null;heldShape=false;eraserPoint=null;invalidate()
    }
    fun cancelContact(){
        removeCallbacks(hold);val page=contactPage
        if(page!=null){
            if(erasing)ObjectChange(eraseAfter.values.toList(),eraseBefore.values.toList(),afterPositions=erasePositions).apply(page)
            if(moveBefore.isNotEmpty())ObjectChange(moveBefore,moveBefore,afterPositions=positions(page)).apply(page)
            pictures.remove(page.id)
        }
        eraseBefore.clear();eraseAfter.clear();erasePositions.clear();erasing=false;pointer=-1;contactPage=null;points.clear();live=null;moveBefore=emptyList();moveStart=null;contactTool=null;heldShape=false;eraserPoint=null;invalidate()
    }
    private fun positions(page:NotePage)=page.objects.mapIndexed{i,obj->obj.getString("id") to i}.toMap()
    private fun record(page:NotePage,change:ObjectChange,apply:Boolean=true){
        if(apply)change.apply(page)
        histories.getOrPut(page.id){EditHistory(8*1024*1024)}.push(change)
        if(histories.size>4)histories.keys.firstOrNull{it!=page.id}?.let{histories.remove(it)}
        pictures.remove(page.id);onEdit(page.id,change,positions(page));invalidate()
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
    fun undo(){if(readOnly)return;val page=currentPage?:return;val change=histories[page.id]?.undo()?:return;change.apply(page);pictures.remove(page.id);onEdit(page.id,change,positions(page));selection.clear();onSelection(emptyList());invalidate()}
    fun redo(){if(readOnly)return;val page=currentPage?:return;val change=histories[page.id]?.redo()?:return;change.apply(page);pictures.remove(page.id);onEdit(page.id,change,positions(page));selection.clear();onSelection(emptyList());invalidate()}
    fun close(){cancelContact();background.close()}
    override fun performClick():Boolean {super.performClick();return true}
}
