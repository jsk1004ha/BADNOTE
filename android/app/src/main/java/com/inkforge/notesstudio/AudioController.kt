package com.inkforge.notesstudio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class AudioController(private val context:Context,private val repository:NoteRepository){
    internal data class PendingRecording(val assetId:String,val pageId:String,val pageIndex:Int,
                                         val startedAt:Long,val stoppedAt:Long)
    private var recorder:MediaRecorder?=null
    private var player:MediaPlayer?=null
    private var assetId=""
    private var started=0L
    private var playbackGeneration=0L
    private var closed=false
    var onPlaybackState:(String)->Unit={}
    var onPlaybackError:(String)->Unit={}
    val recording get()=recorder!=null
    @Suppress("DEPRECATION")
    fun start(){
        check(!closed&&recorder==null)
        val id=uid("recording")+".m4a"
        val pending=repository.asset("$id.pending")
        check(!repository.asset(id).exists()&&pending.createNewFile()){ "녹음 자산 ID 충돌" }
        assetId=id
        var next:MediaRecorder?=null
        try{next=MediaRecorder();next.setAudioSource(MediaRecorder.AudioSource.MIC);next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(96000);next.setAudioSamplingRate(44100);next.setOutputFile(pending.path);next.prepare();next.start();started=System.currentTimeMillis();recorder=next}
        catch(e:Exception){runCatching{next?.release()};pending.delete();assetId="";throw e}
    }
    internal fun stop(pageId:String,pageIndex:Int):PendingRecording?{
        val current=recorder?:return null;recorder=null
        val id=assetId
        try{try{current.stop()}finally{current.release()}
            val stopped=System.currentTimeMillis()
            assetId=""
            return PendingRecording(id,pageId,pageIndex,started,stopped)}
        catch(e:Exception){repository.asset("$id.pending").delete();assetId="";throw e}
    }
    /** Called on the repository executor after the UI thread has released MediaRecorder. */
    internal fun finalizeRecording(recording:PendingRecording):JSONObject{
        val id=recording.assetId
        try{val pending=repository.asset("$id.pending")
            require(pending.isFile){"녹음 임시 파일이 없습니다."}
            FileOutputStream(pending,true).use{it.fd.sync()}
            val clip=json("id" to uid("audio"),"title" to "녹음","src" to "asset:$id","mime" to "audio/mp4",
                "createdAt" to recording.startedAt,"duration" to (recording.stoppedAt-recording.startedAt)/1000.0,
                "pageId" to recording.pageId,"pageIndex" to recording.pageIndex)
            repository.finalizePendingAsset(id,validate={source->validateRecordedM4a(source)})
            return clip
        }catch(e:Exception){repository.asset("$id.pending").delete();throw e}
    }
    internal fun discardPending(recording:PendingRecording){repository.asset("${recording.assetId}.pending").delete()}
    /** File validation is outside the short DB transaction; only the latest document body is changed. */
    internal fun persistRecording(recording:PendingRecording,documentId:String):JSONObject?{
        if(repository.document(documentId)==null){discardPending(recording);return null}
        val clip=finalizeRecording(recording)
        try{repository.transaction{
            val latest=requireNotNull(repository.document(documentId)){"녹음할 노트가 없습니다."}
            val clips=latest.data.array("audio");clips.put(clip);latest.data.put("audio",clips)
            repository.putDocument(latest)
        }}catch(error:Exception){
            val referenced=runCatching{repository.document(documentId)?.data?.array("audio")?.objects()
                ?.any{it.optString("src")=="asset:${recording.assetId}"}==true}.getOrDefault(true)
            if(!referenced)repository.asset(recording.assetId).delete()
            throw error
        }
        return clip
    }
    fun play(clip:JSONObject){
        if(closed){onPlaybackError("오디오가 종료되었습니다.");return}
        stopPlayback()
        val reference=clip.optString("src")
        if(!reference.startsWith("asset:")){onPlaybackError("녹음 자산이 없습니다.");return}
        val source=runCatching{repository.asset(reference.removePrefix("asset:"))}.getOrElse{
            onPlaybackError("녹음 자산 경로가 잘못되었습니다.");return
        }
        if(!source.isFile||source.length()==0L){onPlaybackError("녹음 파일이 없습니다.");return}
        val generation=++playbackGeneration
        val next=MediaPlayer()
        player=next
        try{
            next.setDataSource(source.path)
            next.setOnPreparedListener{current->if(player===current&&playbackGeneration==generation){
                try{current.start();onPlaybackState("playing")}
                catch(error:Exception){playbackFailed(current,generation,"재생을 시작할 수 없습니다: ${error.message}")}
            }}
            next.setOnCompletionListener{current->if(player===current&&playbackGeneration==generation){
                stopPlayback();onPlaybackState("completed")
            }}
            next.setOnErrorListener{current,what,extra->
                playbackFailed(current,generation,"녹음을 재생할 수 없습니다 ($what/$extra).")
                true
            }
            onPlaybackState("preparing");next.prepareAsync()
        }catch(error:Exception){playbackFailed(next,generation,"녹음을 재생할 수 없습니다: ${error.message}")}
    }
    private fun playbackFailed(current:MediaPlayer,generation:Long,message:String){
        if(player!==current||playbackGeneration!=generation)return
        stopPlayback();onPlaybackState("failed");onPlaybackError(message)
    }
    fun stopPlayback(){
        ++playbackGeneration
        player?.let{current->player=null
            runCatching{current.setOnPreparedListener(null);current.setOnCompletionListener(null);current.setOnErrorListener(null)}
            runCatching{current.release()}
        }
    }
    fun close(){closed=true;stopPlayback();val current=recorder;recorder=null
        try{current?.let{try{it.stop()}catch(_:Exception){};it.release()}}
        finally{if(assetId.isNotBlank())repository.asset("$assetId.pending").delete();assetId=""}}

    companion object {
        /** Only the newly recorded MPEG-4/AAC output is restricted; playback accepts legacy containers. */
        internal fun validateRecordedM4a(file:File){
            require(file.isFile&&file.length()>0){"녹음 파일이 비어 있습니다."}
            val extractor=MediaExtractor()
            try{extractor.setDataSource(file.path)
                val audioTrack=(0 until extractor.trackCount).firstOrNull{index->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm"
                }
                require(audioTrack!=null){"녹음 파일의 AAC 오디오 트랙을 읽지 못했습니다."}
                extractor.selectTrack(audioTrack)
                require(extractor.readSampleData(ByteBuffer.allocateDirect(64*1024),0)>0){"녹음 파일에 오디오 샘플이 없습니다."}
            }finally{extractor.release()}
        }
    }
}
