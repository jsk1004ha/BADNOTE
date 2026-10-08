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
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val extra = (8*root.resources.displayMetrics.density).roundToInt()
            fun insets(bars: Insets, cutout: Insets = Insets.NONE, keyboard: Insets = Insets.NONE) =
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),bars)
                    .setInsets(WindowInsetsCompat.Type.displayCutout(),cutout)
                    .setInsets(WindowInsetsCompat.Type.ime(),keyboard).build()
            val normal = insets(Insets.of(12,24,18,36),Insets.of(20,42,0,0))
            repeat(2) {
                val consumed = ViewCompat.dispatchApplyWindowInsets(root,normal)
                check(consumed.isConsumed)
                check(root.paddingLeft==20 && root.paddingTop==42+extra && root.paddingRight==18 && root.paddingBottom==36+extra)
            }
            ViewCompat.dispatchApplyWindowInsets(root,insets(Insets.of(12,24,18,36),Insets.of(20,42,0,0),Insets.of(0,0,0,260)))
            check(root.paddingBottom==260+extra)
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
        result.putString("safeArea","PASS: system bars, cutout, side bars, IME, repeat dispatch, 8dp breathing room, consumed child insets")
        result.putString("toolbarLayout","PASS: aligned icon centers; single-line pen name; full visible narrow dock controls; actual system-bar bounds")
    }
}
