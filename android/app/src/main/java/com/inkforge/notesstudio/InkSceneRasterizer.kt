package com.inkforge.notesstudio

import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The View owns current hardware presentation; this worker only derives an exact reusable bitmap base. */
internal class InkSceneRasterizer(private val publish:(Long,InkSceneCache.Prepared)->Unit) {
    private data class Request(val generation:Long,val picture:Picture,val width:Int,val height:Int,
        val x:Float,val y:Float,val scale:Float)
    private val main=Handler(Looper.getMainLooper())
    private val latest=AtomicReference<Request?>()
    private val running=AtomicBoolean()
    private val version=AtomicLong()
    private val worker=Executors.newSingleThreadExecutor { task->Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run()
    },"ink-canonical-raster").apply{isDaemon=true} }
    @Volatile private var closed=false
    val generation get()=version.get()
    fun advance():Long {latest.set(null);return version.incrementAndGet()}
    fun request(generation:Long,picture:Picture,width:Int,height:Int,x:Float,y:Float,scale:Float){
        if(closed||generation!=version.get())return
        latest.set(Request(generation,picture,width,height,x,y,scale))
        if(running.compareAndSet(false,true))worker.execute(::drain)
    }
    private fun drain(){
        while(true){
            val request=latest.getAndSet(null)
            if(request==null){
                running.set(false)
                if(latest.get()!=null&&running.compareAndSet(false,true))continue
                return
            }
            if(closed||request.generation!=version.get())continue
            val prepared=InkSceneCache.rasterize(request.picture,request.width,request.height,
                request.x,request.y,request.scale){!closed&&request.generation==version.get()}?:continue
            main.post {
                if(!closed&&request.generation==version.get())publish(request.generation,prepared)
                else prepared.discard()
            }
        }
    }
    fun close(){closed=true;advance();worker.shutdown()}
}
