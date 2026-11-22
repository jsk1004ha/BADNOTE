package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Picture
import kotlin.math.ceil

/** Screen-pixel tiles include the page backdrop: flattening transparent ink would change multiply/alpha.
 * Rasterization keeps the original viewport transform and clip: translating or clipping brush paths
 * at tile edges changes coverage. Only immutable tile copies are published to Pictures/display lists.
 */
internal class InkSceneCache {
    /** Ownership transfers once to the UI cache; unpublished/cancelled results may be recycled. */
    class Prepared(val bitmap:Bitmap,val images:List<Bitmap>,val width:Int,val height:Int,
                   val x:Float,val y:Float,val scale:Float){
        fun discard(){bitmap.recycle();images.forEach{it.recycle()}}
    }
    companion object {
        // Reserve current base/tiles, scratch, in-flight base/tiles and older published tile
        // generations still owned by the View/front display lists. Command nodes have their own budget.
        fun canRasterize(width:Int,height:Int)=width>0&&height>0&&width.toLong()*height*32<=128L*1024*1024
        fun rasterize(picture:Picture,width:Int,height:Int,x:Float,y:Float,scale:Float,
                      current:()->Boolean):Prepared? {
            if(!canRasterize(width,height)||!current())return null
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply{density=Bitmap.DENSITY_NONE}
            val images=ArrayList<Bitmap>()
            var transferred=false
            try{
                val canvas=Canvas(bitmap);canvas.drawPicture(picture);canvas.setBitmap(null)
                if(!current())return null
                val columns=ceil(width/256.0).toInt();val rows=ceil(height/256.0).toInt()
                for(row in 0 until rows)for(column in 0 until columns){
                    if(!current())return null
                    val left=column*256;val top=row*256
                    val image=Bitmap.createBitmap(minOf(256,width-left),minOf(256,height-top),Bitmap.Config.ARGB_8888)
                    image.density=Bitmap.DENSITY_NONE
                    Canvas(image).drawBitmap(bitmap,-left.toFloat(),-top.toFloat(),null);images.add(image)
                }
                transferred=true;return Prepared(bitmap,images,width,height,x,y,scale)
            }finally{if(!transferred){bitmap.recycle();images.forEach{it.recycle()}}}
        }
    }
    private class Tile(val bitmap:Bitmap,var dirty:Boolean=false)
    private var tiles=arrayOfNulls<Tile>(0)
    private var working:Bitmap?=null
    private var target:Canvas?=null
    private var scratch:Bitmap?=null
    private var scratchCanvas:Canvas?=null
    private val replace=Paint().apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.SRC)}
    private data class Append(val left:Float,val top:Float,val right:Float,val bottom:Float,val draw:(Canvas)->Unit)
    private val appends=ArrayList<Append>()
    private var width=0
    private var height=0
    private var columns=0
    private var x=Float.NaN
    private var y=Float.NaN
    private var scale=Float.NaN
    var hasContent=false;private set
    var cacheHits=0L;private set
    var coldScenes=0L;private set
    var rasterizedTiles=0L;private set

    fun prepare(width:Int,height:Int,x:Float,y:Float,scale:Float):Boolean {
        // Never thrash a partial viewport through an LRU, nor allocate a zoom-sized page bitmap.
        if(!canRasterize(width,height)){clear();return false}
        if(this.width!=width||this.height!=height||this.x!=x||this.y!=y||this.scale!=scale){
            clear();this.width=width;this.height=height;this.x=x;this.y=y;this.scale=scale
            columns=ceil(width/256.0).toInt()
            tiles=arrayOfNulls(columns*ceil(height/256.0).toInt())
        }
        return true
    }

    fun install(prepared:Prepared){
        clear();width=prepared.width;height=prepared.height;x=prepared.x;y=prepared.y;scale=prepared.scale
        columns=ceil(width/256.0).toInt();working=prepared.bitmap;target=Canvas(prepared.bitmap)
        tiles=Array<Tile?>(prepared.images.size){Tile(prepared.images[it])}
        rasterizedTiles+=tiles.size;hasContent=true;coldScenes++
    }

    /** Only a true top-of-stack append may composite directly onto the complete owned base. */
    fun append(width:Int,height:Int,x:Float,y:Float,scale:Float,left:Float,top:Float,right:Float,bottom:Float,
               draw:(Canvas)->Unit):Boolean {
        if(!hasContent||this.width!=width||this.height!=height||this.x!=x||this.y!=y||this.scale!=scale||
            !left.isFinite()||!top.isFinite()||!right.isFinite()||!bottom.isFinite())return false
        appends.add(Append(left,top,right,bottom,draw));return true
    }

    fun invalidate(left:Float,top:Float,right:Float,bottom:Float) {
        for(index in tiles.indices){
            val tile=tiles[index]?:continue
            val tileX=index%columns*256;val tileY=index/columns*256
            if(left<=tileX+tile.bitmap.width&&right>=tileX&&top<=tileY+tile.bitmap.height&&bottom>=tileY)tile.dirty=true
        }
    }

    fun draw(canvas:Canvas,render:(Canvas,List<Rect>?)->Unit) {
        var raster=working
        if(raster==null){
            raster=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply{density=Bitmap.DENSITY_NONE}
            working=raster
            target=Canvas(raster)
            // A cold viewport needs only one geometry replay, not one replay per published tile.
            render(checkNotNull(target),null);appends.clear();coldScenes++
        }
        val destination=checkNotNull(target)
        if(hasContent){
            if(tiles.any{it?.dirty==true}){
                // An append that races another kind of edit belongs to that scene replay instead.
                for(append in appends)invalidate(append.left,append.top,append.right,append.bottom)
                appends.clear()
                val damage=ArrayList<Rect>()
                for(index in tiles.indices){val tile=tiles[index]?:continue
                    if(tile.dirty){val left=index%columns*256;val top=index/columns*256
                        damage.add(Rect(left,top,left+tile.bitmap.width,top+tile.bitmap.height))}
                }
                if(damage.size==tiles.size)render(destination,null)
                else if(damage.isNotEmpty()){
                    var redraw=scratchCanvas
                    if(redraw==null){
                        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply{density=Bitmap.DENSITY_NONE}
                        scratch=bitmap;redraw=Canvas(bitmap);scratchCanvas=redraw
                    }
                    // Keep the canonical base intact outside damage. The scratch renderer still
                    // receives the original full viewport transform/clip, never a tile-local clip.
                    render(redraw,damage)
                    val source=checkNotNull(scratch)
                    for(rect in damage)destination.drawBitmap(source,rect,rect,replace)
                }
            }else if(appends.isNotEmpty()){
                for(append in appends){
                    append.draw(destination)
                    invalidate(append.left,append.top,append.right,append.bottom)
                }
                appends.clear()
            }
        }
        for(index in tiles.indices){
            val left=index%columns*256;val top=index/columns*256
            var tile=tiles[index]
            if(tile==null||tile.dirty){
                val tileWidth=minOf(256,width-left);val tileHeight=minOf(256,height-top)
                val bitmap=Bitmap.createBitmap(tileWidth,tileHeight,Bitmap.Config.ARGB_8888)
                bitmap.density=Bitmap.DENSITY_NONE
                Canvas(bitmap).drawBitmap(raster,-left.toFloat(),-top.toFloat(),null)
                tile=Tile(bitmap);tiles[index]=tile;rasterizedTiles++
            }
            else cacheHits++
            canvas.drawBitmap(tile.bitmap,left.toFloat(),top.toFloat(),null)
        }
        hasContent=true
    }

    fun clear() {
        // No recycle: an asynchronous front-buffer scene may still own an older tile.
        target?.setBitmap(null);target=null
        scratchCanvas?.setBitmap(null);scratchCanvas=null;scratch?.recycle();scratch=null;appends.clear()
        // The working raster is never published, so it can be released immediately.
        working?.recycle();working=null
        tiles=arrayOfNulls(0);width=0;height=0;columns=0;x=Float.NaN;y=Float.NaN;scale=Float.NaN;hasContent=false
    }
}
