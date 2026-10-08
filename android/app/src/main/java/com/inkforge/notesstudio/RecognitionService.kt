package com.inkforge.notesstudio

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.digitalink.recognition.*
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class RecognitionService:Closeable{
    val executor=Executors.newSingleThreadExecutor()
    private val recognizers=mutableMapOf<String,DigitalInkRecognizer>()
    private fun recognizer(language:String):DigitalInkRecognizer{
        recognizers[language]?.let{return it}
        val identifier=requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(language))
        val model=DigitalInkRecognitionModel.builder(identifier).build()
        val manager=RemoteModelManager.getInstance()
        if(!Tasks.await(manager.isModelDownloaded(model),15,TimeUnit.SECONDS))Tasks.await(manager.download(model,DownloadConditions.Builder().build()),120,TimeUnit.SECONDS)
        return DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()).also{recognizers[language]=it}
    }
    fun handwriting(objects:List<JSONObject>,width:Float,height:Float,language:String="ko",math:Boolean=false):String{
        val strokes=objects.filter{it.optString("type")=="stroke"&&it.optString("brush")!="highlighter"&&!it.optBoolean("hidden")}
        require(strokes.isNotEmpty()){"인식할 필기가 없습니다."}
        val lines=mutableListOf<MutableList<JSONObject>>()
        strokes.sortedBy{InkGeometry.bounds(it).top}.forEach{stroke->
            val b=InkGeometry.bounds(stroke);val center=(b.top+b.bottom)/2
            val line=lines.firstOrNull{list->val boxes=list.map{InkGeometry.bounds(it)}
                val y=boxes.map{(it.top+it.bottom)/2}.average().toFloat();abs(center-y)<max(24f,boxes.maxOf{it.height}*.55f)}
            if(line==null)lines+=mutableListOf(stroke)else line+=stroke
        }
        val languages=if(math)listOf("en-US") else when(language){"ja"->listOf("ja");"zh"->listOf("zh-Hans");"pt"->listOf("pt-BR");"en"->listOf("en-US");else->listOf("ko","en-US")}
        return lines.joinToString("\n"){line->
            val ink=Ink.builder()
            line.sortedBy{it.optLong("createdAt",0)}.forEach{obj->
                val stroke=Ink.Stroke.builder();InkGeometry.points(obj).forEachIndexed{i,p->stroke.addPoint(Ink.Point.create(p.x,p.y,if(p.time>0)p.time else i*8L))};ink.addStroke(stroke.build())
            }
            val context=RecognitionContext.builder().setWritingArea(WritingArea(width,height)).build()
            val candidates=languages.mapNotNull{tag->
                try{Tasks.await(recognizer(tag).recognize(ink.build(),context),25,TimeUnit.SECONDS).candidates.firstOrNull()?.text}catch(e:Exception){if(languages.size==1)throw e else null}}
            require(candidates.isNotEmpty()){"필기 인식 모델을 사용할 수 없습니다. 네트워크 연결 후 다시 시도해 주세요."}
            candidates.firstOrNull{text->language=="ko"&&text.any{it in '\uac00'..'\ud7a3'}}?:candidates.last()
        }
    }
    fun image(bitmap:Bitmap,language:String):String{
        val client=if(language=="ko")TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try{return Tasks.await(client.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS).text}finally{client.close()}
    }
    override fun close(){executor.execute{recognizers.values.forEach{it.close()};recognizers.clear()};executor.shutdown()}
}
