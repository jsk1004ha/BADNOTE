package com.inkforge.notesstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Base64
import android.webkit.*
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** The sole WebView in v4 is a one-time, local-only IndexedDB reader. It never edits old data. */
class LegacyMigration(private val activity:Activity,private val repository:NoteRepository,
                      private val status:(String)->Unit,private val complete:(Boolean)->Unit){
    private var web:WebView?=null
    private var stage:File?=null
    private var documentId=""
    private var pageCount=0
    private var asset:File?=null
    private var assetId=""
    private var assetOutput:FileOutputStream?=null
    private var assetDigest:MessageDigest?=null
    private var assetBytes=0L
    private var assetRequiresRaster=false
    private var textOutput:Writer?=null
    private var pagePart=false
    private var pendingAssets=mutableListOf<File>()
    private var created=mutableListOf<File>()
    private val finished=AtomicBoolean(false)
    internal var requestObserved:((String)->Unit)?=null
    @SuppressLint("SetJavaScriptEnabled")
    fun start():WebView{
        val view=WebView(activity);web=view
        view.settings.javaScriptEnabled=true;view.settings.domStorageEnabled=true;view.settings.allowFileAccess=false;view.settings.allowContentAccess=false
        view.addJavascriptInterface(this,"NativeMigration")
        view.webChromeClient=object:WebChromeClient(){
            override fun onConsoleMessage(message:ConsoleMessage):Boolean{
                val detail=message.message()
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR &&
                    (detail.startsWith("Uncaught ")||detail.contains("SyntaxError"))){
                    val line=message.lineNumber()
                    view.post{done("JavaScript 오류: $detail ($line)")}
                }
                return true
            }
        }
        view.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                val url=request.url.toString()
                val blobOrigin=if(request.url.scheme=="blob")android.net.Uri.parse(android.net.Uri.decode(url.substring(5))) else null
                val ownedBlob=isLocalBlobUrl(url)
                requestObserved?.invoke(if(ownedBlob)"owned-blob" else if(blobOrigin!=null)
                    "blob:${blobOrigin.scheme}:${blobOrigin.host}" else request.url.scheme.orEmpty())
                // WebView 44 routes FileReader's own Blob requests through this callback.
                // Its public Blob URL escapes the origin; decode it before checking authority.
                // Only browser-owned Blobs at our local origin bypass the empty response.
                if(ownedBlob)return null
                if(url=="https://appassets.androidplatform.net/assets/public/index.html")
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
        val file=repository.asset(id);val pending=repository.asset("$id.pending")
        val digest=MessageDigest.getInstance("SHA-256")
        require(!file.exists()&&pending.createNewFile()){ "자산 ID 충돌" }
        pendingAssets+=pending
        try{assetOutput=FileOutputStream(pending);asset=pending;assetId=id;assetDigest=digest;assetBytes=0L;assetRequiresRaster=false}
        catch(error:Throwable){pendingAssets.remove(pending);pending.delete();throw error}
        return "asset:$id"
    }
    @JavascriptInterface fun beginRasterAsset(mime:String):String=beginAsset(mime).also{assetRequiresRaster=true}
    @JavascriptInterface fun appendAsset(chunk:String):Boolean{
        require(chunk.length<=65536);val output=requireNotNull(assetOutput)
        val bytes=Base64.decode(chunk,Base64.NO_WRAP)
        output.write(bytes);requireNotNull(assetDigest).update(bytes);assetBytes+=bytes.size;return true
    }
    @JavascriptInterface fun endAsset():Boolean{
        val output=requireNotNull(assetOutput);val pending=requireNotNull(asset);val id=assetId
        try{
            output.fd.sync();output.close();assetOutput=null
            repository.finalizePendingAsset(id,assetBytes,requireNotNull(assetDigest).digest()){pending->
                require(assetBytes>0L){"원본 자산이 비어 있습니다."}
                if(assetRequiresRaster||id.substringAfterLast('.') in setOf("png","jpg","jpeg","webp"))
                    NoteRepository.checkedImageSize(pending)
            }
            created+=repository.asset(id);pendingAssets.remove(pending)
            asset=null;assetId="";assetDigest=null;assetBytes=0L;assetRequiresRaster=false
            return true
        }catch(error:Throwable){runCatching{output.close()};assetOutput=null;throw error}
    }
    @JavascriptInterface fun beginText(page:Boolean):Boolean{
        require(textOutput==null);pagePart=page
        textOutput=File(requireNotNull(stage),if(page)"${pageCount}.json"else"metadata.json").bufferedWriter();return true
    }
    @JavascriptInterface fun appendText(chunk:String):Boolean{require(chunk.length<=65536);requireNotNull(textOutput).write(chunk);return true}
    @JavascriptInterface fun endText():Boolean{textOutput?.close();textOutput=null;if(pagePart)pageCount++;return true}
    @JavascriptInterface fun endDocument():Boolean{
        val directory=requireNotNull(stage)
        require(assetOutput==null&&pendingAssets.isEmpty()) { "완료되지 않은 자산이 있습니다." }
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
        cleanup(true);activity.runOnUiThread{status("${data.optString("title","노트")} 이전 완료")};return true
    }
    @JavascriptInterface fun setting(key:String,value:String):Boolean{
        require(key in listOf("folders","preferences","appSettings"));require(value.length<=1048576)
        repository.setting(key,value,true);return true
    }
    @JavascriptInterface fun done(error:String){
        if(!finished.compareAndSet(false,true))return
        cleanup(error.isEmpty())
        var failure=error
        if(failure.isEmpty())try{repository.setting("legacy-migration-complete","true",true)}
            catch(problem:Exception){failure=problem.message?:"이전 완료 상태를 저장하지 못했습니다."}
        activity.runOnUiThread{web?.removeJavascriptInterface("NativeMigration");web?.stopLoading();web?.destroy();web=null
            if(failure.isNotEmpty())status("기존 노트 이전을 완료하지 못했습니다: $failure")
            complete(failure.isEmpty())}
    }
    private fun cleanup(keepAssets:Boolean){
        try{assetOutput?.close();textOutput?.close()}catch(_:Exception){}
        assetOutput=null;textOutput=null;asset=null;assetId="";assetDigest=null;assetBytes=0L;assetRequiresRaster=false
        pendingAssets.forEach{it.delete()};pendingAssets.clear()
        val committed=created.isNotEmpty()&&documentId.isNotEmpty()&&
            runCatching{repository.document(documentId)!=null}.getOrDefault(true)
        if(!keepAssets&&!committed)created.forEach{it.delete()}
        created.clear();stage?.deleteRecursively();stage=null
    }
    fun close(){finished.set(true);web?.stopLoading();web?.removeJavascriptInterface("NativeMigration");web?.destroy();web=null;cleanup(false)}
    companion object{
        internal fun isLocalBlobUrl(url:String):Boolean=runCatching{
            if(!url.startsWith("blob:"))false else{
                val origin=android.net.Uri.parse(android.net.Uri.decode(url.substring(5)))
                // Android Uri reports invalid ports as -1; compare the raw authority instead.
                origin.scheme=="https"&&origin.encodedAuthority in
                    setOf("appassets.androidplatform.net","appassets.androidplatform.net:443")
            }
        }.getOrDefault(false)
    }
}
