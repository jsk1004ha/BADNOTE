package com.inkforge.notesstudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity:Activity(){
    lateinit var repository:NoteRepository;private set
    var inkView:InkCanvasView?=null;private set
    private lateinit var root:FrameLayout
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private lateinit var blocker:LinearLayout
    private lateinit var blockerLabel:TextView
    private lateinit var fileExport:NativeFileExport
    private lateinit var audio:AudioController
    private val recognition=RecognitionService()
    private val network=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var migration:LegacyMigration?=null
    private var document:DocumentInfo?=null
    private var ids:List<String> = emptyList()
    private var metas:List<JSONObject> = emptyList()
    private var folder="root"
    private var libraryFilter="all"
    private var query=""
    private var settings=JSONObject()
    private var folders=JSONArray()
    private var dictionary=JSONObject()
    private var pageLabel:TextView?=null
    private var selectionBar:LinearLayout?=null
    private var busy=false
    private var clipboard=emptyList<JSONObject>()
    private var pendingInsert:Triple<InkCanvasView.Tool,Float,Float>?=null
    private var importMode="note"
    private var previousReadOnly=false
    private var ocrTask:Runnable?=null
    @Volatile private var recognitionBusy=false
    private val openTabs=linkedSetOf<String>()
    private data class Save(val block:()->Unit,val complete:()->Unit)
    private val saves=ArrayDeque<Save>()
    private var saving=false
    private var saveFailed=false
    private var libraryGeneration=0
    private var lastActivityIntentHandled=false
    private val ui by lazy { ClassicUi(this,::t) }
    private var modal:FrameLayout?=null
    private var editorSurface:FrameLayout?=null
    private var editorNavbar:LinearLayout?=null
    private var activeDock:LinearLayout?=null
    private var undoPill:LinearLayout?=null
    private var zoomLabel:TextView?=null
    private var pageSidebar:LinearLayout?=null
    private var sidebarTab="pages"
    private var searchDrawer:LinearLayout?=null
    private var globalSearchOpen=false
    private var selectionMode=false
    private val selectedDocuments=linkedSetOf<String>()
    private val thumbnailCache=object:android.util.LruCache<String,android.graphics.Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount}
    private val thumbnailPending=mutableSetOf<String>()
    private val classicTemplates=listOf("blank" to "무지","lined" to "줄 노트","grid" to "격자","dotted" to "도트","cornell" to "코넬","planner" to "플래너")

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){navigateBack()}
        repository=NoteRepository(this)
        audio=AudioController(this,repository)
        makeRoot()
        fileExport=NativeFileExport(this,repository,::message){value->setBusy(value,"내보낼 파일을 준비하고 있습니다.")}
        fileExport.restore(savedInstanceState?.getString("pendingExport"))
        loadSettings()
        if(repository.setting("legacy-migration-complete")!="true")startMigration()
        else{
            val restore=savedInstanceState?.getString("documentId")?:repository.setting("last-open-document")
            showLibrary()
            if(restore.isNotBlank())openDocument(restore)
            handleIncoming(intent)
        }
    }
    private fun makeRoot(){
        root=FrameLayout(this).apply{setBackgroundColor(0xff181818.toInt())}
        root.setOnApplyWindowInsetsListener{view,insets->
            if(Build.VERSION.SDK_INT>=30){val cutout=insets.getInsets(WindowInsets.Type.displayCutout());val ime=insets.getInsets(WindowInsets.Type.ime());view.setPadding(cutout.left,cutout.top,cutout.right,ime.bottom)}
            insets
        }
        body=column();root.addView(body,FrameLayout.LayoutParams(-1,-1))
        status=text("bad note · 4.0",12,0xff607185.toInt()).apply{setPadding(dp(16),dp(5),dp(16),dp(5))}
        blocker=column().apply{gravity=Gravity.CENTER;setBackgroundColor(0xeef5f7fa.toInt());isClickable=true;visibility=View.GONE}
        blockerLabel=text("처리 중…",18);blocker.addView(ProgressBar(this));blocker.addView(blockerLabel)
        blocker.addView(button("취소"){}.apply{setOnClickListener{if(::fileExport.isInitialized)fileExport.cancel()}})
        root.addView(blocker,FrameLayout.LayoutParams(-1,-1));setContentView(root)
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).roundToInt()
    private fun dp(value:Float)=(value*resources.displayMetrics.density).roundToInt()
    private fun t(value:String)=dictionary.optString(value,value)
    private fun text(value:String,size:Int=16,color:Int=0xff172b42.toInt())=TextView(this).apply{text=t(value);textSize=size.toFloat();setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun button(label:String,action:()->Unit)=Button(this).apply{
        text=t(label);contentDescription=t(label);isAllCaps=false;minHeight=dp(44);minimumHeight=dp(44);setTextColor(0xff24486a.toInt());textSize=14f
        minWidth=dp(44);minimumWidth=dp(44);stateListAnimator=null;elevation=0f;setPadding(dp(12),0,dp(12),0)
        val states=android.graphics.drawable.StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_selected),rounded(0xffd9eaff.toInt(),10))
            addState(intArrayOf(),rounded(0xffedf2f7.toInt(),10))
        }
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x242468a1),states,null)
        layoutParams=LinearLayout.LayoutParams(-2,dp(44)).apply{setMargins(dp(3),dp(3),dp(3),dp(3))}
        setOnClickListener{if(!busy)try{action()}catch(e:Exception){message(e.message?:"작업 실패")}}
    }
    private fun horizontal(content:LinearLayout)=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(content,ViewGroup.LayoutParams(-2,-2))}
    private fun rounded(color:Int,radius:Int=14)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun message(value:String){if(Looper.myLooper()!=Looper.getMainLooper()){runOnUiThread{message(value)};return};status.text=t(value)
        val toast=ui.text(value,13f,Color.WHITE).apply{setPadding(dp(18),dp(12),dp(18),dp(12));background=ui.rounded(0xf218191d.toInt(),13f);elevation=dp(10).toFloat()}
        root.addView(toast,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(24);leftMargin=dp(16);rightMargin=dp(16)})
        handler.postDelayed({root.removeView(toast)},2400)
    }
    private fun setBusy(value:Boolean,label:String="처리 중…"){
        busy=value;blockerLabel.text=t(label);blocker.visibility=if(value)View.VISIBLE else View.GONE
        if(value){previousReadOnly=inkView?.readOnly?:false;inkView?.finishContact();inkView?.readOnly=true}else inkView?.readOnly=previousReadOnly||saveFailed
    }
    private fun loadSettings(){
        settings=JSONObject(repository.setting("preferences",repository.setting("appSettings","{}")))
        val storedFolders=repository.setting("folders")
        folders=if(storedFolders.isBlank())JSONArray(listOf(json("id" to "root","title" to "문서","color" to "#1b68a6"),json("id" to "study","title" to "학교·공부","color" to "#2b8bc5"),json("id" to "work","title" to "프로젝트","color" to "#6d63c7")))else JSONArray(storedFolders)
        if(storedFolders.isBlank())repository.setting("folders",folders.toString(),true)
        loadDictionary()
    }
    private fun loadDictionary(){
        dictionary=try{assets.open("locales/${settings.optString("language","ko")}.json").bufferedReader().use{JSONObject(it.readText())}}catch(_:Exception){JSONObject()}
    }
    private fun saveSettings(){val copy=settings.toString();enqueueSave({repository.setting("preferences",copy,true)})}
    private fun startMigration(){
        setBusy(true,"기존 노트를 이전하고 있습니다. 원본 데이터는 유지됩니다.")
        migration=LegacyMigration(this,repository,{blockerLabel.text=it}){success->
            migration=null;setBusy(false);loadSettings()
            if(success){showLibrary();handleIncoming(intent)}else AlertDialog.Builder(this).setTitle("기존 노트 이전")
                .setMessage("원본은 그대로 보관되어 있습니다. 이전을 다시 시도하거나 파일 백업을 가져올 수 있습니다.")
                .setPositiveButton("다시 시도"){_,_->startMigration()}.setNegativeButton("라이브러리 열기"){_,_->showLibrary()}.setCancelable(false).show()
        }
        val web=requireNotNull(migration).start();root.addView(web,0,FrameLayout.LayoutParams(1,1))
    }
    private fun enqueueSave(block:()->Unit,complete:()->Unit={}){saves.addLast(Save(block,complete));drainSaves()}
    private fun drainSaves(){
        if(saving||saveFailed||saves.isEmpty())return
        saving=true;status.text=t("저장 중…")
        val work=saves.first()
        repository.executor.execute{
            try{work.block();runOnUiThread{saving=false;saves.removeFirst();work.complete();if(saves.isEmpty())status.text=t("저장됨");drainSaves()}}
            catch(e:Exception){runOnUiThread{saving=false;saveFailed=true;inkView?.readOnly=true;status.text="저장 실패: ${e.message}"
                AlertDialog.Builder(this).setTitle("저장하지 못했습니다.").setMessage("화면의 필기는 유지됩니다. 저장 공간을 확인한 뒤 다시 시도해 주세요.\n${e.message}")
                    .setPositiveButton("다시 저장"){_,_->saveFailed=false;inkView?.readOnly=false;drainSaves()}.setCancelable(false).show()}}
        }
    }
    private fun afterSaved(action:()->Unit){if(saves.isEmpty()&&!saving&&!saveFailed)action()else enqueueSave({},action)}
    private fun <T> work(label:String,operation:()->T,complete:(T)->Unit){
        setBusy(true,label)
        afterSaved{repository.executor.execute{
            try{val result=operation();runOnUiThread{setBusy(false);if(!isFinishing&&!isDestroyed)complete(result)}}
            catch(e:Exception){runOnUiThread{setBusy(false);message(e.message?:"작업 실패")}}
        }}
    }
    private fun clearScreen(){
        closeSheet();editorSurface=null;editorNavbar=null;activeDock=null;undoPill=null;pageSidebar=null;searchDrawer=null;zoomLabel=null;pageLabel=null;selectionBar=null
        ocrTask?.let{handler.removeCallbacks(it)};inkView?.finishContact();inkView?.close();inkView=null;body.removeAllViews()
        (status.parent as? ViewGroup)?.removeView(status)
    }
    private fun titleRow(title:String,back:(()->Unit)?=null):LinearLayout{
        val header=row().apply{setPadding(dp(12),dp(8),dp(12),dp(8))}
        if(back!=null)header.addView(button("‹",back))
        header.addView(text(title,23).apply{setTypeface(typeface,Typeface.BOLD)},LinearLayout.LayoutParams(0,dp(48),1f))
        return header
    }

    fun showLibrary(){
        if(audio.recording)stopRecording()
        clearScreen();document=null;ids=emptyList();metas=emptyList()
        enqueueSave({repository.setting("last-open-document","",true)})
        body.setBackgroundColor(ui.color("#181818"))
        val narrow=resources.configuration.screenWidthDp<=840
        val shell=ui.row();body.addView(shell,LinearLayout.LayoutParams(-1,0,1f))
        fun filter(value:String){libraryFilter=value;selectionMode=false;selectedDocuments.clear();showLibrary()}
        if(!narrow){
            val rail=ui.col().apply{setPadding(dp(10),dp(14),dp(10),dp(14));gravity=Gravity.CENTER_HORIZONTAL;setBackgroundColor(ui.color("#292929"))}
            rail.addView(ui.iconButton("notebook","문서",Color.WHITE,size=32){folder="root";filter("all")})
            rail.addView(ui.space(40))
            listOf("all" to ("folder" to "모든 문서"),"favorite" to ("star" to "즐겨찾기"),"shared" to ("users" to "공유됨"),"templates" to ("store" to "템플릿"),"trash" to ("trash" to "휴지통")).forEach{(key,item)->
                if(key=="trash"){rail.addView(ui.line(ui.color("#14ffffff")),LinearLayout.LayoutParams(dp(40),dp(1)).apply{topMargin=dp(2);bottomMargin=dp(14)})}
                rail.addView(ui.iconButton(item.first,item.second,ui.color("#f2f2f2"),selected=libraryFilter==key,selectedFill=ui.color("#174784"),selectedTint=ui.color("#67c5ff")){filter(key)},LinearLayout.LayoutParams(dp(44),dp(44)).apply{bottomMargin=dp(12)})
            }
            shell.addView(rail,LinearLayout.LayoutParams(dp(78),-1))
        }
        val pad=if(narrow)14 else (resources.configuration.screenWidthDp*.03f).roundToInt().coerceIn(18,38)
        val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false}
        val main=ui.col().apply{setPadding(dp(pad),dp(if(narrow)10 else 14),dp(pad),dp(if(narrow)92 else 72))}
        scroll.addView(main);shell.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
        val activeFolder=folders.objects().firstOrNull{it.optString("id")==folder}
        val title=if(libraryFilter=="all"&&folder!="root")activeFolder?.optString("title")?:"문서" else mapOf("all" to "문서","favorite" to "즐겨찾기","shared" to "공유됨","templates" to "템플릿","trash" to "휴지통")[libraryFilter]?:"문서"
        val top=ui.row();top.addView(ui.text(title,20f,ui.color("#f3f3f4"),true))
        val count=ui.text("0개",12f,ui.color("#888888"));top.addView(count,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(10)})
        top.addView(ui.iconButton("search","노트 검색",Color.WHITE){globalSearchOpen=!globalSearchOpen;showLibrary()})
        top.addView(ui.iconButton("settings","설정",Color.WHITE){showSettings()},LinearLayout.LayoutParams(dp(44),dp(44)).apply{leftMargin=dp(6)})
        main.addView(top,LinearLayout.LayoutParams(-1,dp(if(narrow)48 else 54)));main.addView(ui.line(ui.color("#11ffffff")))
        val search=ui.field(query,"제목, 손글씨 OCR, 텍스트, 수식 검색",50).apply{setTextColor(Color.WHITE);background=ui.rounded(ui.color("#262626"),14f,ui.color("#14ffffff"));visibility=if(globalSearchOpen||query.isNotBlank())View.VISIBLE else View.GONE}
        main.addView(search,LinearLayout.LayoutParams(-1,dp(50)).apply{if(search.visibility==View.VISIBLE)topMargin=dp(12)})
        val commands=ui.row();val crumbs=ui.row()
        crumbs.addView(ui.text("문서",15f,ui.color("#f0f0f0"),true).apply{setOnClickListener{folder="root";filter("all")}})
        if(folder!="root"){
            val path=mutableListOf<JSONObject>();var node=activeFolder;val visited=mutableSetOf<String>()
            while(node!=null&&node.optString("id")!="root"&&visited.add(node.optString("id"))){path.add(0,node);val parent=node.optString("parentId","root");node=folders.objects().firstOrNull{it.optString("id")==parent}}
            path.forEach{item->crumbs.addView(ui.text("/",15f,ui.color("#757b86"),true).apply{setPadding(dp(9),0,dp(9),0)});crumbs.addView(ui.text(item.optString("title"),15f,Color.WHITE,true).apply{setOnClickListener{folder=item.getString("id");filter("all")}})}
        }
        commands.addView(horizontal(crumbs),LinearLayout.LayoutParams(0,-2,1f))
        val create=ui.button(if(narrow)"" else "신규",ui.accent,Color.WHITE,40,19f,"plus"){newNote()}.apply{setPadding(dp(12),0,dp(12),0);elevation=dp(4).toFloat()}
        if(!narrow){create.addView(ui.icon("chevron-down",Color.WHITE,18),LinearLayout.LayoutParams(dp(18),dp(18)).apply{leftMargin=dp(10)})}
        commands.addView(create);commands.addView(ui.divider(ui.color("#14ffffff"),28))
        commands.addView(ui.iconButton("folderPlus","폴더 생성",Color.WHITE){newFolder()})
        if(folder!="root"&&activeFolder!=null)commands.addView(ui.iconButton("trash","폴더 삭제",ui.color("#ff8c8c")){folderMenu(activeFolder)})
        if(!narrow)commands.addView(ui.button(if(settings.optString("librarySort")=="title")"이름" else "날짜",ui.color("#222222"),Color.WHITE,38){
            choose("정렬",listOf("수정 날짜","이름")){i->settings.put("librarySort",if(i==0)"date" else "title");saveSettings();showLibrary()}
        }.apply{addView(ui.icon("chevron-down",Color.WHITE,15),LinearLayout.LayoutParams(dp(15),dp(15)).apply{leftMargin=dp(12)});background=ui.rounded(ui.color("#222222"),12f,ui.color("#12ffffff"))},LinearLayout.LayoutParams(-2,dp(38)).apply{leftMargin=dp(8);rightMargin=dp(8)})
        commands.addView(ui.iconButton(if(settings.optBoolean("listMode"))"list" else "grid","보기 방식 전환",Color.WHITE){settings.put("listMode",!settings.optBoolean("listMode"));saveSettings();showLibrary()})
        if(!narrow)commands.addView(ui.iconButton("check-circle","문서 선택",if(selectionMode)ui.accent else Color.WHITE){selectionMode=!selectionMode;selectedDocuments.clear();showLibrary()})
        main.addView(commands,LinearLayout.LayoutParams(-1,dp(if(narrow)62 else 72)))
        val banner=ui.row().apply{setPadding(dp(16),dp(12),dp(16),dp(12));background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(ui.color("#1e1e1e"),ui.color("#181d21"))).apply{cornerRadius=dp(12).toFloat();setStroke(dp(1),ui.color("#13ffffff"))}}
        banner.addView(ui.icon("sparkles",ui.color("#d8eaff"),27).apply{setPadding(dp(7.5f),dp(7.5f),dp(7.5f),dp(7.5f))},LinearLayout.LayoutParams(dp(42),dp(42)))
        val copy=ui.col();copy.addView(ui.text("기기 안에서 빠르고 안전하게",14f,ui.color("#f3f3f4"),true));copy.addView(ui.text("필기, 손글씨 OCR, 수식 계산, 페이지 검색을 오프라인으로 사용할 수 있습니다.",12f,ui.color("#9a9a9e")).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        banner.addView(copy,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(12)})
        if(!narrow)banner.addView(ui.button("사용법",ui.color("#153041"),ui.color("#9ed5ff"),34,17f){gestureGuide()})
        main.addView(banner,LinearLayout.LayoutParams(-1,dp(76)).apply{topMargin=dp(2);bottomMargin=dp(if(narrow)16 else 22)})
        val folderStrip=ui.row();main.addView(horizontal(folderStrip),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(18)})
        val list=ui.col();main.addView(list)
        fun render(){
            val generation=++libraryGeneration
            repository.executor.execute{val docs=repository.documents();runOnUiThread{
                if(generation!=libraryGeneration||document!=null)return@runOnUiThread
                list.removeAllViews();folderStrip.removeAllViews()
                val children=if(libraryFilter=="all"&&query.isBlank())folders.objects().filter{it.optString("id")!="root"&&it.optString("parentId","root")==folder}else emptyList()
                fun folderChip(item:JSONObject?){
                    val tint=runCatching{ui.color(item?.optString("color","#67c8ff")?:"#75c9ff")}.getOrDefault(ui.accent)
                    val chip=ui.row().apply{setPadding(dp(10),dp(9),dp(10),dp(9));background=ui.rounded(ui.color("#222222"),13f,ui.color("#13ffffff"));setOnClickListener{if(item==null)newFolder()else{folder=item.getString("id");showLibrary()}};setOnLongClickListener{if(item!=null)folderMenu(item);true}}
                    val glyph=ui.iconButton(if(item==null)"folderPlus" else "folder",item?.optString("title")?:"새 폴더",tint,42,42,24){chip.performClick()}.apply{background=ui.rounded((tint and 0xffffff) or 0x47000000,10f)}
                    chip.addView(glyph);val labels=ui.col();labels.addView(ui.text(item?.optString("title")?:"새 폴더",13f,if(item==null)ui.color("#d7eaff")else Color.WHITE,true))
                    labels.addView(ui.text(if(item==null)if(folder=="root")"노트 묶음 만들기"else"이 폴더 안에 만들기"else"${docs.count{!it.trashed&&it.folder==item.optString("id")}}개 노트",11f,ui.color("#888888")),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
                    chip.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(9)});chip.addView(ui.icon(if(item==null)"plus" else "chevron-right",ui.color("#8c8c8f"),18))
                    folderStrip.addView(chip,LinearLayout.LayoutParams(dp(if(narrow)174 else 190),dp(if(narrow)61 else 68)).apply{rightMargin=dp(12)})
                }
                if(libraryFilter=="all"&&query.isBlank()){children.forEach{folderChip(it)};folderChip(null)}
                if(libraryFilter=="templates"){count.text="${classicTemplates.size}개";list.addView(templateGrid("grid"){newNote(it)});return@runOnUiThread}
                val visible=docs.filter{doc->
                    val matches=doc.title.contains(query,true)||doc.data.array("tags").toString().contains(query,true)
                    matches&&when(libraryFilter){"trash"->doc.trashed;"favorite"->!doc.trashed&&doc.favorite;"shared"->!doc.trashed&&doc.data.optBoolean("shared");else->!doc.trashed&&(query.isNotBlank()||doc.folder==folder)}
                }.let{if(settings.optString("librarySort")=="title")it.sortedBy{d->d.title}else it}
                count.text="${visible.size}개"
                if(visible.isEmpty()&&children.isEmpty()){
                    val empty=ui.col().apply{gravity=Gravity.CENTER;setPadding(0,dp(70),0,dp(60))};empty.addView(ui.icon("notebook-plus",ui.color("#666666"),56))
                    empty.addView(ui.text("노트를 만들어 보세요",20f,ui.color("#e6e6e6"),true),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(17);bottomMargin=dp(7)})
                    empty.addView(ui.text("+ 신규 버튼으로 템플릿을 고르고 바로 필기를 시작할 수 있습니다.",13f,ui.color("#858589")).apply{gravity=Gravity.CENTER})
                    empty.addView(ui.button("새 노트",ui.accent,Color.WHITE,icon="plus"){newNote()},LinearLayout.LayoutParams(-2,dp(42)).apply{topMargin=dp(18)})
                    list.addView(empty,LinearLayout.LayoutParams(-1,-2))
                }
                val available=resources.configuration.screenWidthDp-(if(narrow)0 else 78)-pad*2
                val gap=if(narrow)14 else (resources.configuration.screenWidthDp*.032f).roundToInt().coerceIn(18,44)
                val columns=if(settings.optBoolean("listMode"))1 else if(narrow)2 else ((available+gap)/(164+gap)).coerceAtLeast(1)
                val cardWidth=(available-gap*(columns-1))/columns
                visible.chunked(columns).forEach{group->val line=ui.row().apply{gravity=Gravity.TOP}
                    group.forEachIndexed{n,doc->line.addView(documentCard(doc,cardWidth,settings.optBoolean("listMode")),LinearLayout.LayoutParams(dp(cardWidth),-2).apply{if(n>0)leftMargin=dp(gap)})}
                    list.addView(line,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(if(settings.optBoolean("listMode"))8 else if(narrow)25 else 34)})
                }
                if(selectionMode&&selectedDocuments.isNotEmpty())list.addView(ui.button("선택한 문서를 휴지통으로 이동",ui.color("#4a2020"),Color.WHITE){
                    confirm("휴지통으로 이동","${selectedDocuments.size}개 문서를 이동할까요?"){val selected=selectedDocuments.toSet();work("문서를 이동하는 중…",{repository.documents().filter{it.id in selected}.forEach{repository.putDocument(DocumentInfo(it.id,it.data.put("trashed",true)))}}){selectedDocuments.clear();selectionMode=false;showLibrary()}}
                })
            }}
        }
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s?.toString()?:"";render()};override fun afterTextChanged(s:Editable?){}})
        if(narrow){val mobile=ui.row().apply{setBackgroundColor(ui.color("#1d1d1e"));setPadding(dp(8),dp(4),dp(8),0)}
            listOf(Triple("folder","문서","all"),Triple("star","즐겨찾기","favorite"),Triple("plus-circle","신규","new"),Triple("store","템플릿","templates"),Triple("settings","설정","settings")).forEach{(icon,label,key)->val item=ui.col().apply{gravity=Gravity.CENTER;setOnClickListener{when(key){"new"->newNote();"settings"->showSettings();else->filter(key)}}};val tint=if(key==libraryFilter)ui.color("#55b8f5")else ui.color("#8d8d91");item.addView(ui.icon(icon,tint,23));item.addView(ui.text(label,10f,tint),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(3)});mobile.addView(item,LinearLayout.LayoutParams(0,dp(64),1f))};body.addView(mobile)}
        render()
    }
    private fun documentCard(doc:DocumentInfo,width:Int,listMode:Boolean):View{
        val card=if(listMode)ui.row()else ui.col();card.contentDescription="${doc.title} 열기"
        card.setOnClickListener{if(selectionMode){if(!selectedDocuments.add(doc.id))selectedDocuments.remove(doc.id);showLibrary()}else openDocument(doc.id)}
        val cover=FrameLayout(this).apply{background=ui.rounded(Color.WHITE,12f);clipToOutline=true;elevation=dp(5).toFloat()}
        cover.addView(thumbnail(doc.id,null).apply{coverColor=runCatching{ui.color(doc.data.optString("coverColor","#2f7fb7"))}.getOrDefault(ui.blue)},FrameLayout.LayoutParams(-1,-1))
        val favorite=ui.iconButton(if(selectionMode)"check-circle"else"star",if(selectionMode)"문서 선택"else"즐겨찾기",if(if(selectionMode)doc.id in selectedDocuments else doc.favorite)ui.color("#ffbd23")else ui.color("#acacaf"),32,32,20){if(selectionMode)card.performClick()else mutateDocument(doc){put("favorite",!doc.favorite)}}.apply{background=ui.rounded(0xb8ffffff.toInt(),50f)}
        cover.addView(favorite,FrameLayout.LayoutParams(dp(32),dp(32),Gravity.TOP or Gravity.RIGHT).apply{topMargin=dp(7);rightMargin=dp(7)})
        card.addView(cover,LinearLayout.LayoutParams(dp(if(listMode)72 else width),dp(if(listMode)96 else (width/.72f).roundToInt())))
        val meta=ui.col();val title=ui.row();title.addView(ui.text(doc.title,if(listMode)15f else 14f,ui.color("#f2f2f2"),true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f));title.addView(ui.iconButton("chevron-down","문서 메뉴",ui.color("#aaaaaa"),24,24,17){documentMenu(doc)})
        meta.addView(title);val subtitle=ui.row();val date=ui.text(formatDocumentDate(doc.data),11f,ui.color("#777777"));subtitle.addView(date,LinearLayout.LayoutParams(0,-2,1f));val pages=ui.text("",11f,ui.color("#777777"));subtitle.addView(pages);meta.addView(subtitle,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        repository.executor.execute{val count=repository.pageIds(doc.id).size;runOnUiThread{pages.text="${count}페이지"}}
        card.addView(meta,if(listMode)LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(14)}else LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        return card
    }
    private fun formatDocumentDate(data:JSONObject):String{
        val value=data.opt("updatedAt")?:data.opt("createdAt")?:return ""
        val date=if(value is Number)java.util.Date(value.toLong())else runCatching{java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.US).parse(value.toString())}.getOrNull()
        return date?.let{java.text.SimpleDateFormat("yyyy. M. d.",java.util.Locale.getDefault()).format(it)}?:""
    }
    private fun thumbnail(documentId:String?,pageId:String?):NotePreview{
        val view=NotePreview(this);val key=pageId?:"doc:$documentId"
        view.loadBitmap={
            val bitmap=thumbnailCache.get(key)
            if(bitmap==null&&thumbnailPending.add(key))repository.executor.execute{
                val image=runCatching{val id=pageId?:repository.pageIds(requireNotNull(documentId)).firstOrNull();id?.let{repository.page(it)}?.let{StreamingPdf.render(repository,it,220)}}.getOrNull()
                runOnUiThread{thumbnailPending.remove(key);if(image!=null){thumbnailCache.put(key,image);view.invalidate()}}
            };bitmap
        };return view
    }
    private fun newNote(initialTemplate:String="grid"){
        var template=initialTemplate;val content=showSheet("새 문서","노트 템플릿 선택",680)
        content.addView(ui.text("노트 제목",12f,ui.color("#5d626a"),true),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
        val name=ui.field("","새 노트");content.addView(name)
        content.addView(templateGrid(template){template=it},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(18)})
        val pdf=ui.row().apply{setPadding(dp(16),dp(12),dp(16),dp(12));background=ui.rounded(ui.color("#f1f8ff"),15f,ui.color("#b8dcff"));setOnClickListener{closeSheet();pick("pdf")}}
        pdf.addView(ui.icon("page-plus",ui.color("#186dab"),24),LinearLayout.LayoutParams(dp(24),dp(32)).apply{rightMargin=dp(16)});val labels=ui.col();labels.addView(ui.text("PDF로 새 노트",14f,ui.color("#186dab"),true));labels.addView(ui.text("페이지와 검색 텍스트를 가져옵니다",11f,ui.color("#66809a")),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)});pdf.addView(labels)
        content.addView(pdf,LinearLayout.LayoutParams(-1,dp(64)).apply{topMargin=dp(16)})
        sheetActions(content,"만들기","plus") { val title=name.text.toString().ifBlank{"새 노트"};closeSheet();work("노트를 만드는 중…",{repository.create(title,folder,template)}){openDocument(it.id)} }
    }
    private fun newFolder(){prompt("새 폴더 이름",""){name->if(name.isNotBlank()){
        val existing=folders.objects().firstOrNull{it.optString("parentId","root")==folder&&it.optString("title").equals(name.trim(),true)}
        if(existing!=null){folder=existing.getString("id");showLibrary()}else{
            folders.put(json("id" to uid("folder"),"title" to name.trim(),"parentId" to folder,"color" to "#2468a1"));val copy=folders.toString();enqueueSave({repository.setting("folders",copy,true)}){showLibrary()}}
    }}}
    private fun folderMenu(item:JSONObject){choose(item.optString("title"),listOf("이름 변경","폴더 삭제")){i->
        if(i==0)prompt("폴더 이름",item.optString("title")){value->item.put("title",value);val copy=folders.toString();enqueueSave({repository.setting("folders",copy,true)}){showLibrary()}}
        else confirm("폴더 삭제","안의 노트와 하위 폴더는 상위 폴더로 이동합니다."){
            val id=item.getString("id");val parent=item.optString("parentId","root")
            folders=JSONArray(folders.objects().filter{it.optString("id")!=id}.map{it.apply{if(optString("parentId")==id)put("parentId",parent)}})
            val copy=folders.toString();work("폴더 정리 중…",{repository.transaction{repository.documents().filter{it.folder==id}.forEach{repository.putDocument(DocumentInfo(it.id,it.data.put("folderId",parent)))};repository.setting("folders",copy,true)}}){if(folder==id)folder=parent;showLibrary()}
        }
    }}
    private fun mutateDocument(doc:DocumentInfo,change:JSONObject.()->Unit){
        val data=doc.data.copyJson().apply(change)
        val changed=data.keys().asSequence().filter{data.opt(it)?.toString()!=doc.data.opt(it)?.toString()}.toList()
        if(document?.id==doc.id)document=DocumentInfo(doc.id,data)
        enqueueSave({val latest=requireNotNull(repository.document(doc.id));changed.forEach{latest.data.put(it,data.opt(it))};repository.putDocument(latest)}){
            if(document==null)showLibrary()}
    }
    private fun documentMenu(doc:DocumentInfo){
        val options=if(doc.trashed)listOf("복원","영구 삭제")else listOf("이름 변경","폴더로 이동","태그","복제","파일 내보내기","기기 공유","휴지통으로 이동")
        choose(doc.title,options){i->if(doc.trashed){if(i==0)mutateDocument(doc){put("trashed",false)}else confirm("영구 삭제",doc.title){work("삭제 중…",{repository.deleteDocument(doc.id)}){showLibrary()}}}
            else when(i){
                0->prompt("노트 이름",doc.title){value->mutateDocument(doc){put("title",value)}}
                1->{val choices=listOf(json("id" to "root","title" to "문서"))+folders.objects().filter{it.optString("id")!="root"};choose("폴더로 이동",choices.map{it.optString("title")}){n->mutateDocument(doc){put("folderId",choices[n].getString("id"))}}}
                2->prompt("태그 (쉼표로 구분)",doc.data.array("tags").let{a->(0 until a.length()).joinToString(", "){a.optString(it)}}){value->mutateDocument(doc){put("tags",JSONArray(value.split(',').map{it.trim()}.filter{it.isNotBlank()}))}}
                3->work("노트 복제 중…",{val file=File.createTempFile("duplicate-",".ifnote",repository.workDirectory);try{repository.export(doc.id,file);file.inputStream().use{repository.importArchive(it,doc.folder)}.also{it.data.put("title","${doc.title} 사본");repository.putDocument(it)}}finally{file.delete()}}){showLibrary()}
                4->exportMenu(doc,false)
                5->exportMenu(doc,true)
                6->mutateDocument(doc){put("trashed",true)}
            }
        }
    }
    fun openDocument(id:String,index:Int?=null){
        inkView?.finishContact()
        work("노트를 여는 중…",{
            val doc=requireNotNull(repository.document(id)){"노트를 찾을 수 없습니다."}
            val pageIds=repository.pageIds(id);require(pageIds.isNotEmpty()){"페이지가 없습니다."}
            Triple(doc,pageIds,pageIds.map{requireNotNull(repository.pageMeta(it))})
        }){(doc,pageIds,pageMetas)->
            document=doc;ids=pageIds;metas=pageMetas;openTabs+=id
            val remembered=doc.data.optString("lastPageId");val found=pageIds.indexOf(remembered)
            val pageIndex=(index?:if(found>=0)found else doc.data.optInt("lastPageIndex",0)).coerceIn(0,pageIds.lastIndex)
            showEditor(pageIndex);enqueueSave({repository.setting("last-open-document",id,true)})
        }
    }
    private fun showEditor(index:Int){
        val doc=document?:return;clearScreen();document=doc
        val narrow=resources.configuration.screenWidthDp<=840
        body.setBackgroundColor(ui.color("#f6f6f7"))
        if(!narrow){
            val tabs=ui.row().apply{setBackgroundColor(ui.color("#233e64"));gravity=Gravity.BOTTOM}
            tabs.addView(ui.iconButton("home","라이브러리",ui.color("#e9f2fb"),40,32,19){showLibrary()})
            val tabList=ui.row().apply{gravity=Gravity.BOTTOM}
            openTabs.toList().takeLast(6).forEach{id->
                val tab=ui.row().apply{setPadding(dp(11),0,dp(6),0);background=GradientDrawable().apply{setColor(if(id==doc.id)ui.blue else ui.color("#2c466a"));cornerRadii=floatArrayOf(dp(8).toFloat(),dp(8).toFloat(),dp(8).toFloat(),dp(8).toFloat(),0f,0f,0f,0f)};setOnClickListener{openDocument(id)}}
                tab.addView(ui.text(if(id==doc.id)doc.title else repository.document(id)?.title?:"노트",12f,if(id==doc.id)Color.WHITE else ui.color("#aaffffff")).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-1,1f))
                tab.addView(ui.iconButton("close","문서 탭 닫기",ui.color("#b3ffffff"),22,22,14){openTabs.remove(id);if(id==doc.id){openTabs.lastOrNull()?.let{openDocument(it)}?:showLibrary()}else showEditor(inkView?.currentIndex?:0)})
                tabList.addView(tab,LinearLayout.LayoutParams(dp(120+((if(id==doc.id)doc.title.length else 6)-6).coerceIn(0,20)*5),dp(29)).apply{rightMargin=dp(2)})
            }
            tabs.addView(horizontal(tabList),LinearLayout.LayoutParams(0,dp(29),1f));tabs.addView(ui.iconButton("plus","새 문서",Color.WHITE,40,32,19){newNote()});body.addView(tabs,LinearLayout.LayoutParams(-1,dp(34)))
        }
        val navbar=ui.row().apply{setPadding(dp(if(narrow)4 else 12),0,dp(if(narrow)4 else 12),0);setBackgroundColor(ui.blue)}
        editorNavbar=navbar;body.addView(navbar,LinearLayout.LayoutParams(-1,dp(if(narrow)58 else 62)))
        val surface=FrameLayout(this);editorSurface=surface;body.addView(surface,LinearLayout.LayoutParams(-1,0,1f))
        val canvas=InkCanvasView(this,repository);inkView=canvas
        applySettings(canvas)
        canvas.tool=runCatching{InkCanvasView.Tool.valueOf(settings.optString("activeTool","LASSO"))}.getOrDefault(InkCanvasView.Tool.LASSO)
        if(canvas.tool==InkCanvasView.Tool.HIGHLIGHTER)canvas.inkColor=settings.optString("highlighterColor","#f5df39")
        if(canvas.tool==InkCanvasView.Tool.TAPE)canvas.inkColor=settings.optString("tapeColor","#4c91dd")
        canvas.onPageChanged={position->updatePageLabel();rememberPosition(position);scheduleOcr()}
        canvas.onEdit={pageId,change,positions->
            val documentId=doc.id;enqueueSave({repository.commit(documentId,pageId,change,positions)})
            thumbnailCache.remove(pageId);thumbnailCache.remove("doc:$documentId");scheduleOcr();updateUndoPill()
        }
        canvas.onInsert={tool,x,y->insertObject(tool,x,y)}
        canvas.onSelection={selection->showSelection(selection)}
        canvas.onObjectTap={obj->objectMenu(obj)}
        canvas.onStatus=::message
        canvas.onAppendPage={addPage()}
        var previousTool=canvas.tool
        canvas.onToolChanged={
            if(previousTool!=canvas.tool){settings.put(when(previousTool){InkCanvasView.Tool.HIGHLIGHTER->"highlighterColor";InkCanvasView.Tool.TAPE->"tapeColor";else->"inkColor"},canvas.inkColor);canvas.inkColor=settings.optString(when(canvas.tool){InkCanvasView.Tool.HIGHLIGHTER->"highlighterColor";InkCanvasView.Tool.TAPE->"tapeColor";else->"inkColor"},when(canvas.tool){InkCanvasView.Tool.HIGHLIGHTER->"#f5df39";InkCanvasView.Tool.TAPE->"#4c91dd";else->"#111827"});previousTool=canvas.tool}
            renderEditorToolbar();renderActiveDock();settings.put("activeTool",canvas.tool.name);saveSettings()
        }
        canvas.onViewportChanged={updatePageLabel()}
        surface.addView(canvas,FrameLayout.LayoutParams(-1,-1))
        val pill=ui.row().apply{gravity=Gravity.CENTER;setPadding(dp(4),0,dp(4),0);background=ui.rounded(ui.color("#bf575758"),20f);elevation=dp(8).toFloat()};undoPill=pill
        surface.addView(pill,FrameLayout.LayoutParams(dp(if(narrow)102 else 146),dp(if(narrow)48 else 68),if(narrow)Gravity.BOTTOM or Gravity.LEFT else Gravity.TOP or Gravity.LEFT).apply{leftMargin=dp(if(narrow)8 else 13);if(narrow)bottomMargin=dp(12)else topMargin=dp(18)})
        fun hud():TextView=ui.text("",12f,Color.WHITE,true).apply{gravity=Gravity.CENTER;setPadding(dp(13),0,dp(13),0);background=ui.rounded(ui.color("#b326292e"),17f)}
        pageLabel=hud().apply{setOnClickListener{jumpPage()}};zoomLabel=hud().apply{minimumWidth=dp(62);setOnClickListener{canvas.resetZoom()}}
        surface.addView(pageLabel,FrameLayout.LayoutParams(-2,dp(34),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(14)})
        surface.addView(zoomLabel,FrameLayout.LayoutParams(-2,dp(34),Gravity.BOTTOM or Gravity.RIGHT).apply{bottomMargin=dp(14);rightMargin=dp(14)})
        selectionBar=ui.row().apply{setPadding(dp(5),dp(5),dp(5),dp(5));background=ui.rounded(ui.color("#f5181819"),15f);visibility=View.GONE;elevation=dp(12).toFloat()}
        surface.addView(selectionBar,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(62)})
        renderEditorToolbar();renderActiveDock();updateUndoPill()
        canvas.open(ids,metas,index);applyHudOpacity();updatePageLabel()
    }
    private fun renderEditorToolbar(){
        val bar=editorNavbar?:return;val view=inkView?:return;val narrow=resources.configuration.screenWidthDp<=840;bar.removeAllViews()
        val left=ui.row();val center=ui.row();val right=ui.row().apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL}
        val size=if(narrow)42 else 48
        fun icon(parent:LinearLayout,name:String,label:String,active:Boolean=false,action:()->Unit){parent.addView(ui.iconButton(name,label,Color.WHITE,size,size,if(narrow)23 else 25,active,ui.color("#d9e4ef"),if(name=="pen")ui.color("#1d677b")else ui.color("#162b44"),action),LinearLayout.LayoutParams(dp(size),dp(size)).apply{if(parent.childCount>0)leftMargin=dp(if(parent==center)5 else 4)})}
        icon(left,"home","라이브러리"){showLibrary()}
        if(!narrow){left.addView(ui.divider());icon(left,"sidebar","페이지 사이드바",pageSidebar!=null){togglePages()};icon(left,"sparkles","학습 도우미"){assistantMenu()}}
        fun tool(name:String,label:String,value:InkCanvasView.Tool){icon(center,name,label,view.tool==value){if(value==InkCanvasView.Tool.PEN&&view.tool==value)penSettingsSheet()else{view.tool=value;if(value==InkCanvasView.Tool.IMAGE)insertObject(value,100f,150f)}}}
        tool("lasso","올가미",InkCanvasView.Tool.LASSO);tool("pen","펜",InkCanvasView.Tool.PEN);tool("eraser","지우개",InkCanvasView.Tool.ERASER);tool("text","텍스트",InkCanvasView.Tool.TEXT)
        if(!narrow){tool("sticky","스티키 노트",InkCanvasView.Tool.STICKY);tool("image","이미지",InkCanvasView.Tool.IMAGE)}
        icon(center,"chevron-down","도구 더보기"){toolMenu()}
        if(!narrow)center.addView(ui.divider())
        icon(center,"ruler","자",view.ruler){view.ruler=!view.ruler;view.invalidate();renderEditorToolbar()}
        icon(center,"mic","오디오 녹음",audio.recording){toggleRecording();renderEditorToolbar()}
        icon(right,"page-plus","새 페이지"){addPage()};if(!narrow)icon(right,"share","공유와 내보내기"){exportMenu(document?:return@icon,true)}
        if(!narrow)icon(right,"ocr","손글씨 인식"){recognize(false,false)}
        icon(right,"more","더 보기"){editorMenu()}
        if(narrow){bar.addView(left);bar.addView(horizontal(center),LinearLayout.LayoutParams(0,-1,1f));bar.addView(right)}
        else {bar.addView(left,LinearLayout.LayoutParams(0,-1,1f));bar.addView(center);bar.addView(right,LinearLayout.LayoutParams(0,-1,1f))}
    }
    private fun updateUndoPill(){
        val pill=undoPill?:return;val view=inkView?:return;val narrow=resources.configuration.screenWidthDp<=840;pill.removeAllViews()
        val undo=ui.iconButton("undo","실행 취소",Color.WHITE,if(narrow)42 else 57,if(narrow)40 else 52){view.undo();updateUndoPill()}.apply{isEnabled=view.canUndo;alpha=if(isEnabled)1f else .32f}
        val redo=ui.iconButton("redo","다시 실행",Color.WHITE,if(narrow)42 else 57,if(narrow)40 else 52){view.redo();updateUndoPill()}.apply{isEnabled=view.canRedo;alpha=if(isEnabled)1f else .32f}
        pill.addView(undo);pill.addView(redo,LinearLayout.LayoutParams(dp(if(narrow)42 else 57),dp(if(narrow)40 else 52)).apply{leftMargin=dp(6)})
    }
    private fun renderActiveDock(){
        val surface=editorSurface?:return;val view=inkView?:return;activeDock?.let{surface.removeView(it)}
        val narrow=resources.configuration.screenWidthDp<=840
        val dock=ui.row().apply{gravity=Gravity.CENTER;clipChildren=false;clipToPadding=false};activeDock=dock
        val menu=ui.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(if(narrow)7 else 10),dp(6),dp(if(narrow)7 else 10),dp(6));background=ui.rounded(ui.color("#f7171717"),if(narrow)18f else 21f);elevation=0f}
        fun add(item:View,width:Int?=null,height:Int=45){menu.addView(item,LinearLayout.LayoutParams(if(width==null)-2 else dp(width),dp(height)).apply{if(menu.childCount>0)leftMargin=dp(7)})}
        fun divider(){add(ui.divider().apply{setPadding(0,0,0,0)},7,36);(menu.getChildAt(menu.childCount-1) as View).background=ui.rounded(Color.TRANSPARENT);val last=menu.getChildAt(menu.childCount-1);last.setBackgroundColor(0x21ffffff);last.scaleX=1f/7f}
        fun icon(name:String,label:String,selected:Boolean=false,action:()->Unit){add(ui.iconButton(name,label,ui.color("#f4f4f4"),45,45,25,selected,ui.color("#327c92"),Color.WHITE,action),45)}
        fun named(name:String,label:String,selected:Boolean=false,arrow:Boolean=false,action:()->Unit){
            val item=ui.row().apply{gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0);background=ui.rounded(if(selected)ui.color("#30ffffff")else ui.color("#12ffffff"),14f);setOnClickListener{action()};contentDescription=t(label)}
            item.addView(ui.icon(name,Color.WHITE,24));item.addView(ui.text(label,13f,Color.WHITE,true).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(7)})
            if(arrow)item.addView(ui.icon("chevron-down",Color.WHITE,18));add(item,if(narrow)82 else 98,44)
        }
        fun label(value:String){add(ui.text(value,12f,ui.color("#b5b5b5"),true).apply{gravity=Gravity.CENTER;maxLines=2},110)}
        fun width(value:Float){val item=FrameLayout(this).apply{background=ui.rounded(if(abs(value-currentWidth())<.2f)ui.color("#17ffffff")else Color.TRANSPARENT,13f);setOnClickListener{setCurrentWidth(value);saveSettings();renderActiveDock()};contentDescription="굵기 $value"}
            item.addView(View(this).apply{background=ui.rounded(Color.WHITE,99f)},FrameLayout.LayoutParams(dp(28),dp((value/2.2f).coerceIn(2f,13f)),Gravity.CENTER));add(item,45)}
        fun slider(){
            val holder=ui.row().apply{setPadding(dp(8),0,dp(8),0);background=ui.rounded(ui.color("#14ffffff"),12f)}
            val output=ui.text("${widthText(currentWidth())}px",12f,Color.WHITE,true)
            val max=when(view.tool){InkCanvasView.Tool.ERASER->180f;InkCanvasView.Tool.HIGHLIGHTER->120f;else->80f}
            val seek=SeekBar(this).apply{this.max=(max*2).toInt();progress=(currentWidth()*2).toInt();minimumWidth=0;setPadding(0,0,0,0);progressTintList=android.content.res.ColorStateList.valueOf(ui.color("#7dd3fc"));thumb=ui.rounded(Color.WHITE,50f).apply{setSize(dp(17),dp(17))};thumbOffset=0}
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){setCurrentWidth(max(.5f,p/2f));output.text="${widthText(currentWidth())}px"}};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){saveSettings()}})
            holder.addView(seek,LinearLayout.LayoutParams(0,dp(30),1f));holder.addView(output,LinearLayout.LayoutParams(dp(42),-2).apply{leftMargin=dp(7)});add(holder,if(narrow)108 else 126,39)
        }
        fun colors(values:List<String>,current:String,onColor:(String)->Unit){values.forEach{value->add(ui.swatch(value,current.equals(value,true)){onColor(value);if(view.tool!=InkCanvasView.Tool.STICKY)settings.put(when(view.tool){InkCanvasView.Tool.HIGHLIGHTER->"highlighterColor";InkCanvasView.Tool.TAPE->"tapeColor";else->"inkColor"},view.inkColor);saveSettings();renderActiveDock()},39,39)}}
        val palette=if(view.tool==InkCanvasView.Tool.HIGHLIGHTER)listOf("#f5df39","#80e2ff","#ff9fc2","#a8ef82","#b9a3ff")else listOf("#111827","#147bd1","#ffffff","#d93939","#2a9d66","#7a57c7")
        when(view.tool){
            InkCanvasView.Tool.PEN->{val brushes=mapOf("fountain" to "만년필","ballpoint" to "볼펜","gel" to "젤펜","brush" to "브러시","pencil" to "연필","fineliner" to "파인라이너","calligraphy" to "캘리그래피","marker" to "마커");named(if(view.brush in listOf("fountain","ballpoint","brush","pencil"))view.brush else "pen",brushes[view.brush]?:"펜",arrow=true){penMenu()};divider();listOf(2f,4.2f,8.5f).forEach(::width);slider();icon("settings","펜 설정"){penSettingsSheet()};divider();colors(palette,view.inkColor){view.inkColor=it;settings.put("inkColor",it);saveSettings()};add(ui.iconButton("plus","색상 추가",Color.WHITE,39,39,18){colorMenu()}.apply{background=GradientDrawable().apply{setColor(Color.TRANSPARENT);shape=GradientDrawable.OVAL;setStroke(dp(2),0xb3ffffff.toInt(),dp(4).toFloat(),dp(3).toFloat())}},39,39)}
            InkCanvasView.Tool.HIGHLIGHTER->{named("highlighter","형광펜"){};divider();listOf(12f,22f,34f).forEach(::width);slider();divider();colors(palette,view.inkColor){view.inkColor=it};add(ui.iconButton("plus","색상 추가",Color.WHITE,39,39,18){colorMenu()}.apply{background=GradientDrawable().apply{setColor(Color.TRANSPARENT);shape=GradientDrawable.OVAL;setStroke(dp(2),0xb3ffffff.toInt(),dp(4).toFloat(),dp(3).toFloat())}},39,39)}
            InkCanvasView.Tool.ERASER->{named("eraser","획",!view.preciseEraser&&!view.wholeEraser){view.preciseEraser=false;view.wholeEraser=false;renderActiveDock()};named("lasso","정밀",view.preciseEraser){view.preciseEraser=true;view.wholeEraser=false;renderActiveDock()};named("trash","전체",view.wholeEraser){view.wholeEraser=true;view.preciseEraser=false;renderActiveDock()};divider();listOf(12f,26f,48f).forEach(::width);slider();icon("trash","페이지 지우기"){clearPage()}}
            InkCanvasView.Tool.LASSO->{named("lasso","자유형 선택",true){};icon("check-circle","전체 선택"){view.selectAll()};icon("copy","붙여넣기"){pasteSelection()};divider();label("필기 · 텍스트 · 이미지")}
            InkCanvasView.Tool.SHAPE->{listOf("line","curve","arc","rectangle","rounded-rectangle","square","ellipse","circle","triangle","diamond","pentagon","hexagon","starshape","trapezoid","parallelogram","heartshape","cloudshape","speech","arrow","double-arrow").forEach{shape->icon(shape,shape,view.shape==shape){view.shape=shape;renderActiveDock()}};divider();listOf(2f,4f,8f).forEach(::width);slider();colors(palette.take(5),view.inkColor){view.inkColor=it}}
            InkCanvasView.Tool.TEXT->{named("text","텍스트 추가",true){insertObject(InkCanvasView.Tool.TEXT,100f,150f)};divider();label("페이지를 탭해 입력");icon("search","검색"){searchDocument()}}
            InkCanvasView.Tool.STICKY->{named("sticky","스티키 노트",true){};divider();colors(listOf("#ffe58d","#ffd2dc","#cfefff","#d8f5ce","#ddd2ff"),settings.optString("stickyColor","#ffe58d")){settings.put("stickyColor",it)};label("페이지를 탭해 추가")}
            InkCanvasView.Tool.TAPE->{named("tape","암기 테이프",true){};divider();colors(listOf("#4c91dd","#cf5b74","#6f63c7","#2c9878","#d38a32"),view.inkColor){view.inkColor=it};label("탭하면 정답 보기")}
            InkCanvasView.Tool.HAND->{named("hand","손 도구",true){};icon("close","축소"){view.zoomBy(.8f);renderActiveDock()};label("${view.zoomPercent()}%");icon("plus","확대"){view.zoomBy(1.25f);renderActiveDock()};icon("fit","페이지 맞춤"){view.resetZoom();renderActiveDock()}}
            InkCanvasView.Tool.LASER->{named("laser","레이저 포인터",true){};divider();label("표시는 저장되지 않습니다")}
            InkCanvasView.Tool.IMAGE->{named("image","이미지 선택",true){insertObject(InkCanvasView.Tool.IMAGE,100f,150f)};divider();label("사진을 페이지에 삽입")}
        }
        val position=settings.optString("dock","top")
        fun grip():View=FrameLayout(this).apply{
            contentDescription="활성 도구 메뉴 위치 변경";alpha=.55f;isClickable=true
            listOf(7,14).forEach{x->addView(View(this@MainActivity).apply{background=ui.rounded(ui.color("#53565c"),3f)},FrameLayout.LayoutParams(dp(4),dp(22),Gravity.CENTER_VERTICAL or Gravity.LEFT).apply{leftMargin=dp(x)})}
            setOnClickListener{val modes=listOf("top","bottom","left","right");settings.put("dock",modes[(modes.indexOf(position)+1)%4]);saveSettings();renderActiveDock()}
        }
        if(!narrow&&(position=="left"||position=="right")){
            dock.orientation=LinearLayout.VERTICAL;menu.orientation=LinearLayout.VERTICAL;menu.gravity=Gravity.CENTER_HORIZONTAL
            for(i in 0 until menu.childCount){val child=menu.getChildAt(i);val divider=(child.layoutParams as LinearLayout.LayoutParams).width==dp(7)
                child.layoutParams=LinearLayout.LayoutParams(dp(if(divider)36 else 45),dp(if(divider)1 else 45)).apply{if(i>0)topMargin=dp(7)}
                if(divider)child.scaleX=1f
                if(child is LinearLayout){child.setPadding(0,0,0,0);for(j in 0 until child.childCount){val inner=child.getChildAt(j);if(inner is TextView||j>0&&inner is ImageView)inner.visibility=View.GONE}}
            }
            val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;background=ui.rounded(ui.color("#f7171717"),21f);addView(menu,ViewGroup.LayoutParams(dp(66),-2))}
            val height=dp(min(700,resources.configuration.screenHeightDp-140).coerceAtLeast(160));dock.addView(scroll,LinearLayout.LayoutParams(dp(66),height));dock.addView(grip(),LinearLayout.LayoutParams(dp(24),dp(44)).apply{topMargin=dp(7)})
            surface.addView(dock,FrameLayout.LayoutParams(dp(66),-2,Gravity.CENTER_VERTICAL or if(position=="left")Gravity.LEFT else Gravity.RIGHT).apply{leftMargin=dp(14);rightMargin=dp(14)})
            undoPill?.layoutParams=(undoPill?.layoutParams as? FrameLayout.LayoutParams)?.apply{gravity=Gravity.BOTTOM or Gravity.LEFT;topMargin=0;bottomMargin=dp(18)}
            return
        }
        val maxWidth=dp(min(960,resources.configuration.screenWidthDp-64));menu.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(dp(60),View.MeasureSpec.EXACTLY))
        val scroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;clipToPadding=false;background=ui.rounded(ui.color("#f7171717"),21f);elevation=dp(8).toFloat();addView(menu,ViewGroup.LayoutParams(-2,dp(60)))}
        dock.addView(scroll,LinearLayout.LayoutParams(if(narrow)dp(resources.configuration.screenWidthDp-54)else min(menu.measuredWidth,maxWidth),dp(60)))
        if(!narrow)dock.addView(grip(),LinearLayout.LayoutParams(dp(24),dp(44)).apply{leftMargin=dp(7)})
        val gravity=when(position){"bottom"->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;"left"->Gravity.CENTER_VERTICAL or Gravity.LEFT;"right"->Gravity.CENTER_VERTICAL or Gravity.RIGHT;else->Gravity.TOP or Gravity.CENTER_HORIZONTAL}
        surface.addView(dock,FrameLayout.LayoutParams(-2,dp(60),gravity).apply{topMargin=dp(if(narrow)6 else 10);bottomMargin=dp(18);leftMargin=dp(if(position=="left")14 else 0);rightMargin=dp(if(position=="right")14 else 0)})
        val collide=narrow||((resources.configuration.screenWidthDp-(min(menu.measuredWidth,maxWidth)/ui.density+31))/2<172)
        undoPill?.layoutParams=(undoPill?.layoutParams as? FrameLayout.LayoutParams)?.apply{this.gravity=if(collide)Gravity.BOTTOM or Gravity.LEFT else Gravity.TOP or Gravity.LEFT;topMargin=if(collide)0 else dp(18);bottomMargin=dp(if(narrow)12 else 18)}
    }
    private fun currentWidth():Float=inkView?.let{when(it.tool){InkCanvasView.Tool.ERASER->it.eraserRadius;InkCanvasView.Tool.HIGHLIGHTER->it.highlighterWidth;else->it.inkWidth}}?:4.2f
    private fun widthText(value:Float)=if(value%1f==0f)value.toInt().toString()else String.format(java.util.Locale.US,"%.1f",value)
    private fun setCurrentWidth(value:Float){inkView?.let{when(it.tool){InkCanvasView.Tool.ERASER->{it.eraserRadius=value;settings.put("eraserRadius",value)};InkCanvasView.Tool.HIGHLIGHTER->{it.highlighterWidth=value;settings.put("highlighterWidth",value)};else->{it.inkWidth=value;settings.put("inkWidth",value)}}}}
    private fun pasteSelection(){clipboard.forEach{inkView?.add(InkGeometry.transform(it,25f,25f).put("id",uid("obj")))}}
    private fun clearPage(){confirm("페이지 지우기","현재 페이지의 모든 필기를 지울까요?"){inkView?.let{v->v.currentPage?.objects?.filter{!it.optBoolean("locked")}?.let{v.changeObjects(it,emptyList())}}}}
    private fun applySettings(view:InkCanvasView){
        view.stylusOnly=settings.optBoolean("stylusOnly",true);view.scribbleErase=settings.optBoolean("scribbleErase",true);view.drawHold=settings.optBoolean("drawHold",true)
        view.continuous=settings.optBoolean("continuous",true);view.brush=settings.optString("brush","fountain");view.inkColor=settings.optString("inkColor","#111827");view.inkWidth=settings.f("inkWidth",4.2f)
        view.eraserRadius=settings.f("eraserRadius",22f);view.highlighterWidth=settings.f("highlighterWidth",22f);view.penSettings=settings.optJSONObject("penSettings")?:defaultPenSettings(view.brush);view.preciseEraser=settings.optBoolean("preciseEraser",false)
        status.alpha=settings.f("hudTextOpacity",1f).coerceIn(.35f,1f)
    }
    private fun updatePageLabel(){pageLabel?.text="${(inkView?.currentIndex?:0)+1} / ${ids.size}";zoomLabel?.text="${inkView?.zoomPercent()?:100}%";updateUndoPill()}
    private fun rememberPosition(index:Int){val doc=document?:return;val id=ids.getOrNull(index)?:return
        doc.data.put("lastPageId",id).put("lastPageIndex",index)
        enqueueSave({repository.document(doc.id)?.let{it.data.put("lastPageId",id).put("lastPageIndex",index);repository.putDocument(it)}})}
    private fun addPage(){val doc=document?:return;val index=(inkView?.currentIndex?:0)+1;val template=inkView?.currentPage?.meta?.optString("template","grid")?:"grid"
        work("페이지 추가 중…",{repository.addPage(doc.id,NotePage.blank(template),index)}){refreshPages(index)}}
    private fun refreshPages(index:Int){val doc=document?:return;work("페이지를 불러오는 중…",{val pages=repository.pageIds(doc.id);pages to pages.map{requireNotNull(repository.pageMeta(it))}}){(pages,meta)->
        ids=pages;metas=meta;inkView?.updatePages(ids,metas,index.coerceIn(0,ids.lastIndex));updatePageLabel();renderSidebar()}}
    private fun jumpPage(){prompt("페이지 번호",((inkView?.currentIndex?:0)+1).toString()){value->value.toIntOrNull()?.let{inkView?.goTo(it-1)}}}
    private fun pageMenu(){
        val doc=document?:return;val current=inkView?.currentIndex?:0;val pageId=ids.getOrNull(current)?:return
        val content=showSheet("페이지 레이아웃","${current+1}페이지")
        menuItem(content,"이 페이지로 이동","eye"){inkView?.goTo(current)}
        menuItem(content,if(metas[current].optBoolean("bookmarked"))"북마크 해제"else"북마크","bookmark"){
            val meta=metas[current].copyJson().put("bookmarked",!metas[current].optBoolean("bookmarked"));enqueueSave({repository.putPageMeta(pageId,meta)}){metas=metas.toMutableList().apply{set(current,meta)};inkView?.currentPage?.takeIf{it.id==pageId}?.meta?.put("bookmarked",meta.optBoolean("bookmarked"));renderSidebar()}}
        menuItem(content,"페이지 복제","duplicate"){work("페이지 복제 중…",{val source=requireNotNull(repository.page(pageId));repository.addPage(doc.id,NotePage(uid("page"),source.meta.copyJson(),source.objects.map{it.copyJson().put("id",uid("obj"))}.toMutableList()),current+1)}){refreshPages(current+1)}}
        fun move(delta:Int){val next=(current+delta).coerceIn(0,ids.lastIndex);if(next!=current){val order=ids.toMutableList().apply{add(next,removeAt(current))};work("페이지 이동 중…",{repository.reorder(doc.id,order)}){refreshPages(next)}}}
        menuItem(content,"앞으로 이동","arrow-up"){move(-1)};menuItem(content,"뒤로 이동","arrow-down"){move(1)}
        menuItem(content,"페이지 삭제","trash"){confirm("페이지 삭제","현재 페이지를 삭제할까요?"){work("페이지 삭제 중…",{repository.removePage(doc.id,pageId)}){refreshPages(current)}}}
    }
    private fun outlineMenu(){val doc=document?:return;val entries=(doc.data.optJSONArray("outlines")?:doc.data.array("outline")).objects()
        choose("목차",listOf("현재 페이지를 목차에 추가")+entries.map{it.optString("title")}){index->
            if(index==0)prompt("목차 제목",""){title->val data=doc.data.copyJson();val list=data.optJSONArray("outlines")?:data.array("outline");list.put(json("id" to uid("outline"),"title" to title,"pageId" to ids[inkView?.currentIndex?:0],"pageIndex" to (inkView?.currentIndex?:0)));data.put("outlines",list);document=DocumentInfo(doc.id,data)
                enqueueSave({repository.document(doc.id)?.let{it.data.put("outlines",list);repository.putDocument(it)}})}
            else{val entry=entries[index-1];val byId=ids.indexOf(entry.optString("pageId"));inkView?.goTo(if(byId>=0)byId else entry.optInt("pageIndex"))}
        }
    }
    private fun toolMenu(){
        val content=showSheet("도구막대","추가 도구")
        val tools=listOf(Triple("highlighter","형광펜",InkCanvasView.Tool.HIGHLIGHTER),Triple("shape","도형",InkCanvasView.Tool.SHAPE),Triple("tape","암기 테이프",InkCanvasView.Tool.TAPE))
        val descriptions=listOf("반투명 마커로 중요한 부분을 표시합니다.","선, 화살표, 다각형, 별, 말풍선 등 다양한 도형을 만듭니다.","내용을 가렸다가 탭해 정답을 확인합니다.")
        tools.forEachIndexed{i,(icon,title,tool)->menuItem(content,title,icon,descriptions[i]){inkView?.tool=tool}}
        menuItem(content,"스티커","sticker","페이지 가운데에 선택한 스티커를 추가합니다."){
            val sheet=showSheet("Sticker","스티커");listOf("★","✓","!","?","❤","⚑","→","○","□","△").forEach{value->menuItem(sheet,value,"sticker","선택한 스티커를 현재 페이지에 추가합니다."){inkView?.add(json("id" to uid("sticker"),"type" to "sticker","text" to value,"x" to 440,"y" to 250,"w" to 120,"h" to 120,"fontSize" to 90))}}
        }
        menuItem(content,"레이저 포인터","laser","저장되지 않는 발표용 포인터입니다."){inkView?.tool=InkCanvasView.Tool.LASER}
        menuItem(content,"손 도구","hand","확대·이동과 읽기에 사용합니다."){inkView?.tool=InkCanvasView.Tool.HAND}
        if(resources.configuration.screenWidthDp<=840){menuItem(content,"스티키 노트","sticky"){inkView?.tool=InkCanvasView.Tool.STICKY};menuItem(content,"이미지","image"){insertObject(InkCanvasView.Tool.IMAGE,100f,150f)}}
    }
    private fun penMenu(){
        val content=showSheet("Writing Tool","펜 종류")
        val brushes=listOf("fountain","ballpoint","gel","brush","pencil","fineliner","calligraphy","marker")
        val titles=listOf("만년필","볼펜","젤펜","브러시","연필","파인라이너","캘리그래피","마커")
        val descriptions=listOf("필압과 진행 방향에 반응하는 만년필","압력 변화가 적은 균일한 볼펜","선명한 잉크 코어를 가진 젤펜","큰 굵기 변화와 테이퍼를 가진 브러시","흑연 입자, 압력 농도, 기울기 음영을 사용하는 새 연필 엔진","일정하고 가는 파인라이너","펜 방향에 따른 선 굵기 변화","굵고 일정한 마커")
        brushes.take(6).forEachIndexed{i,id->menuItem(content,titles[i],if(id in listOf("fountain","ballpoint","brush","pencil"))id else "pen",descriptions[i]){
            inkView?.let{view->val profiles=settings.optJSONObject("penProfiles")?:JSONObject();profiles.put(view.brush,view.penSettings.copyJson());view.brush=id;view.penSettings=profiles.optJSONObject(id)?.copyJson()?:defaultPenSettings(id);settings.put("penProfiles",profiles);settings.put("penSettings",view.penSettings);view.tool=InkCanvasView.Tool.PEN};settings.put("brush",id);saveSettings()}}
    }
    private fun colorMenu(selection:Boolean=false){
        val view=inkView?:return;val content=showSheet("Color Mixer","색상 조합기",720);val hsv=FloatArray(3);Color.colorToHSV(ui.color(view.inkColor),hsv)
        val narrow=resources.configuration.screenWidthDp<=840;val layout=if(narrow)ui.col()else ui.row().apply{gravity=Gravity.TOP};val left=ui.col();val right=ui.col()
        val preview=View(this).apply{background=ui.rounded(Color.HSVToColor(hsv),50f)}
        lateinit var spectrum:ClassicSpectrum;lateinit var hue:ClassicHue
        fun update(){preview.background=ui.rounded(Color.HSVToColor(hsv),50f);spectrum.invalidate();hue.invalidate()}
        spectrum=ClassicSpectrum(this,hsv,::update);hue=ClassicHue(this,hsv,::update)
        left.addView(spectrum,LinearLayout.LayoutParams(-1,dp(250)));left.addView(hue,LinearLayout.LayoutParams(-1,dp(26)).apply{topMargin=dp(12);bottomMargin=dp(8)})
        val selected=ui.row().apply{setPadding(dp(9),dp(9),dp(9),dp(9));background=ui.rounded(ui.color("#f2f5f8"),13f)}
        selected.addView(preview,LinearLayout.LayoutParams(dp(39),dp(39)));val labels=ui.col();labels.addView(ui.text("현재 색상",12f,ui.ink,true));labels.addView(ui.text("색상판을 누르거나 아래 표에서 고르세요.",10.5f,ui.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
        selected.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(12)});left.addView(selected)
        right.addView(ui.text("색상표",12f,ui.ink,true),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        val colors=listOf("#111827","#374151","#6b7280","#9ca3af","#d1d5db","#ffffff","#7f1d1d","#dc2626","#f97316","#f59e0b","#eab308","#84cc16","#166534","#16a34a","#10b981","#14b8a6","#06b6d4","#0ea5e9","#1e40af","#2563eb","#4f46e5","#7c3aed","#9333ea","#c026d3","#9d174d","#db2777","#f472b6","#fb7185","#fca5a5","#fecaca","#713f12","#a16207","#ca8a04","#d97706","#92400e","#78350f")
        val cell=if(narrow)(resources.configuration.screenWidthDp-34-35)/6 else 42
        colors.chunked(6).forEach{group->val row=ui.row();group.forEachIndexed{i,color->row.addView(View(this).apply{background=ui.rounded(ui.color(color),10f,ui.color("#21000000"));contentDescription="색상 $color";isClickable=true;setOnClickListener{Color.colorToHSV(ui.color(color),hsv);update()}},LinearLayout.LayoutParams(0,dp(cell),1f).apply{if(i>0)leftMargin=dp(7)})};right.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})}
        right.addView(ui.text("최근 사용",12f,ui.ink,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(10)})
        val recent=settings.array("recentColors");val row=ui.row()
        if(recent.length()==0)row.addView(ui.text("아직 저장된 색상이 없습니다.",11f,ui.muted))else (0 until min(5,recent.length())).forEach{val color=recent.optString(it);row.addView(ui.swatch(color){Color.colorToHSV(ui.color(color),hsv);update()},LinearLayout.LayoutParams(dp(36),dp(36)).apply{rightMargin=dp(8)})};right.addView(row)
        layout.addView(left,if(narrow)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));layout.addView(right,if(narrow)LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(22)}else LinearLayout.LayoutParams(0,-2,.8f).apply{leftMargin=dp(22)})
        content.addView(layout,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(18)})
        sheetActions(content,"적용", "check"){
            val color=String.format("#%06x",Color.HSVToColor(hsv) and 0xffffff)
            if(selection){val selected=view.selected().filter{!it.optBoolean("locked")};view.changeObjects(selected,selected.map{it.copyJson().put("color",color)})}else{view.inkColor=color;settings.put(when(view.tool){InkCanvasView.Tool.HIGHLIGHTER->"highlighterColor";InkCanvasView.Tool.TAPE->"tapeColor";else->"inkColor"},color)}
            settings.put("recentColors",JSONArray((listOf(color)+(0 until recent.length()).map{recent.optString(it)}).distinct().take(20)));saveSettings();closeSheet();renderActiveDock()
        }
    }
    private fun widthMenu(){val view=inkView?:return;val highlight=view.tool==InkCanvasView.Tool.HIGHLIGHTER
        slider("굵기",((if(highlight)view.highlighterWidth else view.inkWidth)*10).toInt(),5,if(highlight)600 else 200){n->if(highlight)view.highlighterWidth=n/10f else{view.inkWidth=n/10f;settings.put("inkWidth",view.inkWidth);saveSettings()}}}
    private fun eraserMenu(){choose("지우개",listOf("획 지우개","정밀 지우개","지우개 크기")){i->if(i<2){inkView?.preciseEraser=i==1;settings.put("preciseEraser",i==1);saveSettings()}else slider("지우개 크기",inkView?.eraserRadius?.toInt()?:22,3,80){inkView?.eraserRadius=it.toFloat()}}}
    private fun insertObject(tool:InkCanvasView.Tool,x:Float,y:Float){
        if(tool==InkCanvasView.Tool.IMAGE){pendingInsert=Triple(tool,x,y);pick("image");return}
        prompt(if(tool==InkCanvasView.Tool.STICKY)"스티키 노트"else"텍스트",""){value->inkView?.add(json("id" to uid("text"),"type" to if(tool==InkCanvasView.Tool.STICKY)"sticky"else"text","x" to x,"y" to y,"w" to if(tool==InkCanvasView.Tool.STICKY)310 else 700,"h" to if(tool==InkCanvasView.Tool.STICKY)230 else 100,"text" to value,"fontSize" to 28,"color" to if(tool==InkCanvasView.Tool.STICKY)settings.optString("stickyColor","#ffe58d")else(inkView?.inkColor?:"#172033"))) }
    }
    private fun showSelection(objects:List<JSONObject>){
        val bar=selectionBar?:return;bar.removeAllViews();bar.visibility=if(objects.isEmpty())View.GONE else View.VISIBLE;if(objects.isEmpty())return
        fun action(label:String,icon:String,danger:Boolean=false,clicked:()->Unit){
            val item=ui.row().apply{gravity=Gravity.CENTER;setPadding(dp(9),0,dp(9),0);contentDescription=t(label);setOnClickListener{clicked()}}
            val tint=if(danger)ui.color("#ff8c8c")else Color.WHITE;item.addView(ui.icon(icon,tint,18));item.addView(ui.text(label,11f,tint,true),LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(5)})
            bar.addView(item,LinearLayout.LayoutParams(-2,dp(38)).apply{if(bar.childCount>0)leftMargin=dp(4)})
        }
        action("잘라내기","cut"){clipboard=inkView?.selected()?.filter{!it.optBoolean("locked")}?.map{it.copyJson()}?:emptyList();inkView?.deleteSelection()}
        action("복사","copy"){clipboard=inkView?.selected()?.map{it.copyJson()}?:emptyList();message("복사했습니다.")}
        action("복제","duplicate"){inkView?.selected()?.map{InkGeometry.transform(it,25f,25f).put("id",uid("obj"))}?.forEach{inkView?.add(it)}}
        action("색상","palette"){colorMenu(true)}
        action(if(objects.all{it.optBoolean("locked")})"잠금 해제"else"잠금","lock"){val selected=inkView?.selected()?:emptyList();val locked=selected.all{it.optBoolean("locked")};inkView?.changeObjects(selected,selected.map{it.copyJson().put("locked",!locked)})}
        action("앞으로","arrow-up"){inkView?.bringSelectionToFront()};action("삭제","trash",true){inkView?.deleteSelection()}
        bar.post{val view=inkView?:return@post;val surface=editorSurface?:return@post;val top=objects.minOf{InkGeometry.bounds(it).top};val bottom=objects.maxOf{InkGeometry.bounds(it).bottom};val left=objects.minOf{InkGeometry.bounds(it).left};val point=view.screenPoint(left,top)
            bar.layoutParams=(bar.layoutParams as FrameLayout.LayoutParams).apply{gravity=Gravity.TOP or Gravity.LEFT;leftMargin=(view.left+point.x).toInt().coerceIn(dp(10),max(dp(10),surface.width-bar.width-dp(10)));topMargin=if(point.y>bar.height+dp(12))point.y.toInt()-bar.height-dp(10)else min(surface.height-bar.height-dp(10),view.screenPoint(left,bottom).y.toInt()+dp(10));bottomMargin=0}}
    }
    private fun objectMenu(obj:JSONObject){
        if(obj.optString("type")=="tape"){inkView?.toggleTape(obj);return}
        if(obj.optString("type") in listOf("text","sticky","sticker"))choose("텍스트",listOf("내용 편집","글자 크기","굵게/보통","삭제")){i->
            if(obj.optBoolean("locked")){message("잠긴 객체입니다.");return@choose}
            when(i){
                0->prompt("내용 편집",obj.optString("text")){value->inkView?.changeObjects(listOf(obj),listOf(obj.copyJson().put("text",value)))}
                1->slider("글자 크기",obj.optInt("fontSize",28),8,150){size->inkView?.changeObjects(listOf(obj),listOf(obj.copyJson().put("fontSize",size)))}
                2->inkView?.changeObjects(listOf(obj),listOf(obj.copyJson().put("fontWeight",if(obj.optInt("fontWeight",400)>=600)400 else 700)))
                3->inkView?.changeObjects(listOf(obj),emptyList())
            }
        }
        else if(obj.optString("type")=="math")calculatePrompt(obj.optString("expression"))
    }

    private fun editorMenu(){
        val doc=document?:return;val view=inkView?:return;val content=showSheet(doc.title,"문서 옵션")
        menuItem(content,"파일 내보내기","export","노트 파일 또는 PDF로 저장합니다."){exportMenu(doc,false)}
        menuItem(content,"문서 검색","search","입력한 텍스트, 수식, 제목을 찾습니다."){searchDocument()}
        menuItem(content,if(doc.favorite)"파일 즐겨찾기 해제"else"파일 즐겨찾기","star","라이브러리 즐겨찾기와 목록 상단에 고정합니다."){mutateDocument(doc){put("favorite",!doc.favorite)}}
        menuItem(content,"페이지 번호로 이동","arrow","페이지 창에서 원하는 페이지 번호를 입력합니다."){if(pageSidebar==null)togglePages()}
        menuItem(content,"손글씨 수식 계산","math","현재 화면의 필기 수식을 한 번 인식해 결과를 표시합니다."){recognize(true,false)}
        menuItem(content,if(view.readOnly)"편집 모드로 전환"else"읽기 모드",if(view.readOnly)"eye-off"else"read","실수로 필기되지 않도록 편집을 잠급니다."){view.readOnly=!view.readOnly;if(view.readOnly)view.tool=InkCanvasView.Tool.HAND}
        menuItem(content,if(view.continuous)"한 페이지 보기"else"연속 페이지 보기","sidebar"){view.changePageMode(!view.continuous);settings.put("continuous",view.continuous);saveSettings()}
        menuItem(content,"페이지 맞춤","fit"){view.resetZoom()}
        menuItem(content,"노트 가져오기","import",".ifnote 파일을 추가합니다."){pick("note")}
        menuItem(content,"PDF 가져오기","page-plus","PDF 각 페이지를 노트 배경으로 추가하고 텍스트를 검색합니다."){pick("appendPdf")}
        menuItem(content,"설정","settings"){showSettings()}
    }
    private fun exportMenu(doc:DocumentInfo,share:Boolean){
        val content=showSheet("Export","공유 및 내보내기")
        fun export(format:String,toShare:Boolean=false){inkView?.finishContact();afterSaved{fileExport.prepare(doc,format,toShare,ids.getOrNull(inkView?.currentIndex?:0))}}
        menuItem(content,"노트 파일 (.ifnote)","export","필기·이미지·PDF 배경·녹음을 보존합니다. 다시 가져와 편집할 수 있습니다."){export("ifnote")}
        menuItem(content,"PDF 파일 (.pdf)","page-plus","모든 페이지를 필기와 배경이 포함된 PDF로 저장합니다."){export("pdf")}
        menuItem(content,"기기 공유","share","지원되는 앱으로 편집 가능한 노트를 공유합니다."){export("ifnote",true)}
        menuItem(content,"PDF 주석 내보내기","bookmark","필기, 텍스트, 도형 주석을 XFDF 파일로 저장합니다."){export("xfdf")}
        if(document?.id==doc.id)menuItem(content,"현재 페이지 PNG","image"){export("png")}
    }
    private fun pick(mode:String){
        importMode=mode
        val mime=when(mode){"pdf","appendPdf"->"application/pdf";"image"->"image/*";else->"*/*"}
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime),REQUEST_IMPORT)
    }
    private fun uriName(uri:Uri):String=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"가져온 노트"
    private fun importUri(uri:Uri,mode:String){
        val name=uriName(uri)
        val target=document;val index=(inkView?.currentIndex?:0)+1
        if(mode=="image"){
            work("이미지를 가져오는 중…",{val mime=contentResolver.getType(uri)?:"image/png";val id=requireNotNull(contentResolver.openInputStream(uri)).use{repository.storeAsset(it,NoteRepository.extension(mime))}
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(repository.asset(id).path,options);require(options.outWidth>0){"이미지를 읽지 못했습니다."};Triple(id,options.outWidth,options.outHeight)
            }){(id,w,h)->val p=pendingInsert;val scale=min(650f/w,720f/h).coerceAtMost(1f);inkView?.add(json("id" to uid("image"),"type" to "image","src" to "asset:$id","x" to (p?.second?:100f),"y" to (p?.third?:150f),"w" to w*scale,"h" to h*scale));pendingInsert=null}
        }else work("파일을 가져오는 중…",{
            val input=requireNotNull(contentResolver.openInputStream(uri)){"파일을 열 수 없습니다."}
            input.use{if(mode=="pdf"||mode=="appendPdf"||name.endsWith(".pdf",true))PdfImporter.import(repository,it,name.removeSuffix(".pdf"),folder,if(mode=="appendPdf")target?.id else null,index)
                else repository.importNote(it,folder)}
        }){doc->openDocument(doc.id,if(mode=="appendPdf")index else 0)}
    }
    @Deprecated("Uses platform result contracts for API 23 compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==NativeFileExport.REQUEST_SAVE){fileExport.result(resultCode,data);return}
        if(requestCode==REQUEST_IMPORT&&resultCode==RESULT_OK)data?.data?.let{importUri(it,importMode)}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIncoming(intent)}
    private fun handleIncoming(intent:Intent?){
        if(intent?.action==Intent.ACTION_SEND&&intent.type=="application/pdf"){
            @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if(uri!=null)importUri(uri,"pdf")
            intent.action=Intent.ACTION_MAIN
        }
    }
    private fun searchDocument(){
        val surface=editorSurface?:return;val doc=document?:return
        searchDrawer?.let{surface.removeView(it);searchDrawer=null;return}
        val drawer=ui.col().apply{setBackgroundColor(ui.color("#fcfcfd"));elevation=dp(12).toFloat();isClickable=true};searchDrawer=drawer
        val header=ui.row().apply{setPadding(dp(16),0,dp(12),0)};header.addView(ui.text("문서 검색",14f,ui.ink,true),LinearLayout.LayoutParams(0,-1,1f));header.addView(ui.iconButton("close","닫기",width=34){surface.removeView(drawer);searchDrawer=null});drawer.addView(header,LinearLayout.LayoutParams(-1,dp(58)));drawer.addView(ui.line(ui.color("#e3e5e9")))
        val input=ui.field("","손글씨 OCR·텍스트·수식 검색",42);drawer.addView(input,LinearLayout.LayoutParams(-1,dp(42)).apply{setMargins(dp(14),dp(14),dp(14),dp(14))})
        val results=ui.col().apply{setPadding(dp(14),0,dp(14),dp(14))};drawer.addView(ScrollView(this).apply{addView(results)},LinearLayout.LayoutParams(-1,0,1f))
        var generation=0
        input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
            val search=s.toString();val request=++generation;results.removeAllViews();if(search.isBlank())return
            repository.executor.execute{val matches=repository.pageIds(doc.id).mapIndexedNotNull{index,id->val page=repository.page(id)?:return@mapIndexedNotNull null;val text=page.meta.optString("pdfText")+" "+page.meta.optString("ocrText")+" "+page.objects.joinToString(" "){it.optString("text")+" "+it.optString("expression")};val at=text.indexOf(search,ignoreCase=true);if(at>=0)index to text.substring(max(0,at-20),min(text.length,at+80))else null}
                runOnUiThread{if(generation!=request||searchDrawer!==drawer)return@runOnUiThread;results.removeAllViews();if(matches.isEmpty())results.addView(ui.text("검색 결과가 없습니다.",13f,ui.muted));matches.forEach{(index,snippet)->menuItem(results,"${index+1}페이지","search",snippet){inkView?.goTo(index)}}}}
        };override fun afterTextChanged(s:Editable?){} })
        surface.addView(drawer,FrameLayout.LayoutParams(dp(min(420,(resources.configuration.screenWidthDp*.92f).toInt())),-1,Gravity.RIGHT))
    }
    private fun scheduleOcr(){
        ocrTask?.let{handler.removeCallbacks(it)}
        if(!settings.optBoolean("autoOcr",true)&&!settings.optBoolean("autoMath",false))return
        ocrTask=Runnable{recognize(false,true)}.also{handler.postDelayed(it,5000)}
    }
    private fun recognize(math:Boolean,automatic:Boolean){
        if(recognitionBusy){if(!automatic)message("필기 인식이 진행 중입니다.");return}
        val view=inkView?:return;val page=view.currentPage?:return;val doc=document?:return
        val selected=view.selected();val objects=(if(selected.isNotEmpty()&&!automatic)selected else page.objects).map{it.copyJson()}
        if(automatic&&objects.none{it.optString("type")=="stroke"})return
        val signature=objects.filter{it.optString("type")=="stroke"}.joinToString{it.optString("id")+":"+it.array("points").length()}.hashCode()
        if(automatic&&page.meta.optInt("ocrSignature",Int.MIN_VALUE)==signature)return
        recognitionBusy=true
        if(!automatic)status.text=t("필기를 인식하고 있습니다. 첫 사용에는 모델 다운로드가 필요합니다.")
        recognition.executor.execute{
            try{
                val strokes=objects.filter{it.optString("type")=="stroke"}
                val recognized=if(strokes.isNotEmpty())recognition.handwriting(objects,page.width,page.height,settings.optString("language","ko"),math)
                    else{val bitmap=StreamingPdf.render(repository,page,1400);try{recognition.image(bitmap,settings.optString("language","ko"))}finally{bitmap.recycle()}}
                runOnUiThread{
                    if(document?.id!=doc.id)return@runOnUiThread
                    if(automatic){
                        val meta=page.meta.copyJson().put("ocrText",recognized).put("ocrSignature",signature)
                        enqueueSave({val current=repository.pageMeta(page.id)?:return@enqueueSave;current.put("ocrText",recognized).put("ocrSignature",signature);repository.putPageMeta(page.id,current)})
                        if(view.currentPage?.id==page.id){
                            view.currentPage?.meta?.put("ocrText",recognized)?.put("ocrSignature",signature)
                            if(settings.optBoolean("autoMath",false)&&recognized.trim().endsWith("=")){
                                try{
                                    val expression=recognized.lineSequence().last().trim().removeSuffix("=").trim()
                                    val result=MathEngine(degrees=settings.optBoolean("degrees")).calculate(expression)
                                    if(view.currentPage?.objects?.none{it.optString("type")=="math"&&it.optString("expression")==expression}==true)
                                        view.add(json("id" to uid("math"),"type" to "math","expression" to expression,"result" to result.display(),"x" to 100,"y" to min(page.height-100,(objects.lastOrNull()?.let{InkGeometry.bounds(it).bottom}?:150f)+30),"w" to 600,"h" to 92,"fontSize" to 27))
                                }catch(_:Exception){}
                            }
                        }
                    }else if(math)calculatePrompt(recognized.replace('\n',' '))else{
                        prompt("인식 결과",recognized){value->if(inkView?.currentPage?.id==page.id)inkView?.add(json("id" to uid("text"),"type" to "text","text" to value,"x" to 80,"y" to 100,"w" to 800,"h" to 140,"fontSize" to 28,"color" to "#225e9d"))}
                    }
                }
            }catch(e:Exception){if(!automatic)runOnUiThread{message(e.message?:"필기 인식 실패")}}
            finally{recognitionBusy=false}
        }
    }
    private fun mathMenu(){choose("수식",listOf("손글씨 수식 계산","수식 직접 입력","각도 단위 변경")){i->when(i){0->recognize(true,false);1->calculatePrompt("");2->{settings.put("degrees",!settings.optBoolean("degrees"));saveSettings();message(if(settings.optBoolean("degrees"))"도 단위"else"라디안 단위")}}}}
    private fun calculatePrompt(expression:String){prompt("수식 확인",expression){value->
        val doc=document?:return@prompt;val variables=doc.data.optJSONObject("variables")?:JSONObject();val map=variables.keys().asSequence().associateWith{variables.optDouble(it)}
        val result=MathEngine(map,settings.optBoolean("degrees")).calculate(value)
        result.variable?.let{variables.put(it,result.value);doc.data.put("variables",variables);val copy=doc.data.copyJson();enqueueSave({repository.putDocument(DocumentInfo(doc.id,copy))})}
        inkView?.add(json("id" to uid("math"),"type" to "math","expression" to value,"result" to result.display(),"x" to 100,"y" to 180,"w" to 600,"h" to 92,"fontSize" to 27,"color" to "#225e9d"))
    }}
    private fun toggleRecording(){
        if(audio.recording){stopRecording();return}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),REQUEST_AUDIO);return}
        audio.start();status.text=t("● 녹음 중")
    }
    private fun stopRecording(){val doc=document?:return;try{
        val clip=audio.stop(ids.getOrNull(inkView?.currentIndex?:0)?:"",inkView?.currentIndex?:0)?:return
        val data=doc.data.copyJson();val clips=data.array("audio");clips.put(clip);data.put("audio",clips);document=DocumentInfo(doc.id,data)
        enqueueSave({repository.putDocument(DocumentInfo(doc.id,data))}){message("녹음을 저장했습니다.")}
    }catch(e:Exception){message("녹음 저장 실패: ${e.message}")}}
    private fun audioMenu(){val doc=document?:return;val clips=doc.data.array("audio").objects();if(clips.isEmpty()){message("녹음이 없습니다.");return}
        choose("녹음",clips.map{"${it.optString("title","녹음")} · ${it.optDouble("duration").roundToInt()}초"}){i->val clip=clips[i];choose("녹음",listOf("재생","녹음한 페이지로 이동","삭제")){n->when(n){
            0->audio.play(clip);1->{val byId=ids.indexOf(clip.optString("pageId"));inkView?.goTo(if(byId>=0)byId else clip.optInt("pageIndex"))}
            2->{val data=doc.data.copyJson().put("audio",JSONArray(clips.filter{it.optString("id")!=clip.optString("id")}));document=DocumentInfo(doc.id,data);enqueueSave({repository.putDocument(DocumentInfo(doc.id,data))})}
        }}}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==REQUEST_AUDIO){if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED&&document!=null)toggleRecording()else message("녹음 권한이 필요합니다.")}}
    private fun showSettings(){
        val content=showSheet("bad note","설정",680)
        fun setting(label:String,description:String,control:View){
            val line=ui.row().apply{setPadding(dp(12),dp(10),dp(12),dp(10));minimumHeight=dp(68);background=ui.rounded(ui.color("#f4f6f8"),13f)}
            val copy=ui.col();copy.addView(ui.text(label,13f,ui.ink,true));copy.addView(ui.text(description,11f,ui.muted).apply{setLineSpacing(dp(3).toFloat(),1f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
            line.addView(copy,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(16)});line.addView(control)
            content.addView(line,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        }
        fun toggle(label:String,description:String,key:String,default:Boolean){
            val check=CheckBox(this).apply{isChecked=settings.optBoolean(key,default);buttonTintList=android.content.res.ColorStateList.valueOf(ui.accent);contentDescription=t(label)
                setOnCheckedChangeListener{_,checked->settings.put(key,checked);inkView?.let{applySettings(it);if(key=="continuous")it.changePageMode(checked)};saveSettings()}}
            setting(label,description,check)
        }
        val languages=listOf("ko","en","ja","zh","pt");val names=listOf("한국어","English","日本語","中文","Português")
        setting("언어","앱 표시 언어를 바꿉니다. 노트 내용과 파일명은 변경하지 않습니다.",ui.button(names[languages.indexOf(settings.optString("language","ko")).coerceAtLeast(0)],height=38){choose("언어",names){i->settings.put("language",languages[i]);loadDictionary();saveSettings();val index=inkView?.currentIndex?:0;if(document==null)showLibrary()else showEditor(index);showSettings()}})
        toggle("스타일러스 전용 필기","손바닥과 손가락 입력을 필기에서 제외합니다.","stylusOnly",true)
        toggle("낙서해서 지우기","같은 영역을 여러 번 왕복해 덮은 펜 획을 지웁니다.","scribbleErase",true)
        toggle("그려서 도형 만들기","직선을 그린 뒤 잠시 유지하면 정돈된 도형으로 변환합니다.","drawHold",true)
        toggle("스타일러스 정보 표시","필압, 기울기, 입력 장치를 화면에 표시합니다.","telemetry",false)
        val opacity=ui.col();val output=ui.text("${(settings.f("hudTextOpacity",1f)*100).toInt()}%",12f,ui.ink,true).apply{gravity=Gravity.RIGHT};opacity.addView(output)
        opacity.addView(range((settings.f("hudTextOpacity",1f)*100).toInt(),35,100){value->output.text="$value%";settings.put("hudTextOpacity",value/100f);applyHudOpacity();saveSettings()},LinearLayout.LayoutParams(dp(160),dp(28)))
        setting("HUD 텍스트 투명도","S Pen 지우개와 상태 표시 글자의 투명도를 조절합니다.",opacity)
        toggle("연속 페이지 보기","모든 페이지를 세로로 이어서 표시합니다.","continuous",true)
        toggle("손글씨 OCR 자동 등록","필기를 인식해 문서 검색에 등록합니다.","autoOcr",true)
        toggle("손글씨 수식 자동 계산","등호로 끝나는 수식을 인식해 계산합니다.","autoMath",false)
        menuItem(content,"업데이트 확인","download","bad note ${BuildConfig.VERSION_NAME}"){checkUpdates()}
        sheetActions(content,"완료","check",false){closeSheet()}
    }
    private fun applyHudOpacity(){val opacity=settings.f("hudTextOpacity",1f).coerceIn(.35f,1f);pageLabel?.alpha=opacity;zoomLabel?.alpha=opacity;status.alpha=opacity;inkView?.telemetry=settings.optBoolean("telemetry");inkView?.hudOpacity=opacity}
    private fun range(value:Int,minimum:Int=0,maximum:Int=100,changed:(Int)->Unit)=SeekBar(this).apply{
        max=maximum-minimum;progress=(value-minimum).coerceIn(0,max);setPadding(dp(4),0,dp(4),0)
        progressTintList=android.content.res.ColorStateList.valueOf(ui.accent);thumbTintList=android.content.res.ColorStateList.valueOf(ui.accent)
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user)changed(p+minimum)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})
    }
    private fun gestureGuide(){
        val content=showSheet("Quick Guide","제스처 사용법")
        listOf(Triple("gesture","두 손가락 핀치","페이지 중심을 유지하며 8%~800% 확대·축소합니다."),Triple("undo","두 손가락 탭","실행 취소합니다."),Triple("redo","세 손가락 탭","다시 실행합니다."),Triple("arrow","두 손가락 좌우 쓸기","이전 또는 다음 페이지로 이동합니다."),Triple("eraser","낙서해서 지우기","필기 위를 빠르게 지그재그로 문질러 지웁니다."),Triple("shape","그린 뒤 길게 유지","직선이나 닫힌 원을 정돈된 도형으로 변환합니다.")).forEach{(icon,title,description)->menuItem(content,title,icon,description){}}
    }
    private fun assistantMenu(){
        val content=showSheet("On-device","학습 도우미")
        val text=inkView?.currentPage?.let{p->p.meta.optString("ocrText")+" "+p.objects.joinToString(" "){it.optString("text")}}?:""
        val words=Regex("[가-힣A-Za-z0-9]{2,}").findAll(text).map{it.value}.groupingBy{it}.eachCount().entries.sortedByDescending{it.value}.take(6).map{it.key}
        menuItem(content,"현재 페이지 핵심어","sparkles",words.joinToString(" · ").ifBlank{"입력한 텍스트가 있는 페이지에서 사용할 수 있습니다."}){message(words.joinToString(" · ").ifBlank{"텍스트를 먼저 추가하세요."})}
        menuItem(content,"빠른 복습 문제","check-circle",words.firstOrNull()?.let{"${it}의 정의나 핵심 내용을 말해 보세요."}?:"텍스트를 먼저 추가하세요."){message(words.firstOrNull()?.let{"${it}의 정의나 핵심 내용을 말해 보세요."}?:"텍스트를 먼저 추가하세요.")}
        menuItem(content,"암기 테이프 시작","tape","답을 완전히 가리고 눌러 확인합니다."){inkView?.tool=InkCanvasView.Tool.TAPE}
        menuItem(content,"손글씨 OCR","search","현재 페이지 필기를 텍스트로 인식합니다."){recognize(false,false)}
        menuItem(content,"수식 계산","math","손글씨 수식을 계산하거나 직접 입력합니다."){mathMenu()}
    }
    private fun togglePages(){
        val surface=editorSurface?:return
        pageSidebar?.let{surface.removeView(it);pageSidebar=null;resizeForSidebar();return}
        val sidebar=ui.col().apply{setBackgroundColor(ui.color("#fcfcfd"));elevation=dp(12).toFloat();isClickable=true};pageSidebar=sidebar
        surface.addView(sidebar,FrameLayout.LayoutParams(dp(min(420,(resources.configuration.screenWidthDp*.92f).toInt())),-1,Gravity.LEFT))
        renderSidebar();resizeForSidebar()
    }
    private fun resizeForSidebar(){
        val narrow=resources.configuration.screenWidthDp<=840
        inkView?.let{it.layoutParams=(it.layoutParams as FrameLayout.LayoutParams).apply{leftMargin=if(!narrow&&pageSidebar!=null)dp(420)else 0}}
    }
    private fun renderSidebar(){
        val sidebar=pageSidebar?:return;sidebar.removeAllViews()
        val header=ui.row().apply{setPadding(dp(16),0,dp(12),0)};val tabs=ui.row()
        listOf("pages" to "페이지","outline" to "목차","audio" to "오디오").forEach{(id,title)->
            val tab=ui.col().apply{gravity=Gravity.BOTTOM;setOnClickListener{sidebarTab=id;renderSidebar()};contentDescription=title}
            tab.addView(ui.text(title,13f,if(sidebarTab==id)ui.ink else ui.muted,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(41)))
            tab.addView(View(this).apply{setBackgroundColor(if(sidebarTab==id)ui.accent else Color.TRANSPARENT)},LinearLayout.LayoutParams(-1,dp(3)))
            tabs.addView(tab,LinearLayout.LayoutParams(dp(54),dp(58)).apply{rightMargin=dp(12)})
        }
        header.addView(tabs,LinearLayout.LayoutParams(0,-1,1f));header.addView(ui.iconButton("close","닫기",width=34){togglePages()});sidebar.addView(header,LinearLayout.LayoutParams(-1,dp(58)));sidebar.addView(ui.line(ui.color("#e3e5e9")))
        val content=ui.col().apply{setPadding(dp(14),dp(14),dp(14),dp(14))};sidebar.addView(ScrollView(this).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        if(sidebarTab=="pages"){
            val controls=ui.row();val jump=ui.col();jump.addView(ui.text("페이지 이동",11f,ui.muted,true))
            val number=ui.field("${(inkView?.currentIndex?:0)+1}",height=36).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;gravity=Gravity.CENTER};jump.addView(number,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(5)})
            controls.addView(jump,LinearLayout.LayoutParams(0,-2,1f));controls.addView(ui.iconButton("arrow","페이지 이동",ui.blue,36,36,17){number.text.toString().toIntOrNull()?.let{inkView?.goTo(it-1);renderSidebar()}},LinearLayout.LayoutParams(dp(36),dp(36)).apply{leftMargin=dp(8);gravity=Gravity.BOTTOM});content.addView(controls,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
            val cardWidth=(min(420,(resources.configuration.screenWidthDp*.92f).toInt())-44)/2
            ids.indices.chunked(2).forEach{pair->val line=ui.row().apply{gravity=Gravity.TOP}
                pair.forEachIndexed{n,index->val item=ui.col();val card=FrameLayout(this).apply{background=ui.rounded(Color.WHITE,8f,if(index==inkView?.currentIndex)ui.accent else ui.color("#d7dbe1"),if(index==inkView?.currentIndex)2f else 1f);setPadding(dp(2),dp(2),dp(2),dp(2));clipToOutline=true;elevation=dp(3).toFloat();contentDescription="${index+1}페이지";setOnClickListener{inkView?.goTo(index);renderSidebar()};setOnLongClickListener{inkView?.goTo(index);pageMenu();true}}
                    card.addView(thumbnail(null,ids[index]),FrameLayout.LayoutParams(-1,-1));if(metas.getOrNull(index)?.optBoolean("bookmarked")==true)card.addView(ui.icon("bookmark",ui.accent,20),FrameLayout.LayoutParams(dp(20),dp(20),Gravity.TOP or Gravity.RIGHT))
                    item.addView(card,LinearLayout.LayoutParams(-1,dp((cardWidth*1.414f).toInt())));val label=ui.row();label.addView(ui.text("${index+1}",12f,ui.muted,true),LinearLayout.LayoutParams(0,dp(30),1f));label.addView(ui.iconButton("more","${index+1}페이지 메뉴",ui.muted,30,30,18){inkView?.goTo(index);pageMenu()});item.addView(label)
                    line.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply{if(n>0)leftMargin=dp(16)})}
                if(pair.size==1)line.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply{leftMargin=dp(16)})
                content.addView(line,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(18)})
            }
            content.addView(ui.button("새 페이지",icon="plus"){addPage()})
        }else if(sidebarTab=="outline"){
            menuItem(content,"현재 페이지를 목차에 추가","plus"){outlineMenu()}
            val entries=document?.data?.let{(it.optJSONArray("outlines")?:it.array("outline")).objects()}.orEmpty()
            entries.forEach{entry->menuItem(content,entry.optString("title",entry.optString("text")),"bookmark","${entry.optInt("pageIndex")+1}페이지"){val index=ids.indexOf(entry.optString("pageId"));inkView?.goTo(if(index>=0)index else entry.optInt("pageIndex"))}}
            if(entries.isEmpty())content.addView(ui.text("목차가 없습니다.",13f,ui.muted))
        }else{
            menuItem(content,if(audio.recording)"녹음 중지"else"녹음 시작","mic"){toggleRecording();renderSidebar()}
            val clips=document?.data?.array("audio")?.objects().orEmpty()
            clips.forEach{clip->menuItem(content,clip.optString("title","녹음"),"play","${clip.optDouble("duration").toInt()}초"){audio.play(clip)}}
            if(clips.isEmpty())content.addView(ui.text("아직 녹음이 없습니다.",13f,ui.muted))
        }
    }
    private fun penSettingsSheet(){
        val view=inkView?:return;val content=showSheet("Writing Tool","펜 세부 설정",680)
        val preview=object:View(this){val renderer=NoteRenderer();override fun onDraw(canvas:android.graphics.Canvas){canvas.drawColor(ui.color("#f9f7f2"));canvas.save();canvas.scale(width/760f,height/150f);val points=(40..720 step 8).map{x->val f=(x-40)/680f;InkPoint(x.toFloat(),76f+sin(f*PI*3).toFloat()*24f,.12f+sin(f*PI).toFloat()*.83f)};renderer.stroke(canvas,json("brush" to view.brush,"color" to view.inkColor,"width" to view.inkWidth*2,"settings" to view.penSettings),points);canvas.restore()}}
        preview.background=ui.rounded(ui.color("#f9f7f2"),15f,ui.color("#dfe3e8"));preview.clipToOutline=true
        content.addView(preview,LinearLayout.LayoutParams(-1,dp(150)).apply{bottomMargin=dp(18)})
        val fields=if(view.brush=="pencil")listOf("hardness" to "펜촉 경도","grain" to "흑연 입자","tiltShade" to "기울기 음영","pressure" to "압력 민감도","smoothing" to "획 안정화","opacity" to "농도")else listOf("pressure" to "압력 민감도","sharpness" to "펜촉 선명도","smoothing" to "획 안정화","taper" to "시작·끝 테이퍼","opacity" to "불투명도")
        fields.chunked(if(resources.configuration.screenWidthDp<=840)1 else 2).forEach{group->val row=ui.row().apply{gravity=Gravity.TOP}
            group.forEachIndexed{i,(key,title)->val field=ui.col();val line=ui.row();line.addView(ui.text(title,12f,ui.color("#4d535b"),true),LinearLayout.LayoutParams(0,-2,1f));val value=(view.penSettings.f(key,defaultPenSettings(view.brush).f(key))*100).roundToInt();val output=ui.text("$value%",11f,ui.color("#617182"));line.addView(output);field.addView(line)
                field.addView(range(value,if(key=="opacity")20 else 0,if(key=="smoothing")90 else 100){v->view.penSettings.put(key,v/100f);settings.put("penSettings",view.penSettings);output.text="$v%";preview.invalidate();saveSettings()},LinearLayout.LayoutParams(-1,dp(28)).apply{topMargin=dp(6)})
                row.addView(field,LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)leftMargin=dp(18)})}
            if(group.size==1&&resources.configuration.screenWidthDp>840)row.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply{leftMargin=dp(18)})
            content.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})}
        val actions=ui.row().apply{gravity=Gravity.RIGHT};actions.addView(ui.button("기본값"){view.penSettings=defaultPenSettings(view.brush);settings.put("penSettings",view.penSettings);saveSettings();penSettingsSheet()});actions.addView(ui.button("완료",ui.accent,Color.WHITE,icon="check"){closeSheet()},LinearLayout.LayoutParams(-2,dp(42)).apply{leftMargin=dp(9)});content.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
    }
    private fun defaultPenSettings(brush:String):JSONObject{
        val values=when(brush){"ballpoint"->listOf(.08,.48,.1,.12,1.0);"gel"->listOf(.16,.45,.2,.2,1.0);"brush"->listOf(.95,.28,.35,.72,.96);"pencil"->listOf(.7,.26,0.0,0.0,.78);"fineliner"->listOf(.02,.6,.85,.06,.98);else->listOf(.76,.35,.58,.42,1.0)}
        return json("pressure" to values[0],"smoothing" to values[1],"sharpness" to values[2],"taper" to values[3],"opacity" to values[4],"hardness" to .58,"grain" to .66,"tiltShade" to .82)
    }
    private fun checkUpdates(){status.text=t("업데이트 확인 중…");network.execute{
        try{val updater=AppUpdater(this);val release=updater.latest();runOnUiThread{
            if(release==null)message("최신 버전입니다.")else confirm("${release.version} 업데이트","업데이트 파일을 내려받을까요?"){
                setBusy(true,"업데이트 다운로드 중…");network.execute{try{val file=updater.download(release);runOnUiThread{setBusy(false);confirm("업데이트 설치","Android 설치 화면을 엽니다."){updater.install(file)}}}
                catch(e:Exception){runOnUiThread{setBusy(false);message(e.message?:"업데이트 실패")}}}
            }
        }}catch(e:Exception){runOnUiThread{message(e.message?:"업데이트 확인 실패")}}
    }}
    private fun closeSheet(){
        modal?.let{root.removeView(it)};modal=null
        if(Build.VERSION.SDK_INT>=31)body.setRenderEffect(null)
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(root.windowToken,0)
    }
    private fun showSheet(eyebrow:String,title:String,width:Int=680):LinearLayout{
        closeSheet();inkView?.finishContact()
        val narrow=resources.configuration.screenWidthDp<=840
        val shade=FrameLayout(this).apply{setBackgroundColor(0x61000000);isClickable=true;setOnClickListener{closeSheet()}}
        if(Build.VERSION.SDK_INT>=31)body.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(4).toFloat(),dp(4).toFloat(),android.graphics.Shader.TileMode.CLAMP))
        val scroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;background=ui.rounded(Color.WHITE,22f);clipToOutline=true;elevation=dp(18).toFloat();isClickable=true;setOnClickListener{}}
        val content=ui.col().apply{setPadding(dp(if(narrow)17 else 22),dp(if(narrow)20 else 22),dp(if(narrow)17 else 22),dp(if(narrow)20 else 22));isClickable=true;setOnClickListener{}}
        val header=ui.row().apply{gravity=Gravity.TOP;minimumHeight=dp(56)};val labels=ui.col().apply{setPadding(0,dp(6),0,0)}
        if(eyebrow.isNotBlank())labels.addView(ui.text(eyebrow,11f,ui.color("#66809a"),true).apply{letterSpacing=.05f})
        labels.addView(ui.text(title,21f,ui.ink,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        header.addView(labels,LinearLayout.LayoutParams(0,-2,1f));header.addView(ui.iconButton("close","닫기"){closeSheet()})
        content.addView(header,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)});scroll.addView(content)
        if(narrow)scroll.background=GradientDrawable().apply{setColor(Color.WHITE);val r=dp(22).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
        shade.addView(scroll,FrameLayout.LayoutParams(if(narrow)-1 else dp(min(width,resources.configuration.screenWidthDp-28)),-2,if(narrow)Gravity.BOTTOM else Gravity.CENTER))
        root.addView(shade,FrameLayout.LayoutParams(-1,-1));modal=shade
        scroll.addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->
            val maximum=min(dp(if(narrow)820 else 780),(root.height*if(narrow).88f else .84f).roundToInt());if(maximum>0&&v.height>maximum)v.post{if(v.isAttachedToWindow)v.layoutParams=(v.layoutParams as FrameLayout.LayoutParams).apply{height=maximum}}
        }
        return content
    }
    private fun sheetActions(content:LinearLayout,label:String="확인",icon:String?=null,cancel:Boolean=true,action:()->Unit){
        val actions=ui.row().apply{gravity=Gravity.RIGHT}
        if(cancel)actions.addView(ui.button("취소"){closeSheet()})
        actions.addView(ui.button(label,ui.accent,Color.WHITE,icon=icon){try{action()}catch(e:Exception){message(e.message?:"작업 실패")}},LinearLayout.LayoutParams(-2,dp(42)).apply{leftMargin=dp(9)})
        content.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(22)})
    }
    private fun templateGrid(selected:String,onPick:(String)->Unit):View{
        val grid=ui.col();val options=mutableMapOf<String,LinearLayout>()
        classicTemplates.chunked(if(resources.configuration.screenWidthDp<=840)2 else 3).forEach{group->val line=ui.row()
            group.forEachIndexed{index,(id,label)->
                val card=ui.col().apply{gravity=Gravity.CENTER;setPadding(dp(12),dp(12),dp(12),dp(12));background=ui.rounded(ui.color("#fafbfc"),15f,if(id==selected)ui.accent else ui.color("#d7dbe1"));contentDescription=label
                    setOnClickListener{options.forEach{(key,view)->view.background=ui.rounded(ui.color("#fafbfc"),15f,if(key==id)ui.accent else ui.color("#d7dbe1"))};onPick(id)}}
                card.addView(NotePreview(this).apply{page=NotePage.blank(id);elevation=dp(5).toFloat()},LinearLayout.LayoutParams(dp(72),dp(98)))
                card.addView(ui.text(label,13f,ui.ink,true),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(12)})
                options[id]=card;line.addView(card,LinearLayout.LayoutParams(0,dp(152),1f).apply{if(index>0)leftMargin=dp(12)})
            };grid.addView(line,LinearLayout.LayoutParams(-1,-2).apply{if(grid.childCount>0)topMargin=dp(12)})
        };return grid
    }
    private fun menuItem(parent:LinearLayout,label:String,icon:String="chevron-right",description:String="",action:()->Unit){
        val item=ui.row().apply{setPadding(dp(10),dp(6),dp(10),dp(6));minimumHeight=dp(54);background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ui.color("#edf2f7")),ui.rounded(Color.TRANSPARENT),ui.rounded(Color.WHITE));contentDescription=t(label);setOnClickListener{closeSheet();try{action()}catch(e:Exception){message(e.message?:"작업 실패")}}}
        item.addView(ui.iconButton(icon,label,ui.color("#436581"),38,38,21){item.performClick()}.apply{background=ui.rounded(ui.color("#edf2f7"),10f)})
        val copy=ui.col();copy.addView(ui.text(label,14f,if(label.contains("삭제"))ui.color("#d24646")else ui.ink,true));if(description.isNotBlank())copy.addView(ui.text(description,11f,ui.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
        item.addView(copy,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(9);rightMargin=dp(9)});item.addView(ui.icon("chevron-right",ui.color("#a2a6ad"),18))
        parent.addView(item,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
    }
    private fun prompt(title:String,value:String,action:(String)->Unit){
        val content=showSheet("",title,520);val input=ui.field(value).apply{setSingleLine(false);minLines=2;setPadding(dp(14),dp(12),dp(14),dp(12));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        content.addView(input,LinearLayout.LayoutParams(-1,-2));sheetActions(content){val text=input.text.toString();closeSheet();action(text)}
    }
    private fun choose(title:String,items:List<String>,action:(Int)->Unit){
        val content=showSheet("메뉴",title)
        items.forEachIndexed{i,label->menuItem(content,label,when{label.contains("삭제")||label.contains("휴지통")->"trash";label.contains("공유")->"share";label.contains("검색")->"search";label.contains("페이지")->"notebook";label.contains("복제")||label.contains("복사")->"copy";label.contains("설정")->"settings";label.contains("펜")->"pen";label.contains("내보내기")->"export";else->"chevron-right"}){action(i);if(inkView!=null)renderActiveDock()}}
    }
    private fun confirm(title:String,description:String,action:()->Unit){val content=showSheet("",title);content.addView(ui.text(description,14f,ui.muted));sheetActions(content){closeSheet();action()}}
    private fun slider(title:String,value:Int,min:Int,max:Int,action:(Int)->Unit){
        val content=showSheet("",title);var selected=value;val output=ui.text("$value",18f,ui.ink,true);content.addView(output)
        content.addView(range(value,min,max){selected=it;output.text="$it"},LinearLayout.LayoutParams(-1,dp(48)))
        sheetActions(content){closeSheet();action(selected);renderActiveDock()}
    }
    private fun navigateBack(){if(modal!=null){closeSheet();return};if(searchDrawer!=null){searchDocument();return};if(pageSidebar!=null){togglePages();return};if(busy){fileExport.cancel();return};if(document!=null){inkView?.finishContact();afterSaved{showLibrary()}}else afterSaved{finish()}}
    // API 33+ uses the native callback registered in onCreate; this is the legacy path.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy back callback for Android 12 and older")
    override fun onBackPressed(){navigateBack()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{
        if(event.isCtrlPressed){when(keyCode){KeyEvent.KEYCODE_Z->{if(event.isShiftPressed)inkView?.redo()else inkView?.undo();return true};KeyEvent.KEYCODE_Y->{inkView?.redo();return true};KeyEvent.KEYCODE_S->{inkView?.finishContact();return true}}}
        if(keyCode==KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY){inkView?.barrelKey(true);return true}
        if(keyCode==KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY){inkView?.undo();return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean{
        if(keyCode==KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY){inkView?.barrelKey(false);return true}
        return super.onKeyUp(keyCode,event)
    }
    override fun onSaveInstanceState(outState:Bundle){inkView?.finishContact();outState.putString("documentId",document?.id);outState.putString("pendingExport",fileExport.pendingPath());super.onSaveInstanceState(outState)}
    override fun onPause(){inkView?.finishContact();if(audio.recording)stopRecording();super.onPause()}
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);val index=inkView?.currentIndex?:0;afterSaved{if(document==null)showLibrary()else showEditor(index)}}
    override fun onDestroy(){ocrTask?.let{handler.removeCallbacks(it)};inkView?.close();migration?.close();audio.close();recognition.close();network.shutdown();super.onDestroy()}
    companion object{const val REQUEST_IMPORT=4180;const val REQUEST_AUDIO=4181}
}
