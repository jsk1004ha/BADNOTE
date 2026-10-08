package com.inkforge.notesstudio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File

class AudioController(private val context:Context,private val repository:NoteRepository){
    private var recorder:MediaRecorder?=null
    private var player:MediaPlayer?=null
    private var assetId=""
    private var started=0L
    val recording get()=recorder!=null
    @Suppress("DEPRECATION")
    fun start(){
        check(recorder==null)
        assetId=uid("recording")+".m4a"
        val next=MediaRecorder()
        try{next.setAudioSource(MediaRecorder.AudioSource.MIC);next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(96000);next.setAudioSamplingRate(44100);next.setOutputFile(repository.asset(assetId).path);next.prepare();next.start();started=System.currentTimeMillis();recorder=next}
        catch(e:Exception){next.release();repository.asset(assetId).delete();throw e}
    }
    fun stop(pageId:String,pageIndex:Int):JSONObject?{
        val current=recorder?:return null;recorder=null
        try{current.stop();return json("id" to uid("audio"),"title" to "녹음","src" to "asset:$assetId","mime" to "audio/mp4",
            "createdAt" to started,"duration" to (System.currentTimeMillis()-started)/1000.0,"pageId" to pageId,"pageIndex" to pageIndex)}
        catch(e:Exception){repository.asset(assetId).delete();throw e}
        finally{current.release()}
    }
    fun play(clip:JSONObject){
        player?.release();player=null
        val reference=clip.optString("src");require(reference.startsWith("asset:")){"녹음 자산이 없습니다."}
        val next=MediaPlayer()
        try{next.setDataSource(repository.asset(reference.removePrefix("asset:")).path);next.setOnPreparedListener{it.start()};next.setOnCompletionListener{it.release();if(player===it)player=null}
            next.prepareAsync();player=next}
        catch(e:Exception){next.release();throw e}
    }
    fun close(){player?.release();player=null;recorder?.let{try{it.stop()}catch(_:Exception){};it.release()};recorder=null}
}
