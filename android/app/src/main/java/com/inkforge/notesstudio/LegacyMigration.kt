package com.inkforge.notesstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Base64
import android.webkit.*
import org.json.JSONObject
import java.io.*

/** The sole WebView in v4 is a one-time, local-only IndexedDB reader. It never edits old data. */
class LegacyMigration(private val activity:Activity,private val repository:NoteRepository,
                      private val status:(String)->Unit,private val complete:(Boolean)->Unit){
    private var web:WebView?=null
    private var stage:File?=null
    private var documentId=""
    private var pageCount=0
    private var asset:File?=null
    private var assetOutput:FileOutputStream?=null
    private var textOutput:Writer?=null
    private var pagePart=false
    private var created=mutableListOf<File>()
    @SuppressLint("SetJavaScriptEnabled")
    fun start():WebView{
        val view=WebView(activity);web=view
        view.settings.javaScriptEnabled=true;view.settings.domStorageEnabled=true;view.settings.allowFileAccess=false;view.settings.allowContentAccess=false
        view.addJavascriptInterface(this,"NativeMigration")
        view.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse{
                if(request.url.toString()=="https://appassets.androidplatform.net/assets/public/index.html")
                    return WebResourceResponse("text/html","UTF-8",activity.assets.open("migration.html"))
                return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
        }
        view.loadUrl("https://appassets.androidplatform.net/assets/public/index.html")
        return view
    }
    @JavascriptInterface fun beginDocument(id:String):Boolean{
        require(id.length<=200)
        if(repository.document(id)!=null)return false
        cleanup(false);documentId=id;pageCount=0;stage=File(repository.workDirectory,uid("migration")).apply{mkdirs()};created=mutableListOf()
        return true
    }
    @JavascriptInterface fun beginAsset(mime:String):String{
        require(assetOutput==null&&stage!=null)
        val id=uid("asset")+"."+NoteRepository.extension(mime)
        val file=repository.asset(id);asset=file;created+=file;assetOutput=FileOutputStream(file)
        return "asset:$id"
    }
    @JavascriptInterface fun appendAsset(chunk:String):Boolean{
        require(chunk.length<=65536);val output=requireNotNull(assetOutput)
        output.write(Base64.decode(chunk,Base64.NO_WRAP));return true
    }
    @JavascriptInterface fun endAsset():Boolean{assetOutput?.let{it.fd.sync();it.close()};assetOutput=null;asset=null;return true}
    @JavascriptInterface fun beginText(page:Boolean):Boolean{
        require(textOutput==null);pagePart=page
        textOutput=File(requireNotNull(stage),if(page)"${pageCount}.json"else"metadata.json").bufferedWriter();return true
    }
    @JavascriptInterface fun appendText(chunk:String):Boolean{require(chunk.length<=65536);requireNotNull(textOutput).write(chunk);return true}
    @JavascriptInterface fun endText():Boolean{textOutput?.close();textOutput=null;if(pagePart)pageCount++;return true}
    @JavascriptInterface fun endDocument():Boolean{
        val directory=requireNotNull(stage)
        val data=JSONObject(File(directory,"metadata.json").readText()).apply{remove("pages");put("id",documentId)}
        require(pageCount>0)
        repository.executor.submit{
            repository.transaction{
                val pageMap = mutableMapOf<String,String>()
                val ids = (0 until pageCount).map { index ->
                    val old = JSONObject(File(directory,"$index.json").readText()).optString("id")
                    uid("page").also { replacement ->
                        if(old.isNotBlank()) {
                            require(old !in pageMap) { "중복된 페이지 ID입니다." }
                            pageMap[old] = replacement
                        }
                    }
                }
                rewritePageReferences(data,pageMap)
                val doc=DocumentInfo(documentId,data);repository.putDocument(doc)
                repeat(pageCount){index->
                    val pageData=JSONObject(File(directory,"$index.json").readText())
                    rewritePageReferences(pageData,pageMap)
                    pageData.put("id",ids[index])
                    repository.putPage(documentId,NotePage.from(pageData),index)
                }
                repository.putDocument(doc)
            }
        }.get()
        activity.runOnUiThread{status("${data.optString("title","노트")} 이전 완료")}
        cleanup(true);return true
    }
    @JavascriptInterface fun setting(key:String,value:String):Boolean{
        require(key in listOf("folders","preferences","appSettings"));require(value.length<=1048576)
        repository.setting(key,value,true);return true
    }
    @JavascriptInterface fun done(error:String){
        cleanup(error.isEmpty())
        if(error.isEmpty())repository.setting("legacy-migration-complete","true",true)
        activity.runOnUiThread{web?.removeJavascriptInterface("NativeMigration");web?.stopLoading();web?.destroy();web=null
            if(error.isNotEmpty())status("기존 노트 이전을 완료하지 못했습니다: $error")
            complete(error.isEmpty())}
    }
    private fun cleanup(keepAssets:Boolean){
        try{assetOutput?.close();textOutput?.close()}catch(_:Exception){}
        assetOutput=null;textOutput=null;asset=null
        if(!keepAssets)created.forEach{it.delete()};created.clear();stage?.deleteRecursively();stage=null
    }
    fun close(){web?.stopLoading();web?.removeJavascriptInterface("NativeMigration");web?.destroy();web=null;cleanup(false)}
}
