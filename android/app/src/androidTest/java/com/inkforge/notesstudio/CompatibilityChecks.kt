package com.inkforge.notesstudio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

object CompatibilityChecks {
    fun run(test: Instrumentation, activity: MainActivity, result: Bundle) {
        fun <T> ui(block: () -> T): T {
            val value = AtomicReference<T>(); val error = AtomicReference<Throwable>()
            test.runOnMainSync { try { value.set(block()) } catch (e: Throwable) { error.set(e) } }
            error.get()?.let { throw it }; return value.get()
        }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis()+30000
            while (SystemClock.uptimeMillis()<end) { if(condition())return; SystemClock.sleep(40) }
            error("Timeout: $label")
        }
        ui {
            // An attached view's API 29 cutout comes from its actual root window.
            // Keep synthetic window values on an unattached root; real layout is checked below.
            val root = android.widget.FrameLayout(activity)
            RootSafeArea.install(root)
            val extra = (8*root.resources.displayMetrics.density).roundToInt()
            fun insets(bars: Insets, cutout: Insets = Insets.NONE, keyboard: Insets = Insets.NONE): WindowInsetsCompat {
                val builder = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),bars)
                    .setInsets(WindowInsetsCompat.Type.displayCutout(),cutout)
                    .setInsets(WindowInsetsCompat.Type.ime(),keyboard)
                // ViewCompat round-trips through platform WindowInsets on these APIs.
                if (android.os.Build.VERSION.SDK_INT < 30) builder.setSystemWindowInsets(bars)
                // API 29 reads cutouts from the platform object, not setInsets overrides.
                if (android.os.Build.VERSION.SDK_INT >= 29 && cutout != Insets.NONE)
                    builder.setDisplayCutout(DisplayCutoutCompat(
                        android.graphics.Rect(cutout.left,cutout.top,cutout.right,cutout.bottom), emptyList()))
                return builder.build()
            }
            val types=WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            val normal = insets(Insets.of(12,24,18,36),Insets.of(20,42,0,0))
            val normalSafe=normal.getInsets(types)
            check(normalSafe.left>=12 && normalSafe.top>=24 && normalSafe.right>=18 && normalSafe.bottom>=36)
            if(android.os.Build.VERSION.SDK_INT>=29)check(normalSafe.left==20 && normalSafe.top==42)
            repeat(2) {
                val consumed = ViewCompat.dispatchApplyWindowInsets(root,normal)
                check(consumed.isConsumed)
                check(root.paddingLeft==normalSafe.left && root.paddingTop==normalSafe.top+extra &&
                    root.paddingRight==normalSafe.right && root.paddingBottom==normalSafe.bottom+extra) {
                    "Inset dispatch changed fixture: api=${android.os.Build.VERSION.SDK_INT}, expected=$normalSafe+$extra, actual=${root.paddingLeft},${root.paddingTop},${root.paddingRight},${root.paddingBottom}"
                }
            }
            val withKeyboard=if(android.os.Build.VERSION.SDK_INT<30)
                WindowInsetsCompat.Builder().setSystemWindowInsets(Insets.of(12,24,18,260)).build()
            else insets(Insets.of(12,24,18,36),Insets.of(20,42,0,0),Insets.of(0,0,0,260))
            val keyboardSafe=withKeyboard.getInsets(types)
            check(keyboardSafe.bottom==260) { "Keyboard inset fixture unsupported: api=${android.os.Build.VERSION.SDK_INT}, safe=$keyboardSafe" }
            ViewCompat.dispatchApplyWindowInsets(root,withKeyboard)
            check(root.paddingBottom==keyboardSafe.bottom+extra) {
                "Keyboard inset lost during dispatch: api=${android.os.Build.VERSION.SDK_INT}, expected=${keyboardSafe.bottom+extra}, actual=${root.paddingBottom}"
            }
            result.putString("safeAreaSynthetic", "api=${android.os.Build.VERSION.SDK_INT};normal=$normalSafe;keyboard=$keyboardSafe")
            ViewCompat.dispatchApplyWindowInsets(root,insets(Insets.of(80,0,30,0)))
            check(root.paddingLeft==80 && root.paddingRight==30 && root.paddingTop==extra && root.paddingBottom==extra)
            ViewCompat.requestApplyInsets(root)
        }
        test.waitForIdleSync()
        fun checkLayout()=ui {
            val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val real=requireNotNull(ViewCompat.getRootWindowInsets(root))
            val safe=real.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val extra=(8*root.resources.displayMetrics.density).roundToInt()
            check(root.paddingTop==safe.top+extra && root.paddingBottom==safe.bottom+extra)
            for(i in 0 until root.childCount){val child=root.getChildAt(i);if(child.visibility==View.VISIBLE)
                check(child.top>=root.paddingTop && child.bottom<=root.height-root.paddingBottom){"Content overlaps actual Android system bars"}}
            fun find(label:String,parent:View=root):View?{fun walk(view:View):View?{if(view.contentDescription?.toString()==label)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)walk(view.getChildAt(i))?.let{return it};return null};return walk(parent)}
            val labels=listOf("라이브러리","펜","지우개","텍스트","더 보기")
            val navbar=activity.javaClass.getDeclaredField("editorNavbar").apply{isAccessible=true}.get(activity) as ViewGroup
            val centers=labels.map{label->val view=requireNotNull(find(label,navbar));val p=IntArray(2);view.getLocationOnScreen(p);p[1]+view.height/2}
            check(centers.max()-centers.min()<=2){"Toolbar icons must share a vertical center"}
            if(activity.resources.configuration.screenWidthDp<=840){
                val name=requireNotNull(find("만년필")) as ViewGroup
                val label=(0 until name.childCount).map{name.getChildAt(it)}.filterIsInstance<TextView>().single()
                check(label.lineCount==1 && label.paint.measureText(label.text.toString())<=label.width){"Pen name must fit on one line"}
                for(title in listOf("만년필","굵기 2.0","굵기 4.2","굵기 8.5","펜 설정","색상 추가")){
                    val control=requireNotNull(find(title));val visible=android.graphics.Rect()
                    check(control.getGlobalVisibleRect(visible)&&visible.width()==control.width&&visible.height()==control.height){"Dock control clipped: $title"}}
            }
        }
        val repository = activity.repository
        val assetBaseline=repository.assetDirectory.list()?.toSet().orEmpty()
        val createdAssets=mutableListOf<String>()
        try {
            val opaque=byteArrayOf(0,1,2,3,127,-1)
            val opaqueId=opaque.inputStream().use{repository.storeAsset(it,"bin")}
            createdAssets+=opaqueId
            check(repository.asset(opaqueId).readBytes().contentEquals(opaque)) { "Opaque asset bytes changed" }
            val picture=Bitmap.createBitmap(16,12,Bitmap.Config.ARGB_8888)
            val png=java.io.ByteArrayOutputStream().use{output->
                try{check(picture.compress(Bitmap.CompressFormat.PNG,100,output));output.toByteArray()}
                finally{picture.recycle()}
            }
            val pngId=png.inputStream().use{repository.storeAsset(it,"png") {pending->
                check(pending.name.endsWith(".pending")&&!repository.asset(pending.name.removeSuffix(".pending")).exists()) {
                    "Image validation ran after finalization"
                }
                NoteRepository.checkedImageSize(pending);Unit
            }}
            createdAssets+=pngId
            check(repository.asset(pngId).readBytes().contentEquals(png)) { "Image asset bytes changed" }
            check(runCatching{png.copyOf(png.size-12).inputStream().use{repository.storeAsset(it,"png") {pending->NoteRepository.checkedImageSize(pending)}}}.isFailure) {
                "Truncated PNG was finalized"
            }
            val corruptPng=png.copyOf().apply{this[0]=0}
            check(runCatching{corruptPng.inputStream().use{repository.storeAsset(it,"png") {pending->NoteRepository.checkedImageSize(pending)}}}.isFailure) {
                "Corrupt PNG was finalized"
            }
            check(runCatching{ByteArray(0).inputStream().use{repository.storeAsset(it,"png") {pending->NoteRepository.checkedImageSize(pending)}}}.isFailure) {
                "Empty image was finalized"
            }
            val rejected=uid("rejected")+".bin"
            check(runCatching{opaque.inputStream().use{repository.storeAssetAtId(it,rejected){throw IllegalArgumentException("reject")}}}.isFailure)
            check(!repository.asset(rejected).exists()&&!repository.asset("$rejected.pending").exists())
            val wrongDigest=uid("checksum")+".bin"
            repository.asset("$wrongDigest.pending").writeBytes(opaque)
            check(runCatching{repository.finalizePendingAsset(wrongDigest,opaque.size.toLong(),ByteArray(32))}.isFailure)
            check(!repository.asset(wrongDigest).exists()&&!repository.asset("$wrongDigest.pending").exists()) {
                "Checksum failure left an asset"
            }
            val missingBeforeRename=uid("rename-failure")+".bin"
            check(runCatching { opaque.inputStream().use { repository.storeAssetAtId(it,missingBeforeRename) { source ->
                check(source.delete()) // Force rename to fail after this writer reserves the final name.
            } } }.isFailure)
            check(!repository.asset(missingBeforeRename).exists()&&!repository.asset("$missingBeforeRename.pending").exists()) {
                "Failed rename left an empty final-name reservation"
            }
            val failing=object:java.io.InputStream(){var reads=0
                override fun read():Int=throw java.io.IOException("injected read failure")
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                    if(reads++==0){bytes[offset]=42;return 1}
                    throw java.io.IOException("injected read failure")
                }
            }
            val beforeFailure=repository.assetDirectory.list()?.toSet().orEmpty()
            check(runCatching{failing.use{repository.storeAsset(it,"bin")}}.isFailure)
            check(repository.assetDirectory.list()?.toSet().orEmpty()==beforeFailure) { "Failed read left an asset" }
            val collision=uid("collision")+".bin"
            val original=byteArrayOf(9,8,7)
            repository.asset(collision).writeBytes(original)
            try{check(runCatching{opaque.inputStream().use{repository.storeAssetAtId(it,collision)}}.isFailure)
                check(repository.asset(collision).readBytes().contentEquals(original)) { "ID collision replaced an asset" }}
            finally{repository.asset(collision).delete()}
            val interruptedReservation=uid("interrupted")+".bin"
            check(repository.asset(interruptedReservation).createNewFile())
            try {
                val documentsBefore=repository.documents().map { it.id }.toSet()
                check(runCatching{opaque.inputStream().use{repository.storeAssetAtId(it,interruptedReservation)}}.isFailure)
                check(repository.asset(interruptedReservation).length()==0L &&
                    !repository.asset("$interruptedReservation.pending").exists() &&
                    repository.documents().map { it.id }.toSet()==documentsBefore) {
                    "Interrupted reservation was adopted or attached to a document"
                }
            } finally { repository.asset(interruptedReservation).delete() }
            val finalizationRace=uid("race")+".bin"
            try{check(runCatching{opaque.inputStream().use{repository.storeAssetAtId(it,finalizationRace){
                repository.asset(finalizationRace).writeBytes(original)
            }}}.isFailure)
                check(repository.asset(finalizationRace).readBytes().contentEquals(original)) { "Finalization replaced an asset" }
                check(!repository.asset("$finalizationRace.pending").exists())}
            finally{repository.asset(finalizationRace).delete()}
            fun legacy(mime:String, bytes:ByteArray, invalidPage:Boolean=false):ByteArray {
                val source="data:$mime;base64,${android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)}"
                val page=json("id" to "legacy-page", "objects" to JSONArray().put(json("id" to "legacy-image", "type" to "image", "src" to source)))
                if(invalidPage)page.put("backgroundAssetId","missing-pdf")
                return json("title" to "Legacy asset", "pages" to JSONArray().put(page)).toString().toByteArray(Charsets.UTF_8)
            }
            fun acceptedLegacy(mime:String, bytes:ByteArray) {
                val imported=repository.importLegacy(legacy(mime,bytes).inputStream())
                val referenced=mutableSetOf<String>()
                try {
                    val importedPage=requireNotNull(repository.page(repository.pageIds(imported.id).single()))
                    NoteRepository.collectAssets(importedPage.json(),referenced)
                    val id=importedPage.objects.single().getString("src").removePrefix("asset:")
                    check(repository.asset(id).readBytes().contentEquals(bytes)) { "Legacy asset bytes changed: $mime" }
                    check(id.endsWith(".${NoteRepository.extension(mime)}")) { "Legacy asset MIME extension changed: $mime" }
                    check(referenced==setOf(id)) { "Legacy asset reference was not remapped" }
                } finally {
                    repository.pageIds(imported.id).forEach { pageId ->
                        repository.page(pageId)?.let { NoteRepository.collectAssets(it.json(),referenced) }
                    }
                    repository.deleteDocument(imported.id)
                    referenced.forEach { repository.asset(it).delete() }
                }
            }
            acceptedLegacy("image/png",png)
            acceptedLegacy("application/x-legacy-opaque",opaque)
            acceptedLegacy("audio/ogg",opaque) // Historical audio bytes remain importable for later playback/error handling.
            val protectedId=uid("legacy-protected")+".bin"
            repository.asset(protectedId).writeBytes(original)
            try {
                fun rejectedLegacy(bytes:ByteArray,label:String) {
                    val docsBefore=repository.documents().map { it.id }.toSet()
                    val assetsBefore=repository.assetDirectory.list()?.toSet().orEmpty()
                    check(runCatching { repository.importLegacy(bytes.inputStream()) }.isFailure) { "$label legacy asset was imported" }
                    check(repository.documents().map { it.id }.toSet()==docsBefore &&
                        repository.assetDirectory.list()?.toSet().orEmpty()==assetsBefore &&
                        repository.asset(protectedId).readBytes().contentEquals(original)) {
                        "$label legacy import left a document/asset or changed another asset"
                    }
                }
                rejectedLegacy(legacy("image/png",ByteArray(0)),"empty raster")
                rejectedLegacy(legacy("image/png",png.copyOf(png.size-12)),"truncated raster")
                rejectedLegacy(legacy("image/png",png.copyOf().apply { this[0]=0 }),"corrupt raster")
                rejectedLegacy(legacy("image/png",png,true),"post-finalization page failure")
                rejectedLegacy("""{"src":"data:image/png;base64,@@@","pages":[{"id":"p"}]}""".toByteArray(),"invalid base64")
                rejectedLegacy("""{"src":"data:image/png;base64,${android.util.Base64.encodeToString(png,android.util.Base64.NO_WRAP)}","pages":[]}""".toByteArray(),"no pages")
                rejectedLegacy("""{"src":"data:image/png;base64,${android.util.Base64.encodeToString(png,android.util.Base64.NO_WRAP)}","pages":[""".toByteArray(),"parser failure")
            } finally { repository.asset(protectedId).delete() }
            check(repository.assetDirectory.list()?.toSet().orEmpty()==assetBaseline+createdAssets) { "Asset validation left a pending file" }
        }finally{createdAssets.forEach{repository.asset(it).delete()}}
        val recordingDoc=repository.create("Invalid recording status", "root", "blank")
        val invalidRecordingId=uid("invalid-recording")+".m4a"
        repository.asset("$invalidRecordingId.pending").writeBytes(byteArrayOf(1,2,3))
        try {
            val enqueue=MainActivity::class.java.getDeclaredMethod("enqueueStoppedRecording",
                AudioController.PendingRecording::class.java,String::class.java).apply{isAccessible=true}
            val savingField=MainActivity::class.java.getDeclaredField("saving").apply{isAccessible=true}
            val savesField=MainActivity::class.java.getDeclaredField("saves").apply{isAccessible=true}
            val statusField=MainActivity::class.java.getDeclaredField("status").apply{isAccessible=true}
            val failedField=MainActivity::class.java.getDeclaredField("saveFailed").apply{isAccessible=true}
            ui { enqueue.invoke(activity,AudioController.PendingRecording(invalidRecordingId,"",0,1000,1000),recordingDoc.id) }
            await("invalid recording save queue") { ui {
                !savingField.getBoolean(activity) && (savesField.get(activity) as Collection<*>).isEmpty()
            } }
            test.waitForIdleSync()
            check(ui { (statusField.get(activity) as TextView).text.toString().startsWith("녹음 저장 실패:") &&
                !failedField.getBoolean(activity) }) { "Invalid AAC failure was replaced by generic saved status or blocked further edits" }
            check(!repository.asset(invalidRecordingId).exists()&&!repository.asset("$invalidRecordingId.pending").exists()) {
                "Invalid AAC recording left a final or pending file"
            }
            check(repository.document(recordingDoc.id)?.data?.array("audio")?.length()==0) {
                "Invalid AAC recording inserted a document reference"
            }
        } finally {
            repository.asset("$invalidRecordingId.pending").delete()
            repository.deleteDocument(recordingDoc.id)
        }
        val doc = repository.create("asset: allocation","root","blank")
        doc.data.put("settings",json("pageMode" to "single"))
        repository.putDocument(doc)
        val page = requireNotNull(repository.page(repository.pageIds(doc.id).first()))
        val source = Bitmap.createBitmap(100,40,Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 100) source.setPixel(x,y,if(x<50)Color.RED else Color.BLUE)
        val assetId = uid("rotation")+".png"
        repository.asset(assetId).outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG,100,it)) }; source.recycle()
        page.objects += json("id" to "image", "type" to "image", "src" to "asset:$assetId", "x" to 400,
            "y" to 300, "w" to 200, "h" to 80, "rotation" to Math.PI/2)
        page.objects += json("id" to "text", "type" to "text", "text" to "data: experiment results", "x" to 50, "y" to 700)
        repository.transaction { repository.putPage(doc.id,page,0) }
        fun colors(bitmap: Bitmap, point: (Float,Float) -> Pair<Int,Int> = {x,y->x.roundToInt() to y.roundToInt()}) {
            fun at(x: Float,y: Float): Int {val p=point(x,y);return bitmap.getPixel(p.first,p.second)}
            check(Color.red(at(500f,270f))>220 && Color.blue(at(500f,270f))<30) {"Rotation upper half"}
            check(Color.blue(at(500f,410f))>220 && Color.red(at(500f,410f))<30) {"Rotation lower half"}
            check(Color.red(at(420f,340f))>220 && Color.blue(at(420f,340f))>220) {"Old unrotated extent must be blank"}
        }
        val png = StreamingPdf.render(repository,page,1000)
        colors(png); png.recycle()
        val pdf = File(activity.cacheDir,"rotation-regression.pdf")
        StreamingPdf.write(repository,doc.id,pdf) {false}
        PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            renderer.openPage(0).use { p ->
                val bitmap = Bitmap.createBitmap(1000,1414,Bitmap.Config.ARGB_8888)
                p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); colors(bitmap); bitmap.recycle()
            }
        }
        ui { activity.openDocument(doc.id) }
        await("rotated image editor") {ui {activity.inkView?.currentPage?.id==page.id && activity.inkView!!.width>0}}
        checkLayout()
        await("rotated editor image decoded") { ui {
            val view = activity.inkView!!
            val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            try { colors(bitmap) {x,y->val p=view.screenPoint(x,y);p.x.roundToInt() to p.y.roundToInt()}; true }
            catch (_: IllegalStateException) { false }
            finally { bitmap.recycle() }
        } }
        ui {
            check(!activity.inkView!!.continuous)
            activity.javaClass.getDeclaredMethod("changePageMode",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,true)
        }
        await("document mode persisted") {repository.document(doc.id)?.continuous(false)==true}
        ui { activity.openDocument(doc.id) }
        await("document mode reopened") {ui {activity.inkView?.currentPage?.id==page.id && activity.inkView!!.continuous}}
        val archive = File(activity.cacheDir,"prefix-text-regression.ifnote")
        repository.export(doc.id,archive)
        val imported = archive.inputStream().use {repository.importArchive(it,"root")}
        check(imported.title==doc.title)
        check(repository.page(repository.pageIds(imported.id).first())!!.objects.last().optString("text")=="data: experiment results")
        result.putString("compatibility","PASS: literal-prefix archive; rotated editor image, PNG/PDF pixels; document mode persisted and reopened")
        result.putString("safeArea","PASS: supported system bars/cutout/IME, side bars, repeat dispatch, 8dp breathing room, consumed child insets")
        result.putString("toolbarLayout","PASS: aligned icon centers; single-line pen name; full visible narrow dock controls; actual system-bar bounds")
    }
}
