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
        set(value){cancelContact();pendingTextTap=null;field=value;selection.clear();invalidate();onSelection(emptyList());onToolChanged()}
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
    var beforePageRead:((()->Unit)->Unit)={it()}
    var onContactChanged:(Boolean)->Unit={}
    val hasActiveContact get()=pointer>=0||contactPage!=null||contactState==ContactState.PANNING||contactState==ContactState.PINCHING
    var onInsert:(Tool,Float,Float)->Unit={_,_,_->}
    var onTextTap:(Float,Float,Boolean)->Unit={x,y,_->onInsert(Tool.TEXT,x,y)}
    var onBeforePageChange:()->Unit={}
    private data class TextTap(val pageId:String,val x:Float,val y:Float,val fullPage:Boolean)
    private var pendingTextTap:TextTap?=null
    private var textTapGesture=false
    private var hiddenTextObjectId:String?=null
    var onSelection:(List<JSONObject>)->Unit={}
    var onObjectTap:(JSONObject)->Unit={}
    var onStatus:(String)->Unit={}
    var onAppendPage:()->Unit={}
    private val pages=LinkedHashMap<String,NotePage>()
    private val pending=HashSet<String>()
    private val pendingSelected=HashSet<String>()
    private var loadGeneration=0
    private interface CommandDisplay {
        fun prepare()
        fun draw(canvas:Canvas)
    }
    @androidx.annotation.RequiresApi(29)
    private class HardwareDisplay(private val width:Int,private val height:Int,
                                  private val children:List<CommandDisplay>,
                                  private val record:(Canvas)->Unit):CommandDisplay {
        private val node=RenderNode("ink-retained-scene").apply {
            setPosition(0,0,this@HardwareDisplay.width,this@HardwareDisplay.height)
            setClipToBounds(false);setUseCompositingLayer(false,null)
        }
        init { recordCommands() }
        private fun recordCommands(){
            val canvas=node.beginRecording(width,height)
            try{record(canvas)}finally{node.endRecording()}
        }
        override fun prepare(){
            // HWUI can reclaim a list while the idle bitmap owns presentation. The node
            // object still exists; restore its commands before returning to zoom/erase.
            if(!node.hasDisplayList())recordCommands()
            else children.forEach{it.prepare()}
        }
        override fun draw(canvas:Canvas){
            prepare()
            canvas.drawRenderNode(node)
        }
    }
    @androidx.annotation.RequiresApi(29)
    private fun recordHardwareDisplay(width:Int,height:Int,children:List<CommandDisplay> = emptyList(),
                                      draw:(Canvas)->Unit):CommandDisplay =
        HardwareDisplay(width,height,children,draw)
    private data class Command(val objectData:JSONObject,val picture:Picture?,val bounds:InkBounds?,
                               val display:CommandDisplay?)
    private sealed class Layer {
        abstract val moving:Boolean
        data class Commands(val commands:List<Command>,val bounds:InkBounds?,override val moving:Boolean=false):Layer()
        data class Image(val objectData:JSONObject,val bounds:InkBounds,override val moving:Boolean=false):Layer()
    }
    /** Order changes splice only affected entries; unchanged Pictures never need a page-wide reconciliation. */
    private inner class LayerCache(source:List<JSONObject>,previous:LayerCache?=null) {
        inner class Entry(val obj:JSONObject,var layer:Layer?=null,var cost:Long=0) {
            var before:Entry?=null;var after:Entry?=null
            var appendOnly=false
        }
        val entries=HashMap<String,Entry>()
        private var first:Entry?=null;private var last:Entry?=null
        val pending=LinkedHashSet<Entry>()
        var dirty=false
        val invalid=HashSet<String>()
        var bytes=0L;private set
        init { for(obj in source){
            val old=previous?.entries?.get(obj.optString("id"))?.takeIf{it.obj===obj&&previous?.invalid?.contains(obj.optString("id"))!=true}
            insertBefore(null,obj,old?.layer,old?.cost?:0)
        }}
        private fun insertBefore(next:Entry?,obj:JSONObject,layer:Layer?=null,cost:Long=0):Entry {
            val entry=Entry(obj,layer,cost);val prior=next?.before?:if(next==null)last else null
            entry.before=prior;entry.after=next
            if(prior==null)first=entry else prior.after=entry
            if(next==null)last=entry else next.before=entry
            entries[obj.getString("id")]=entry;bytes+=cost
            if(layer==null)pending.add(entry)
            return entry
        }
        fun append(obj:JSONObject){insertBefore(null,obj).appendOnly=true}
        fun replace(id:String,objects:List<JSONObject>):Boolean {
            val entry=entries.remove(id)?:return false;val next=entry.after
            if(entry.before==null)first=next else entry.before?.after=next
            if(next==null)last=entry.before else next.before=entry.before
            bytes-=entry.cost;pending.remove(entry)
            for(obj in objects)insertBefore(next,obj)
            return true
        }
        fun tracks(obj:JSONObject)=entries[obj.optString("id")]?.obj===obj
        fun reconcile(page:NotePage) {
            for(entry in pending){
                if(entry.obj.optString("type")=="image"&&!entry.obj.optBoolean("hidden")){
                    val bounds=InkGeometry.bounds(entry.obj);entry.layer=Layer.Image(entry.obj,bounds)
                    invalidateSceneBounds(page,bounds);continue
                }
                val samples=entry.obj.optJSONArray("points")?.length()?:0
                val cost=512L+samples*(if(entry.obj.optString("brush")=="pencil")768L else 256L)
                // Bound retained command complexity, not ink: an unrecorded object draws through the same renderer.
                val retained=pictures.values.sumOf{it.bytes}
                val picture=if(retained+cost<=48L*1024*1024)Picture() else null
                val bounds=if(picture!=null){
                    val canvas=picture.beginRecording(ceil(page.width).toInt(),ceil(page.height).toInt())
                    val measured=renderer.drawMeasured(canvas,entry.obj,scale.toDouble())
                    picture.endRecording();recordedCommandObjects++;entry.cost=cost;bytes+=cost;measured
                }else null
                val display=if(picture!=null&&Build.VERSION.SDK_INT>=29)
                    recordHardwareDisplay(ceil(page.width).toInt(),ceil(page.height).toInt()){it.drawPicture(picture)}else null
                entry.layer=Layer.Commands(listOf(Command(entry.obj,picture,bounds,display)),bounds)
                if(!entry.appendOnly||picture==null||bounds==null||!appendScenePicture(page,picture,bounds))
                    invalidateSceneBounds(page,layerBounds(entry.layer))
            }
            pending.clear()
        }
        val layers:Iterable<Layer> get()=Iterable { object:Iterator<Layer>{
            private var next=first
            override fun hasNext()=next!=null
            override fun next():Layer {val entry=next?:throw NoSuchElementException();next=entry.after;return checkNotNull(entry.layer)}
        }}
    }
    private val pictures=HashMap<String,LayerCache>()
    private val sceneTiles=InkSceneCache()
    // Receipt diagnostics only, not timings or per-sample telemetry.
    internal var recordedCommandObjects=0L;private set
    internal var spatialIndexBuilds=0;private set
    internal var replayedCommandObjects=0L;private set
    internal val rasterCacheHits get()=sceneTiles.cacheHits
    internal val rasterColdScenes get()=sceneTiles.coldScenes
    internal val rasterizedTiles get()=sceneTiles.rasterizedTiles
    internal val loadedPageCount get()=pages.size
    private var searchPageId:String?=null
    private var searchBounds:List<InkBounds> = emptyList()
    private val spatialIndexes=HashMap<String,PageSpatialIndex>()
    private val histories=HashMap<String,EditHistory>()
    private val revisionClock=HashMap<String,Long>()
    private val captureSessionId=java.util.UUID.randomUUID().toString()
    val canUndo get()=currentPage?.id?.let{histories[it]?.canUndo}?:false
    val canRedo get()=currentPage?.id?.let{histories[it]?.canRedo}?:false
    private var pageIds:List<String> = emptyList()
    private val pageIndices=HashMap<String,Int>()
    private var metadata:List<JSONObject> = emptyList()
    private var tops=FloatArray(0)
    private var bottoms=FloatArray(0)
    private var pagePositionsOrdered=true
    var currentIndex=0;private set
    val currentPage get()=pageIds.getOrNull(currentIndex)?.let{pages[it]}
    private val background=BackgroundLoader(context,repository,{sceneChanged()},{onStatus(it)})
    private val renderer=NoteRenderer()
    private var frontBuffer:InkFrontSurface?=null
    private var sceneDirty=true
    private var commitFrontPending=false
    private var frontContact=false
    private var frontStroke:JSONObject?=null
    private var hardwareFallback=true
    private var hardwareScene:CommandDisplay?=null
    private var hardwareSceneGeneration=-1L
    private var scheduledRasterGeneration=-1L
    private var hiddenFrontGeneration=-1L
    private val canonicalRasterizer:InkSceneRasterizer=InkSceneRasterizer{generation,prepared->
        if(generation==canonicalGeneration()&&prepared.width==width&&prepared.height==height&&
            prepared.x==originX&&prepared.y==originY&&prepared.scale==scale&&isAttachedToWindow){
            sceneTiles.install(prepared);hardwareFallback=false;sceneDirty=true;invalidate()
        }else prepared.discard()
    }
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
    private val pointPublication=InkPointPublication()
    private var contactTool:Tool?=null
    private var live:JSONObject?=null
    private var erasing=false
    private val stylusButtons=StylusButtonState()
    private var keyEraser=false
    private var eraserPoint:InkPoint?=null
    private val eraseBefore=linkedMapOf<String,JSONObject>()
    private val erasePositions=linkedMapOf<String,Int>()
    private val contactOrderChanges=ArrayList<Pair<Int,Int>>()
    private val contactAdded=linkedSetOf<String>()
    private var moveBefore=emptyList<JSONObject>()
    private var moveStart:InkPoint?=null
    private var moveIds=emptySet<String>()
    private var moveLayers:List<Layer>?=null
    private var moveDx=0f;private var moveDy=0f
    private var touchX=0f;private var touchY=0f;private var touchSpan=0f;private var touchTravel=0f
    private var touchStartTime=0L;private var maxTouches=0
    private var heldShape=false
    private val hold=Runnable{convertHeldShape()}

    init{isFocusable=true;isFocusableInTouchMode=true;contentDescription="필기 페이지"}
    internal fun attachFrontBuffer(host:InkFrontSurface){frontBuffer=host;sceneDirty=true;invalidate()}
    internal fun frontBufferRecreated(){sceneChanged()}
    internal fun frontBufferPresented(presented:Boolean){
        if(presented&&hasActiveContact&&!frontContact){frontBuffer?.hideForLegacy();return}
        invalidate()
    }
    private fun canonicalGeneration():Long=canonicalRasterizer.generation
    private fun markHardwareDirty(){
        hardwareFallback=true;hardwareScene=null;hardwareSceneGeneration=-1
        canonicalRasterizer.advance()
    }
    private fun sceneChanged(clearTiles:Boolean=true){
        if(clearTiles){sceneTiles.clear();hardwareFallback=true}
        hardwareScene=null;hardwareSceneGeneration=-1;canonicalRasterizer.advance()
        sceneDirty=true;if(!frontContact)frontBuffer?.hideForLegacy();invalidate()
    }
    private fun recordScene():Picture?{
        if(width<=0||height<=0)return null
        return Picture().apply{val c=beginRecording(width,height);drawDocument(c,DrawMode.SCENE);endRecording()}
    }
    private fun publishScene(commitFront:Boolean=false){
        val picture=recordScene()?:return
        frontBuffer?.renderScene(picture,width,height,commitFront)
        sceneDirty=false;commitFrontPending=false
    }
    /** Main-owned loader access is captured into immutable commands before worker rasterization. */
    private fun requestCanonicalRaster(){
        // Current hardware ink is already visible. A contact's next sample would obsolete this
        // full-scene capture/raster, so derive the canonical bitmap only after the contact ends.
        if(hasActiveContact)return
        val generation=canonicalGeneration()
        if(scheduledRasterGeneration==generation||width<=0||height<=0)return
        scheduledRasterGeneration=generation
        background.beginFrame(retainPrevious=sceneTiles.hasContent)
        val picture=Picture()
        try{
            val canvas=picture.beginRecording(width,height)
            drawDocument(canvas,DrawMode.SCENE,rasterizing=true)
            picture.endRecording()
        }finally{background.endFrame()}
        canonicalRasterizer.request(generation,picture,width,height,originX,originY,scale)
    }
    /** Shared by attached View presentation and the independent hardware pixel oracle. */
    @androidx.annotation.RequiresApi(29)
    internal fun drawRetainedScene(canvas:Canvas){
        check(Build.VERSION.SDK_INT>=29&&canvas.isHardwareAccelerated)
        val visible=visiblePageGeometry()
        maintainVisiblePages(visible)
        val generation=canonicalGeneration()
        var display=hardwareScene
        if(display==null||hardwareSceneGeneration!=generation){
            val children=ArrayList<CommandDisplay>()
            display=recordHardwareDisplay(width,height,children){
                children.clear()
                background.beginFrame(retainPrevious=sceneTiles.hasContent)
                try{drawDocument(it,DrawMode.SCENE,rasterizing=true,hardwareCommands=true,retainedDisplays=children,viewport=visible)}
                finally{background.endFrame()}
            }
            hardwareScene=display;hardwareSceneGeneration=generation
        }
        checkNotNull(display).draw(canvas)
    }
    fun open(ids:List<String>,metas:List<JSONObject>,index:Int=0){
        pendingTextTap=null
        cancelContact();repository.cancelPageRequests(this);loadGeneration++;commitFrontPending=false
        histories.values.forEach{it.clear()}
        clearSearchHighlights();pageIds=ids;metadata=metas;pages.clear();pictures.clear();spatialIndexes.clear();histories.clear();revisionClock.clear();pending.clear();pendingSelected.clear();selection.clear()
        currentIndex=index.coerceIn(0,(ids.size-1).coerceAtLeast(0));layoutPages();zoom=1f;panX=0f;panY=-(tops.getOrNull(currentIndex)?:0f)*scale
        requestPage(currentIndex);sceneChanged()
    }
    fun updatePages(ids:List<String>,metas:List<JSONObject>,index:Int){
        onBeforePageChange();pendingTextTap=null
        cancelContact();clearSearchHighlights();pageIds=ids;metadata=metas;pages.keys.retainAll(ids.toSet());pictures.clear();spatialIndexes.keys.retainAll(ids.toSet());layoutPages();goTo(index)
    }
    fun refreshPageList(ids:List<String>,metas:List<JSONObject>){
        val selected=pageIds.getOrNull(currentIndex)
        val oldTop=pageTop(currentIndex)
        pageIds=ids;metadata=metas;pages.keys.retainAll(ids.toSet());spatialIndexes.keys.retainAll(ids.toSet());layoutPages()
        pictures.keys.retainAll(ids.toSet())
        if(searchPageId !in ids)clearSearchHighlights()
        currentIndex=selected?.let{ids.indexOf(it)}?.takeIf{it>=0}?:currentIndex.coerceIn(0,(ids.size-1).coerceAtLeast(0))
        panY+=(oldTop-pageTop(currentIndex))*scale
        requestPage(currentIndex);sceneChanged()
    }
    fun replacePage(page:NotePage){if(contactPage?.id==page.id)cancelContact();clearSearchHighlights(page.id)
        pages[page.id]=indexedPage(page);pictures.remove(page.id);spatialIndexes.remove(page.id);sceneChanged()}
    private fun indexedPage(page:NotePage)=page.prepareForEditing()
    private fun indexFor(page:NotePage)=spatialIndexes.getOrPut(page.id){spatialIndexBuilds++;PageSpatialIndex().apply{rebuild(page.objects)}}
    private fun layoutPages(){
        var y=0f;tops=FloatArray(pageIds.size);bottoms=FloatArray(pageIds.size);pagePositionsOrdered=true
        pageIndices.clear()
        for(i in pageIds.indices){
            pageIndices[pageIds[i]]=i
            val pageHeight=metadata.getOrNull(i)?.f("height",1414f)?:1414f
            tops[i]=y;bottoms[i]=y+pageHeight
            pagePositionsOrdered=pagePositionsOrdered&&pageHeight.isFinite()&&pageHeight>0&&y.isFinite()&&bottoms[i].isFinite()&&bottoms[i]+28f>y
            y=bottoms[i]+28f
        }
    }
    private fun visiblePageIndices():IntRange {
        if(!continuous)return currentIndex..currentIndex
        return pageViewportRange(tops,bottoms,(-500f-originY)/scale,(height+500f-originY)/scale,pagePositionsOrdered)
    }
    private data class VisiblePage(val index:Int,val top:Float,val height:Float)
    /** A document replay shares geometry across loading, cached-scene replay and its overlay. */
    private fun visiblePageGeometry():List<VisiblePage> {
        if(pageIds.isEmpty())return emptyList()
        val visible=ArrayList<VisiblePage>()
        for(index in visiblePageIndices()){
            val top=originY+pageTop(index)*scale
            val pageHeight=metadata.getOrNull(index)?.f("height",1414f)?:1414f
            if(top>height+500||top+pageHeight*scale< -500)continue
            visible.add(VisiblePage(index,top,pageHeight))
        }
        return visible
    }
    fun goTo(index:Int,center:Boolean=false){
        if(pageIds.isEmpty())return
        cancelContact();setCurrent(index.coerceIn(0,pageIds.lastIndex));panY=-pageTop(currentIndex)*scale;panX=0f
        if(center){
            val meta=metadata.getOrNull(currentIndex)
            panX=(1000f-(meta?.f("width",1000f)?:1000f))*scale/2f
            panY+=(height-(meta?.f("height",1414f)?:1414f)*scale)/2f-margin
        }
        retainPageRequests(setOf(pageIds[currentIndex]));requestPage(currentIndex);sceneChanged()
    }
    /** Search bounds stay in page coordinates and are never baked into document/front-buffer commands. */
    fun showSearchHighlights(pageId:String,bounds:List<InkBounds>){
        cancelContact()
        val index=pageIds.indexOf(pageId)
        if(index<0){clearSearchHighlights();return}
        searchPageId=pageId
        searchBounds=bounds.filter{it.left.isFinite()&&it.top.isFinite()&&it.right.isFinite()&&it.bottom.isFinite()&&it.width>=0f&&it.height>=0f}
        setCurrent(index);requestPage(index)
        searchBounds.firstOrNull()?.let{b->
            val first=screenPoint(b.left,b.top,index);val last=screenPoint(b.right,b.bottom,index)
            val inset=min(32f*resources.displayMetrics.density,min(width,height)/4f)
            fun shift(start:Float,end:Float,size:Float):Float=when{
                end-start>size-2*inset->size/2-(start+end)/2
                start<inset->inset-start
                end>size-inset->size-inset-end
                else->0f
            }
            panX+=shift(first.x,last.x,width.toFloat());panY+=shift(first.y,last.y,height.toFloat())
        }
        sceneChanged()
    }
    fun clearSearchHighlights(){searchPageId=null;searchBounds=emptyList();invalidate()}
    private fun clearSearchHighlights(pageId:String){if(searchPageId==pageId)clearSearchHighlights()}
    fun changePageMode(value:Boolean){continuous=value;goTo(currentIndex)}
    fun resetZoom(){cancelContact();zoom=1f;panX=0f;panY=-pageTop(currentIndex)*scale;pictures.clear();sceneChanged();onViewportChanged()}
    fun zoomBy(multiplier:Float){cancelContact();zoomAt(multiplier,width/2f,height/2f);sceneChanged()}
    fun zoomPercent()=(zoom*100).roundToInt()
    private fun zoomAt(factor:Float,x:Float,y:Float){val old=scale;val px=(x-originX)/old;val py=(y-originY)/old
        zoom=(zoom*factor).coerceIn(.08f,8f);panX=x-px*scale-(width-1000f*scale)/2;panY=y-py*scale-margin
        if((old>2.6f)!=(scale>2.6f))pictures.clear()
        onViewportChanged()}
    private fun pageTop(index:Int)=if(continuous)tops.getOrElse(index){0f} else 0f
    private fun setCurrent(index:Int){if(index!=currentIndex){onBeforePageChange();pendingTextTap=null;currentIndex=index;selection.clear();onSelection(emptyList());onPageChanged(index)}}
    private fun requestPage(index:Int){
        val id=pageIds.getOrNull(index)?:return
        if(pages.containsKey(id))return
        val selected=index==currentIndex
        if(!pending.add(id)&&(!selected||id in pendingSelected))return
        if(selected)pendingSelected.add(id)
        val generation=loadGeneration
        beforePageRead {
            if(generation!=loadGeneration||id !in pending)return@beforePageRead
            var preparedIndex:PageSpatialIndex?=null
            var preparedPage:NotePage?=null
            repository.requestPage(this,id,selected,prepare={page->
                preparedPage=indexedPage(page)
                preparedIndex=PageSpatialIndex().apply{rebuild(page.objects)}
            }){result->
                if(generation!=loadGeneration)return@requestPage
                pending.remove(id);pendingSelected.remove(id)
                if(pageIndices.containsKey(id)&&result!=null&&!pages.containsKey(id)){
                    pages[id]=preparedPage?:indexedPage(result)
                    preparedIndex?.let{spatialIndexes[id]=it;spatialIndexBuilds++}
                    // Multiple adjacent arrivals share the next frame; don't cold-clear an unchanged viewport.
                    invalidateSceneBounds(pages.getValue(id),InkBounds(0f,0f,result.width,result.height))
                    sceneChanged(clearTiles=false)
                    pendingTextTap?.takeIf{it.pageId==id&&pageIds.getOrNull(currentIndex)==id&&tool==Tool.TEXT&&!readOnly}?.let{
                        pendingTextTap=null;performTextTap(it)
                    }
                }
            }
        }
    }
    private fun retainPageRequests(keep:Set<String>){
        repository.cancelPageRequests(this,keep)
        pending.retainAll(keep);pendingSelected.retainAll(keep)
    }
    private fun pageIndexAt(x:Float,y:Float):Int? {
        val docX=(x-originX)/scale;val docY=(y-originY)/scale
        if(docX<0||docX>1000)return null
        val range=visiblePageIndices()
        return range.firstOrNull{docY>=pageTop(it)&&docY<=pageTop(it)+(metadata.getOrNull(it)?.f("height",1414f)?:1414f)}
    }
    fun screenPoint(x:Float,y:Float,index:Int=currentIndex)=PointF(originX+x*scale,originY+(pageTop(index)+y)*scale)
    fun pagePoint(x:Float,y:Float)=PointF((x-originX)/scale,(y-originY)/scale-pageTop(currentIndex))
    fun hideEditingText(id:String?){hiddenTextObjectId=id;sceneChanged()}
    fun ensureTextVisible(obj:JSONObject,topInset:Float){
        val p=screenPoint(obj.f("x"),obj.f("y"));val end=p.y+obj.f("h")*scale
        val bottom=height-24*resources.displayMetrics.density
        val dy=if(p.y<topInset)topInset-p.y else if(end>bottom)max(topInset-p.y,bottom-end) else 0f
        if(dy!=0f){panY+=dy;sceneChanged();onViewportChanged()}
    }
    fun ensureTextCursorVisible(x:Float,y:Float,topInset:Float){
        val point=screenPoint(x,y);val bottom=height-24*resources.displayMetrics.density
        val dy=when{point.y<topInset->topInset-point.y;point.y>bottom->bottom-point.y;else->0f}
        if(dy!=0f){panY+=dy;sceneChanged();onViewportChanged()}
    }
    private fun performTextTap(tap:TextTap){
        val page=currentPage?:return
        if(page.id!=tap.pageId||readOnly)return
        val existing=page.objects.lastOrNull{it.optString("type")=="text"&&!it.optBoolean("hidden")&&
            InkGeometry.hit(it,InkPoint(tap.x,tap.y),0f)}
        if(existing!=null&&!tap.fullPage)onObjectTap(existing)else onTextTap(tap.x,tap.y,tap.fullPage)
    }
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
        if(Build.VERSION.SDK_INT>=29&&canvas.isHardwareAccelerated&&hardwareFallback&&InkSceneCache.canRasterize(width,height)){
            if(frontContact){host?.cancelFront();frontContact=false}
            val generation=canonicalGeneration()
            if(hiddenFrontGeneration!=generation){host?.hideForLegacy();hiddenFrontGeneration=generation}
            drawRetainedScene(canvas)
            drawDocument(canvas,DrawMode.OVERLAY)
            requestCanonicalRaster()
            return
        }
        if(host!=null&&sceneDirty&&!hasActiveContact)publishScene(commitFrontPending)
        // A ready Surface is permission to start low-latency ink, not permission to
        // remove the View's committed page. Keep the complete scene in this frame
        // through zoom, erasing, tool changes and asynchronous Surface transactions.
        // Only an admitted front-buffer contact needs the Surface to own the page.
        drawDocument(canvas,if(frontContact&&host?.presented==true)DrawMode.OVERLAY else DrawMode.LEGACY)
    }
    private fun drawDocument(canvas:Canvas,mode:DrawMode,rasterizing:Boolean=false,damage:List<Rect>?=null,
                             hardwareCommands:Boolean=false,retainedDisplays:MutableList<CommandDisplay>?=null,
                             viewport:List<VisiblePage>?=null){
        val visible=viewport?:visiblePageGeometry()
        val scene=mode!=DrawMode.OVERLAY
        if(scene&&!rasterizing&&pageIds.isNotEmpty())maintainVisiblePages(visible)
        if(scene&&!rasterizing&&moveLayers==null&&pageIds.isNotEmpty()){
            // Reconcile changed recordings before choosing dirty tiles; both old and new extents
            // matter when a stroke is replaced, split, deleted or restored by undo/cancel.
            for(page in visible)pages[pageIds[page.index]]?.let{layersFor(it)}
            if(sceneTiles.prepare(width,height,originX,originY,scale)){
                background.beginFrame(retainPrevious=sceneTiles.hasContent)
                sceneTiles.draw(canvas){target,dirty->drawDocument(target,DrawMode.SCENE,rasterizing=true,damage=dirty,viewport=visible)}
                background.endFrame()
                if(mode!=DrawMode.SCENE)drawDocument(canvas,DrawMode.OVERLAY,viewport=visible)
                return
            }
        }
        val overlay=mode!=DrawMode.SCENE
        if(scene)canvas.drawColor(0xfff7f7f8.toInt())
        if(scene&&!rasterizing)background.beginFrame()
        if(pageIds.isEmpty()){if(scene&&!rasterizing)background.endFrame();return}
        val screenClip=canvas.clipBounds
        for(geometry in visible){
            val i=geometry.index;val top=geometry.top;val pageHeight=geometry.height
            val id=pageIds[i]
            canvas.save();canvas.translate(originX,top);canvas.scale(scale,scale)
            if(scene){paint.color=Color.WHITE;paint.style=Paint.Style.FILL;canvas.drawRect(0f,0f,1000f,pageHeight,paint)}
            val page=pages[id]
            if(page!=null){
                canvas.save();canvas.clipRect(0f,0f,page.width,page.height)
                if(scene){
                    renderer.template(canvas,page)
                    val visibleRect=InkBounds((screenClip.left-originX)/scale,(screenClip.top-top)/scale,
                        (screenClip.right-originX)/scale,(screenClip.bottom-top)/scale)
                    fun visible(bounds:InkBounds?,moving:Boolean):Boolean {
                        if(bounds==null)return true
                        val dx=if(moving)moveDx else 0f;val dy=if(moving)moveDy else 0f
                        if(!bounds.expanded(2/scale).overlaps(InkBounds(visibleRect.left-dx,visibleRect.top-dy,visibleRect.right-dx,visibleRect.bottom-dy)))return false
                        if(damage==null)return true
                        val left=originX+(bounds.left+dx)*scale-2;val right=originX+(bounds.right+dx)*scale+2
                        val first=top+(bounds.top+dy)*scale-2;val last=top+(bounds.bottom+dy)*scale+2
                        return damage.any{left<=it.right&&right>=it.left&&first<=it.bottom&&last>=it.top}
                    }
                    // Keep background sampling and Canvas clipping identical to the original viewport.
                    background.draw(canvas,page,RectF(-originX/scale,-top/scale,(width-originX)/scale,(height-top)/scale),scale)
                    val layers=moveLayers?.takeIf{contactPage?.id==id}?:layersFor(page)
                    for(layer in layers){
                        if(layer.moving){canvas.save();canvas.translate(moveDx,moveDy)}
                        when(layer){
                            is Layer.Commands->{
                                if(visible(layer.bounds,layer.moving))for(command in layer.commands){
                                    if(command.objectData.optString("id")==hiddenTextObjectId)continue
                                    if(visible(command.bounds,layer.moving)){
                                        val picture=command.picture
                                        val display=command.display
                                        if(canvas.isHardwareAccelerated&&(hardwareCommands||!rasterizing)&&display!=null){
                                            retainedDisplays?.add(display);display.draw(canvas)
                                        }
                                        else if(picture!=null)canvas.drawPicture(picture)else renderer.draw(canvas,command.objectData,scale.toDouble())
                                        replayedCommandObjects++
                                    }
                                }
                            }
                            is Layer.Image->{val obj=layer.objectData;val b=layer.bounds
                                if(visible(b,layer.moving))background.image(obj.optString("src"),(max(b.width,b.height)*scale).roundToInt())?.let{bitmap->
                                    renderer.drawImage(canvas,obj,bitmap)
                                }}
                        }
                        if(layer.moving)canvas.restore()
                    }
                }
                if(overlay&&contactPage?.id==id){
                    live?.takeUnless{mode==DrawMode.OVERLAY&&frontContact}?.let{if(it.optString("type")=="stroke")renderer.stroke(canvas,it,pointPublication.snapshot(),scale.toDouble())
                        else renderer.draw(canvas,it,scale.toDouble())}
                    if(contactTool==Tool.LASSO&&moveBefore.isEmpty()&&points.isNotEmpty()){
                        paint.color=0xff1976d2.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=2/scale;paint.pathEffect=DashPathEffect(floatArrayOf(7/scale,5/scale),0f)
                        val path=Path();points.forEachIndexed{index,p->if(index==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)};canvas.drawPath(path,paint);paint.pathEffect=null;paint.style=Paint.Style.FILL
                    }
                }
                if(overlay&&i==currentIndex){
                    paint.color=0xff1976d2.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f/scale
                    if(selection.isNotEmpty()){val index=indexFor(page)
                        for(selectedId in selection){val b=index.boundsFor(selectedId)?.expanded(4/scale)?:continue
                            val dx=if(selectedId in moveIds)moveDx else 0f;val dy=if(selectedId in moveIds)moveDy else 0f
                            canvas.drawRect(b.left+dx,b.top+dy,b.right+dx,b.bottom+dy,paint)}
                    }
                    eraserPoint?.let{canvas.drawCircle(it.x,it.y,eraserRadius/scale,paint)}
                    if(ruler){paint.color=0x80648198.toInt();canvas.drawLine(40f,120f,950f,120f,paint)}
                    paint.style=Paint.Style.FILL
                }
                canvas.restore()
            }else if(scene){paint.color=0xff81909e.toInt();paint.textSize=24f;canvas.drawText("페이지를 불러오는 중…",50f,80f,paint)}
            if(overlay&&id==searchPageId){
                canvas.save();canvas.clipRect(0f,0f,page?.width?:1000f,page?.height?:pageHeight)
                paint.reset();paint.isAntiAlias=true;paint.color=0x55ffbd29
                for(b in searchBounds)canvas.drawRect(b.left,b.top,b.right,b.bottom,paint)
                paint.color=0xffe49800.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f/scale
                for(b in searchBounds)canvas.drawRect(b.left,b.top,b.right,b.bottom,paint)
                paint.style=Paint.Style.FILL;canvas.restore()
            }
            if(overlay&&i==currentIndex){paint.color=0x401397ed;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density/scale;canvas.drawRect(0f,0f,1000f,pageHeight,paint);paint.style=Paint.Style.FILL}
            canvas.restore()
        }
        if(scene&&!rasterizing)background.endFrame()
        if(overlay&&telemetry){
            val density=resources.displayMetrics.density
            paint.style=Paint.Style.FILL;paint.color=0xb326292e.toInt();paint.alpha=(179*hudOpacity).toInt()
            canvas.drawRoundRect(RectF(14*density,height-92*density,340*density,height-58*density),17*density,17*density,paint)
            paint.color=Color.WHITE;paint.alpha=(255*hudOpacity).toInt();paint.textSize=12*density
            canvas.drawText(telemetryText,27*density,height-70*density,paint);paint.alpha=255
        }
    }
    private fun maintainVisiblePages(viewport:List<VisiblePage> = visiblePageGeometry()){
        val visible=HashSet<String>();var nearest=currentIndex;var distance=Float.MAX_VALUE
        for(geometry in viewport){
            val index=geometry.index;val top=geometry.top;val bottom=top+geometry.height*scale
            visible.add(pageIds[index]);requestPage(index)
            val d=maxOf(top-height/2f,height/2f-bottom,0f)
            if(d<distance){nearest=index;distance=d}
        }
        pageIds.getOrNull(currentIndex)?.let{visible.add(it)}
        contactPage?.id?.let{visible.add(it)}
        retainPageRequests(visible)
        if(pointer<0&&nearest!=currentIndex)post{if(pointer<0)setCurrent(nearest)}
        // Raw pages can be reloaded after the save barrier; derived Pictures/indexes never pin distant pages.
        if(pages.size>3)pages.keys.toList().filter{it !in visible}
            .forEach{pages.remove(it);pictures.remove(it);spatialIndexes.remove(it)}
    }
    private fun layersFor(page:NotePage):Iterable<Layer>{
        var cache=pictures[page.id]
        if(cache==null||cache.dirty){
            val previous=cache
            if(previous!=null)for(id in previous.invalid)previous.entries[id]?.layer?.let{invalidateSceneBounds(page,layerBounds(it))}
            cache=LayerCache(page.objects,previous);pictures[page.id]=cache
        }
        cache.reconcile(page)
        return cache.layers
    }
    private fun layerBounds(layer:Layer?)=when(layer){
        is Layer.Commands->layer.bounds
        is Layer.Image->layer.bounds
        null->null
    }
    /** Selection preview borrows committed recordings and changes only their transform. */
    private fun recordLayers(page:NotePage,moving:Set<String>):List<Layer> =
        layersFor(page).map{layer->when(layer){
            is Layer.Commands->layer.copy(moving=layer.commands.first().objectData.optString("id") in moving)
            is Layer.Image->layer.copy(moving=layer.objectData.optString("id") in moving)
        }}
    private fun replaceCached(page:NotePage,id:String,fragments:List<JSONObject>){
        val cache=pictures[page.id]?:return
        if(cache.dirty){
            cache.invalid.add(id);for(fragment in fragments)cache.invalid.add(fragment.getString("id"))
            return
        }
        // A fragment replaced again before the next draw never contributed pixels of its own.
        cache.entries[id]?.layer?.let{invalidateSceneBounds(page,layerBounds(it))}
        if(!cache.replace(id,fragments))cache.dirty=true
    }
    private fun invalidateSceneBounds(page:NotePage,bounds:InkBounds?){
        if(bounds==null||!bounds.left.isFinite()||!bounds.top.isFinite()||!bounds.right.isFinite()||!bounds.bottom.isFinite()){
            sceneTiles.clear();return
        }
        val index=pageIndices[page.id]?:return
        val top=originY+pageTop(index)*scale
        sceneTiles.invalidate(originX+bounds.left*scale-2,top+bounds.top*scale-2,
            originX+bounds.right*scale+2,top+bounds.bottom*scale+2)
    }
    private fun appendScenePicture(page:NotePage,picture:Picture,bounds:InkBounds):Boolean {
        if(hardwareFallback)return false
        val index=pageIndices[page.id]?:return false
        // Later page backdrops must not overlap this page's actual clip.
        if(continuous&&(!pagePositionsOrdered||page.height>(metadata.getOrNull(index)?.f("height",1414f)?:1414f)))return false
        val x=originX;val y=originY+pageTop(index)*scale;val factor=scale
        val pageWidth=page.width;val pageHeight=page.height
        return sceneTiles.append(width,height,originX,originY,factor,
            x+bounds.left*factor-2,y+bounds.top*factor-2,x+bounds.right*factor+2,y+bounds.bottom*factor+2){target->
            val saved=target.save()
            target.translate(x,y);target.scale(factor,factor);target.clipRect(0f,0f,pageWidth,pageHeight)
            target.drawPicture(picture);target.restoreToCount(saved)
        }
    }
    private fun invalidateLayers(page:NotePage,ids:Collection<String>){
        markHardwareDirty()
        pictures[page.id]?.let{cache->cache.dirty=true;cache.invalid.addAll(ids)}
    }
    private fun updateEditCaches(page:NotePage,change:ObjectChange){
        val afterIds=change.after.mapTo(HashSet()){it.optString("id")}
        val ids=change.before.mapTo(HashSet()){it.optString("id")}.apply{addAll(afterIds)}
        pictures[page.id]?.let{cache->
            if(change.after.any{!cache.tracks(it)}||change.before.any{it.optString("id") !in afterIds&&cache.entries.containsKey(it.optString("id"))})
                invalidateLayers(page,ids)
        }
        clearSearchHighlights(page.id)
        spatialIndexes[page.id]?.let{index->
            for(id in ids)if(id !in afterIds)index.remove(id)
            for(obj in change.after)if(!index.tracks(obj))index.put(obj)
        }
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
        if(pointer>=0&&p!=null)transitionContact(erasing||held||tool==Tool.ERASER,p)
        invalidate()
    }
    override fun onGenericMotionEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS||event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE){
            if(!stylus(event,event.actionIndex))return super.onGenericMotionEvent(event)
            updateButtons(event)
            // Button coordinates are not contact samples; MOVE/UP alone advance an active eraser.
            if(pointer>=0&&!erasing){val index=event.findPointerIndex(pointer);if(index>=0&&stylus(event,index)){
                transitionContact(buttonEraser(event,index)||tool==Tool.ERASER,point(event,index))}}
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
        // Inserting typed text is a UI tap, including with "pen only" handwriting enabled.
        // A moved finger still pans; a second pointer still zooms. Wait for lazy pages instead of dropping taps.
        if(action==MotionEvent.ACTION_DOWN)textTapGesture=tool==Tool.TEXT&&!readOnly&&!buttonEraser(event,index)
        if(textTapGesture){
            if(action==MotionEvent.ACTION_UP){
                val tapped=touchTravel<18*resources.displayMetrics.density&&maxTouches==1
                val pageIndex=if(tapped)pageIndexAt(event.x,event.y)else null
                val fullPage=event.eventTime-touchStartTime>=500
                resetContact(ContactState.IDLE);textTapGesture=false;maxTouches=0
                if(pageIndex!=null){
                    setCurrent(pageIndex);val p=pagePoint(event.x,event.y)
                    val tap=TextTap(pageIds[pageIndex],p.x,p.y,fullPage)
                    if(currentPage!=null)performTextTap(tap)else{pendingTextTap=tap;requestPage(pageIndex)}
                    performClick()
                }
                return true
            }
            if(action==MotionEvent.ACTION_CANCEL){textTapGesture=false;cancelContact();maxTouches=0;return true}
            return handlePan(event)
        }
        if(action==MotionEvent.ACTION_CANCEL){cancelContact();maxTouches=0;return true}
        if(pointer>=0){
            val pIndex=event.findPointerIndex(pointer)
            if(pIndex<0){cancelContact();return true}
            if(action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(index)!=pointer)return true
            if(action==MotionEvent.ACTION_POINTER_DOWN)return true // Palm/finger while writing cannot change the viewport.
            if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&Build.VERSION.SDK_INT>=33&&event.flags and MotionEvent.FLAG_CANCELED!=0){cancelContact();return true}
            if(action==MotionEvent.ACTION_MOVE){
                requestUnbufferedDispatch(event)
                consumeEvent(event,pIndex)
                // Keep every historical sample, but submit only the event's complete snapshot.
                updateFront()
                invalidate();return true
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
                consumeEvent(event,pIndex)
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
            onContactChanged(true)
            eraseBefore.clear();erasePositions.clear();contactOrderChanges.clear();contactAdded.clear()
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
            MotionEvent.ACTION_DOWN->{contactState=ContactState.PANNING;onContactChanged(true);touchX=cx;touchY=cy;touchSpan=span;touchTravel=0f;touchStartTime=event.eventTime;maxTouches=1}
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
                maxTouches=0;resetContact(ContactState.IDLE);sceneChanged()
            }
        };return true
    }
    private fun beginInk(p:InkPoint){
        frontStroke=null
        erasing=false;eraserPoint=null;points=mutableListOf(p);pointPublication.clear();pointPublication.append(p);heldShape=false;contactTool=tool
        contactState=if(tool==Tool.LASSO)ContactState.SELECTING else ContactState.DRAWING
        val page=contactPage?:return
        if(tool!=Tool.LASSO&&tool!=Tool.HAND&&tool!=Tool.LASER)clearSearchHighlights(page.id)
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
            Tool.LASSO->{val index=indexFor(page)
                moveBefore=selected().filter{!it.optBoolean("locked")}.takeIf{list->list.any{
                    index.boundsFor(it.optString("id"))?.expanded(10/scale)?.contains(p.x,p.y)==true
                }}?:emptyList() // Page objects stay untouched during preview; borrow until ownership transfers on UP.
                moveStart=if(moveBefore.isNotEmpty())p else null
                moveIds=moveBefore.mapTo(HashSet()){it.getString("id")};moveDx=0f;moveDy=0f
                if(moveBefore.isNotEmpty())clearSearchHighlights(page.id)
                moveLayers=if(moveBefore.isNotEmpty())recordLayers(page,moveIds) else null
                if(moveBefore.isEmpty()){selection.clear();onSelection(emptyList())}}
            Tool.TEXT,Tool.STICKY,Tool.IMAGE->{onInsert(tool,p.x,p.y);resetContact(ContactState.IDLE)}
            else->Unit
        }
        val stroke=live
        val host=frontBuffer
        // Freeze style once per contact; only the sample snapshot changes during MOVE.
        frontStroke=stroke?.takeIf{!sceneDirty&&!hardwareFallback&&!commitFrontPending&&
            tool==Tool.PEN&&host?.presented==true&&frontEligible(it)}?.copyJson()
        frontContact=frontStroke?.let{host?.renderFront(frontFrame(it))}==true
        if(!frontContact)host?.hideForLegacy()
    }
    private fun frontEligible(stroke:JSONObject):Boolean{
        val name=stroke.optString("brush")
        return name in setOf("fountain","ballpoint")&&stroke.f("opacity",1f)==1f&&
            Color.alpha(renderer.color(stroke.optString("color","#172033")))==255
    }
    private fun frontFrame(stroke:JSONObject)=InkFrontFrame(
        stroke,
        originX,originY+pageTop(currentIndex)*scale,scale,
        contactPage?.width?:1000f,contactPage?.height?:1414f,pointPublication.snapshot())
    private fun updateFront(){
        if(frontContact){val stroke=frontStroke
            if(stroke==null||frontBuffer?.renderFront(frontFrame(stroke))!=true){
                frontBuffer?.cancelFront();frontContact=false;frontBuffer?.hideForLegacy()
            }
        }
    }
    private fun movePoint(p:InkPoint){
        if(contactPage==null)return
        if(heldShape)return
        when(contactTool){
            Tool.PEN,Tool.HIGHLIGHTER,Tool.LASER->{
                val moved=points.isEmpty()||InkGeometry.distance(points.last(),p)*scale>.25f
                points+=p;pointPublication.append(p)
                if(moved){
                    removeCallbacks(hold);if(drawHold&&contactTool==Tool.PEN&&!ruler)postDelayed(hold,650)
                }
            }
            Tool.SHAPE->{live?.put("x2",p.x)?.put("y2",p.y)}
            Tool.TAPE->{val a=points.first();live?.put("x",min(a.x,p.x))?.put("y",min(a.y,p.y))?.put("w",abs(a.x-p.x))?.put("h",abs(a.y-p.y))}
            Tool.LASSO->{val start=moveStart
                if(start!=null){moveDx=p.x-start.x;moveDy=p.y-start.y;markHardwareDirty()}
                else if(points.isEmpty()||InkGeometry.distance(points.last(),p)*scale>2)points+=p}
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
        contactPage?.let{clearSearchHighlights(it.id)}
        erasing=true;contactTool=Tool.ERASER;contactState=ContactState.ERASING;eraserPoint=null;eraseAlong(p)
    }
    private fun consumeEvent(event:MotionEvent,index:Int){
        val p=point(event,index)
        if(erasing){
            val samples=ArrayList<InkPoint>(event.historySize+1)
            for(history in 0 until event.historySize)samples.add(point(event,index,history))
            samples.add(p);erasePath(samples)
        }else{
            // Historical samples precede this event's button state.
            for(history in 0 until event.historySize)movePoint(point(event,index,history))
            if(tool==Tool.ERASER||buttonEraser(event,index))transitionContact(true,p)else movePoint(p)
        }
    }
    private fun transitionContact(toEraser:Boolean,p:InkPoint){
        if(pointer<0||erasing||!toEraser||tool !in setOf(Tool.PEN,Tool.HIGHLIGHTER,Tool.ERASER,Tool.LASER))return
        // A temporary eraser is latched to this contact, not to the physical button's release.
        if(frontContact){frontBuffer?.cancelFront();frontContact=false;frontBuffer?.hideForLegacy()}
        finalizeInkSegment();beginErase(p)
    }
    private fun eraseAlong(p:InkPoint)=erasePath(listOf(p))
    private fun erasePath(samples:List<InkPoint>){
        val page=contactPage?:return
        val radius=eraserRadius/scale
        val centers=ArrayList<InkPoint>()
        var old=eraserPoint
        var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
        var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
        for(p in samples){
            val previous=old
            val steps=if(previous==null)1 else ceil(InkGeometry.distance(previous,p)/(radius*.4f)).toInt().coerceAtLeast(1)
            for(step in 1..steps){
                val t=step.toFloat()/steps
                val center=if(previous==null)p else p.copy(x=previous.x+(p.x-previous.x)*t,y=previous.y+(p.y-previous.y)*t)
                centers.add(center);left=min(left,center.x-radius);top=min(top,center.y-radius)
                right=max(right,center.x+radius);bottom=max(bottom,center.y+radius)
            }
            old=p
        }
        eraserPoint=old
        if(centers.isEmpty())return
        val event=InkEraseEvent(centers,radius)
        val index=indexFor(page)
        // Circle order stays exact within each independent object; immutable geometry is installed
        // only once per event instead of cloning/reindexing every survivor after every circle.
        for(obj in index.query(InkBounds(left,top,right,bottom))){
            val stroke=obj.optString("type")=="stroke"
            if(obj.optBoolean("locked")||obj.optBoolean("hidden")||
                !(wholeEraser&&tool==Tool.ERASER||stroke||obj.optString("type") in setOf("text","shape")))continue
            val masked=obj.optString("type") in setOf("text","shape") || obj.optString("brush")=="highlighter"
            val fragments=if(preciseEraser&&masked)listOf(ObjectEraseMask.erase(obj,centers,radius))
            else if(preciseEraser&&stroke)index.eraseAlong(obj,event)else{
                if(!index.hitAlong(obj,event))continue
                emptyList()
            }
            if(fragments.size==1&&fragments[0]===obj)continue
            stageReplace(page,obj,fragments)
        }
    }
    private fun stageReplace(page:NotePage,obj:JSONObject,fragments:List<JSONObject>){
        val id=obj.getString("id");val objects=page.objects as? IndexedObjectList
        val pos=objects?.indexOfId(id)?:page.objects.indexOfFirst{it.optString("id")==id}
        if(pos<0||page.objects[pos]!==obj)return
        markHardwareDirty()
        if(id !in contactAdded&&id !in eraseBefore){
            // Invert earlier splices only for a newly affected original. No full-page position snapshot.
            var original=pos
            for(change in contactOrderChanges.asReversed())if(original>=change.first+change.second)
                original+=1-change.second
            eraseBefore[id]=obj;erasePositions[id]=original
        }
        contactAdded.remove(id)
        if(objects!=null)objects.replaceById(id,fragments)
        else {page.objects.removeAt(pos);page.objects.addAll(pos,fragments)}
        if(fragments.size!=1)contactOrderChanges.add(pos to fragments.size)
        val index=spatialIndexes[page.id]
        index?.remove(id)
        for(fragment in fragments){val nextId=fragment.getString("id")
            if(nextId !in eraseBefore)contactAdded.add(nextId)
            index?.put(fragment)
        }
        replaceCached(page,id,fragments)
    }
    private fun finalizeInkSegment(){
        removeCallbacks(hold)
        val page=contactPage?:return
        val obj=live ?: return
        if(contactTool!=Tool.LASER){
            if(obj.optString("type")=="stroke"){
                val samples=JSONArray();for(point in points)samples.put(point.json());obj.put("points",samples)
            }
            val scratch=if(scribbleErase&&contactTool==Tool.PEN&&!heldShape)
                InkGeometry.scratchTargets(page.objects,points,scale/resources.displayMetrics.density)else emptyList()
            if(scratch.isNotEmpty()){
                scratch.forEach{stageReplace(page,it,emptyList())}
                onStatus("${scratch.size}개 항목을 지웠습니다.")
            }else{
                // The contact transfers ownership; later edits replace objects instead of mutating their geometry.
                page.objects.add(obj);contactAdded.add(obj.getString("id"));spatialIndexes[page.id]?.put(obj)
                pictures[page.id]?.takeUnless{it.dirty}?.append(obj)
            }
        }
        live=null;points.clear();pointPublication.clear();heldShape=false
    }
    fun finishContact(){
        removeCallbacks(hold);if(frontContact)updateFront();val page=contactPage
        if(page!=null){
            if(contactTool==Tool.LASSO){
                if(moveBefore.isNotEmpty()){
                    if(moveDx!=0f||moveDy!=0f){
                        // Preview recordings borrow immutable geometry; only changed runs need replacing.
                        moveLayers=null
                        val changed=moveBefore.map{InkGeometry.transform(it,moveDx,moveDy)}
                        val positions=positions(page)
                        val change=ObjectChange(moveBefore,changed,positions,positions)
                        change.applyOwnedAfter(page)
                        record(page,change,false)
                    }
                }else if(points.size>2){
                    val area=InkBounds(points.minOf{it.x},points.minOf{it.y},points.maxOf{it.x},points.maxOf{it.y})
                    val index=indexFor(page);val candidates=index.query(area).mapTo(HashSet()){it.getString("id")}
                    page.objects.filter{obj->obj.optString("id") in candidates&&InkGeometry.insideLasso(obj,points,index.boundsFor(obj.optString("id")))}
                        .forEach{selection+=it.getString("id")}
                }
                onSelection(selected())
            }else{
                if(!erasing)finalizeInkSegment()
                if(eraseBefore.isNotEmpty()||contactAdded.isNotEmpty()){
                    val affected=eraseBefore.keys+contactAdded
                    val finalPositions=if(contactOrderChanges.isEmpty()&&eraseBefore.isEmpty())affectedPositions(page,affected)else positions(page)
                    val after=affected.mapNotNull{finalPositions[it]}.sorted().map{page.objects[it]}
                    record(page,ObjectChange(eraseBefore.values.toList(),after,erasePositions.toMap(),finalPositions),false)
                }
            }
        }
        if(frontContact){
            // Keep the completed front visible until the next frame publishes the scene. A rapid next
            // contact falls back to the View and invalidates old surface versions before drawing.
            commitFrontPending=true;sceneDirty=true;frontContact=false
        }else if(frontBuffer!=null)sceneChanged(clearTiles=false)
        resetContact(ContactState.IDLE,preserveHeldKey=true)
    }
    fun cancelContact(){
        removeCallbacks(hold);val page=contactPage;val hadContact=pointer>=0||page!=null
        val cancelledFront=frontContact
        if(cancelledFront)frontBuffer?.cancelFront()
        frontContact=false
        if(page!=null){
            if(eraseBefore.isNotEmpty()||contactAdded.isNotEmpty()){
                val affected=eraseBefore.keys+contactAdded
                val current=affectedPositions(page,affected).values.sorted().map{page.objects[it]}
                val restore=ObjectChange(current,eraseBefore.values.toList(),afterPositions=erasePositions)
                restore.applyOwnedAfter(page);updateEditCaches(page,restore)
            }
        }
        if(hadContact&&!cancelledFront)sceneChanged(clearTiles=false)
        resetContact(if(pointer>=0)ContactState.CANCELLED else ContactState.IDLE)
    }
    private fun resetContact(state:ContactState,preserveHeldKey:Boolean=false){
        val hadContact=hasActiveContact
        eraseBefore.clear();erasePositions.clear();contactOrderChanges.clear();contactAdded.clear();erasing=false
        pointer=-1;contactPage=null;points.clear();pointPublication.clear();live=null;frontStroke=null;moveBefore=emptyList();moveStart=null
        moveIds=emptySet();moveLayers=null;moveDx=0f;moveDy=0f
        // The contact latch always ends here; a physically held key survives only a normal lift.
        if(!preserveHeldKey)keyEraser=false
        stylusButtons.reset()
        contactTool=null;heldShape=false;eraserPoint=null;contactState=state;invalidate()
        if(hadContact)onContactChanged(false)
    }
    private fun positions(page:NotePage):Map<String,Int>{
        val result=LinkedHashMap<String,Int>(page.objects.size*4/3+1)
        page.objects.forEachIndexed{index,obj->result[obj.getString("id")]=index}
        return result
    }
    private fun affectedPositions(page:NotePage,ids:Collection<String>):Map<String,Int>{
        val indexed=page.objects as? IndexedObjectList
        if(indexed==null){val all=positions(page);return ids.mapNotNull{id->all[id]?.let{id to it}}.toMap()}
        val result=LinkedHashMap<String,Int>()
        for(id in ids){val position=indexed.indexOfId(id);if(position>=0)result[id]=position}
        return result
    }
    private fun advanceRevisions(page:NotePage,change:ObjectChange,positions:Map<String,Int>):ObjectChange{
        val previous=change.before.associateBy{it.optString("id")}
        val published=ArrayList<JSONObject>(change.after.size)
        change.after.forEach{after->val id=after.optString("id")
            val value=page.objects[checkNotNull(positions[id]){"Missing installed edit object: $id"}]
            check(value.optString("id")==id){"Incorrect installed edit position: $id"}
            val next=maxOf(revisionClock[id]?:0,previous[id]?.optLong("revision",0)?:0,after.optLong("revision",1)-1)+1
            revisionClock[id]=next;value.put("revision",next);published.add(value)
        }
        // Undo/redo replay copies are fresh. Never revise shared history or already queued save snapshots.
        val beforeOrder=change.before.mapNotNull{obj->val id=obj.optString("id");change.beforePositions[id]?.let{id to it}}.toMap()
        val afterOrder=published.associate{obj->val id=obj.optString("id");id to positions.getValue(id)}
        return ObjectChange(change.before,published,beforeOrder,afterOrder)
    }
    private fun record(page:NotePage,change:ObjectChange,apply:Boolean=true){
        if(apply)change.apply(page)
        val finalPositions=change.afterPositions.takeIf{positions->
            (positions.size==page.objects.size||change.before.isEmpty())&&change.after.all{obj->
                val index=positions[obj.optString("id")]
                index!=null&&index in page.objects.indices&&page.objects[index].optString("id")==obj.optString("id")
            }
        }?:if(change.before.isEmpty())affectedPositions(page,change.after.map{it.getString("id")})else positions(page)
        val published=advanceRevisions(page,change,finalPositions)
        val history=histories.getOrPut(page.id){EditHistory(8*1024*1024)}
        val generation=loadGeneration
        history.pushDeferred(published,{task->post{task()}}){discarded->
            if(generation==loadGeneration&&histories[page.id]===history)for(id in discarded)revisionClock.remove(id)
        }
        for(id in history.takeDiscardedObjectIds())revisionClock.remove(id)
        if(histories.size>4)histories.keys.firstOrNull{it!=page.id}?.let{evicted->
            histories.remove(evicted)?.let{removed->
                removed.retainedObjectIds.forEach{revisionClock.remove(it)};removed.clear()
            }
        }
        updateEditCaches(page,published);onEdit(page.id,published,finalPositions);sceneChanged(clearTiles=false)
    }
    fun selected()=currentPage?.objects?.filter{it.optString("id") in selection}?:emptyList()
    fun selectAll(){selection.clear();currentPage?.objects?.filter{!it.optBoolean("hidden")&&it.optString("type")!="ocrIndex"}?.forEach{selection+=it.getString("id")};onSelection(selected());invalidate()}
    fun add(obj:JSONObject){if(readOnly)return;cancelContact();val page=currentPage?:return;record(page,ObjectChange(emptyList(),listOf(obj.copyJson())))}
    internal fun commitText(pageId:String,before:JSONObject?,after:JSONObject?){
        if(readOnly)return
        val page=pages[pageId]?:return
        val original=before?.let{old->page.objects.firstOrNull{it.optString("id")==old.optString("id")}} ?: before
        if(original==null&&after==null)return
        if(original?.toString()==after?.toString())return
        val pos=positions(page)
        record(page,ObjectChange(listOfNotNull(original?.copyJson()),listOfNotNull(after?.copyJson()),pos,
            if(original==null&&after!=null)pos+(after.getString("id") to page.objects.size)else pos))
    }
    internal fun reorderTextObject(id:String,front:Boolean){
        val page=currentPage?:return;val obj=page.objects.firstOrNull{it.optString("id")==id}?:return
        val rest=page.objects.filter{it.optString("id")!=id}
        val order=if(front)rest+obj else listOf(obj)+rest
        record(page,ObjectChange(listOf(obj.copyJson()),listOf(obj.copyJson()),positions(page),
            order.mapIndexed{i,value->value.getString("id") to i}.toMap()))
    }
    fun changeObjects(before:List<JSONObject>,after:List<JSONObject>){if(readOnly)return;cancelContact();val page=currentPage?:return;val pos=positions(page)
        record(page,ObjectChange(before.map{it.copyJson()},after.map{it.copyJson()},pos,pos));selection.retainAll(page.objects.map{it.getString("id")}.toSet());onSelection(selected())}
    fun deleteSelection(){changeObjects(selected().filter{!it.optBoolean("locked")},emptyList())}
    fun bringSelectionToFront(){
        if(readOnly)return;cancelContact();val page=currentPage?:return;val list=selected().filter{!it.optBoolean("locked")};if(list.isEmpty())return
        val before=positions(page);val moved=list.map{it.optString("id")}.toSet()
        val order=page.objects.filter{it.optString("id") !in moved}+list
        val after=order.mapIndexed{i,obj->obj.getString("id") to i}.toMap()
        record(page,ObjectChange(list.map{it.copyJson()},list.map{it.copyJson()},before,after));onSelection(selected())
    }
    fun scaleSelection(factor:Float){if(readOnly)return;cancelContact();val list=selected().filter{!it.optBoolean("locked")};if(list.isEmpty())return
        val boxes=list.map{InkGeometry.bounds(it)};val cx=boxes.minOf{it.left};val cy=boxes.minOf{it.top}
        changeObjects(list,list.map{InkGeometry.transform(it,0f,0f,factor,cx,cy)})}
    fun toggleTape(obj:JSONObject){if(!readOnly)changeObjects(listOf(obj),listOf(obj.copyJson().put("revealed",!obj.optBoolean("revealed"))))}
    fun undo(){if(readOnly)return;cancelContact();val page=currentPage?:return;val change=histories[page.id]?.undo()?:return
        change.apply(page);val positions=positions(page);val published=advanceRevisions(page,change,positions);updateEditCaches(page,published)
        onEdit(page.id,published,positions);selection.clear();onSelection(emptyList());sceneChanged(clearTiles=false)}
    fun redo(){if(readOnly)return;cancelContact();val page=currentPage?:return;val change=histories[page.id]?.redo()?:return
        change.apply(page);val positions=positions(page);val published=advanceRevisions(page,change,positions);updateEditCaches(page,published)
        onEdit(page.id,published,positions);selection.clear();onSelection(emptyList());sceneChanged(clearTiles=false)}
    fun close(){
        cancelContact();repository.cancelPageRequests(this);loadGeneration++;commitFrontPending=false
        clearSearchHighlights();frontBuffer?.hideForLegacy();frontBuffer=null;sceneTiles.clear();background.close()
        canonicalRasterizer.close();hardwareScene=null
        histories.values.forEach{it.clear()}
        pages.clear();pictures.clear();spatialIndexes.clear();histories.clear();revisionClock.clear();pending.clear();pendingSelected.clear();selection.clear()
        pageIds=emptyList();pageIndices.clear();metadata=emptyList();tops=FloatArray(0);bottoms=FloatArray(0)
    }
    override fun onDetachedFromWindow(){
        cancelContact();markHardwareDirty();sceneTiles.clear();frontBuffer?.hideForLegacy()
        super.onDetachedFromWindow()
    }
    override fun performClick():Boolean {super.performClick();return true}
}

/** Conservative bounds; the original screen-space cull still decides what is drawn. */
internal fun pageViewportRange(tops:FloatArray,bottoms:FloatArray,from:Float,to:Float,ordered:Boolean):IntRange {
    if(!ordered||!from.isFinite()||!to.isFinite()||bottoms.size!=tops.size)return tops.indices
    if(tops.isEmpty()||from>to)return IntRange.EMPTY
    var low=0;var high=tops.size
    while(low<high){val middle=(low+high) ushr 1;if(bottoms[middle]<from)low=middle+1 else high=middle}
    val first=low
    low=first;high=tops.size
    while(low<high){val middle=(low+high) ushr 1;if(tops[middle]<=to)low=middle+1 else high=middle}
    // Adjacent guards retain boundary pages when large-document float transforms round differently.
    return (first-1).coerceAtLeast(0)..low.coerceAtMost(tops.lastIndex)
}
