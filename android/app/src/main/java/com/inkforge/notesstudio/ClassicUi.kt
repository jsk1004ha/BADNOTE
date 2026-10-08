package com.inkforge.notesstudio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.graphics.PathParser
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.roundToInt

/** Geometry, colors and spacing come from the existing 3.3.30 UI. No web runtime. */
class ClassicUi(val context:Context, val translate:(String)->String = {it}) {
    val density get()=context.resources.displayMetrics.density
    fun dp(value:Float)=(value*density).roundToInt()
    fun dp(value:Int)=dp(value.toFloat())
    fun color(value:String)=Color.parseColor(value)
    val ink=color("#202228")
    val muted=color("#777b84")
    val blue=color("#2f5688")
    val accent=color("#1397ed")
    private val icons:JSONObject by lazy { context.assets.open("icons.json").bufferedReader().use{JSONObject(it.readText())} }
    fun rounded(fill:Int,radius:Float=12f,stroke:Int=Color.TRANSPARENT,width:Float=1f)=GradientDrawable().apply{
        setColor(fill);cornerRadius=radius*density;if(stroke!=Color.TRANSPARENT)setStroke(dp(width).coerceAtLeast(1),stroke)
    }
    fun col()=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    fun row()=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    fun text(value:String,size:Float=14f,tint:Int=ink,bold:Boolean=false)=TextView(context).apply{
        text=translate(value);textSize=size;setTextColor(tint);includeFontPadding=false
        typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL);gravity=Gravity.CENTER_VERTICAL
    }
    fun icon(name:String,tint:Int=ink,size:Int=25)=ImageView(context).apply{
        setImageDrawable(OriginalIcon(icons.optString(name,icons.optString("info")),tint));scaleType=ImageView.ScaleType.FIT_CENTER
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun iconButton(name:String,label:String,tint:Int=ink,width:Int=44,height:Int=width,size:Int=25,
                   selected:Boolean=false,selectedFill:Int=color("#d9e4ef"),selectedTint:Int=ink,action:()->Unit):FrameLayout{
        val view=FrameLayout(context).apply{
            layoutParams=LinearLayout.LayoutParams(dp(width),dp(height));contentDescription=translate(label);isClickable=true;isFocusable=true
            val states=StateListDrawable().apply{
                addState(intArrayOf(android.R.attr.state_selected),rounded(selectedFill,if(width>=48)15f else 12f))
                addState(intArrayOf(),rounded(Color.TRANSPARENT))
            }
            background=RippleDrawable(ColorStateList.valueOf(0x24777777),states,rounded(Color.WHITE))
            isSelected=selected;setOnClickListener{action()}
        }
        view.addView(icon(name,if(selected)selectedTint else tint,size),FrameLayout.LayoutParams(dp(size),dp(size),Gravity.CENTER))
        return view
    }
    fun button(label:String,fill:Int=color("#f0f2f5"),tint:Int=ink,height:Int=42,radius:Float=12f,icon:String?=null,action:()->Unit)=row().apply{
        gravity=Gravity.CENTER;setPadding(dp(16),0,dp(16),0);minimumHeight=dp(height)
        background=RippleDrawable(ColorStateList.valueOf(0x24777777),rounded(fill,radius),rounded(Color.WHITE,radius))
        isClickable=true;isFocusable=true;contentDescription=translate(label);setOnClickListener{action()}
        if(icon!=null)addView(this@ClassicUi.icon(icon,tint,19),LinearLayout.LayoutParams(dp(19),dp(19)).apply{rightMargin=dp(8)})
        addView(text(label,14f,tint,true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
        layoutParams=LinearLayout.LayoutParams(-2,dp(height))
    }
    fun divider(tint:Int=color("#21ffffff"),height:Int=32)=View(context).apply{
        setBackgroundColor(tint);layoutParams=LinearLayout.LayoutParams(dp(1),dp(height)).apply{setMargins(dp(5),0,dp(5),0)}
    }
    fun line(tint:Int=color("#21201f26"))=View(context).apply{setBackgroundColor(tint);layoutParams=LinearLayout.LayoutParams(-1,dp(1))}
    fun space(height:Int)=View(context).apply{layoutParams=LinearLayout.LayoutParams(1,dp(height))}
    fun field(value:String="",hint:String="",height:Int=48)=EditText(context).apply{
        setText(value);this.hint=translate(hint);setTextColor(ink);setHintTextColor(muted);textSize=16f;setSingleLine()
        setPadding(dp(14),0,dp(14),0);background=rounded(color("#f8f9fb"),12f,color("#d7dbe1"))
        layoutParams=LinearLayout.LayoutParams(-1,dp(height));setSelectAllOnFocus(false)
    }
    fun swatch(value:String,selected:Boolean=false,action:()->Unit)=FrameLayout(context).apply{
        layoutParams=LinearLayout.LayoutParams(dp(39),dp(39));contentDescription=value;isClickable=true;setOnClickListener{action()}
        background=rounded(Color.TRANSPARENT,50f,if(selected)Color.WHITE else Color.TRANSPARENT,2f)
        addView(View(context).apply{background=rounded(runCatching{color(value)}.getOrDefault(Color.BLACK),50f,color("#26000000"))},FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER))
    }
}

/** The paths are the original SVG paths, painted with the same 24x24 viewport. */
private class OriginalIcon(svg:String,private val tint:Int):Drawable(){
    private data class Part(val path:Path,val filled:Boolean,val stroke:Boolean,val dashed:Boolean)
    private val parts=mutableListOf<Part>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    init{
        val parser=Xml.newPullParser();parser.setInput(StringReader("<svg>$svg</svg>"))
        while(parser.next()!=XmlPullParser.END_DOCUMENT)if(parser.eventType==XmlPullParser.START_TAG){
            fun attr(name:String)=parser.getAttributeValue(null,name)
            fun f(name:String,default:Float=0f)=attr(name)?.toFloatOrNull()?:default
            val path=when(parser.name){
                "path"->PathParser.createPathFromPathData(attr("d")?:"")
                "rect"->Path().apply{addRoundRect(RectF(f("x"),f("y"),f("x")+f("width"),f("y")+f("height")),f("rx"),f("ry",f("rx")),Path.Direction.CW)}
                "circle"->Path().apply{addCircle(f("cx"),f("cy"),f("r"),Path.Direction.CW)}
                "ellipse"->Path().apply{addOval(RectF(f("cx")-f("rx"),f("cy")-f("ry"),f("cx")+f("rx"),f("cy")+f("ry")),Path.Direction.CW)}
                else->null
            }
            if(path!=null)parts+=Part(path,attr("fill")=="currentColor",attr("stroke")!="none",attr("stroke-dasharray")!=null)
        }
    }
    override fun draw(canvas:Canvas){
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        paint.color=tint
        parts.forEach{part->
            if(part.filled){paint.style=Paint.Style.FILL;paint.pathEffect=null;canvas.drawPath(part.path,paint)}
            if(part.stroke){paint.style=Paint.Style.STROKE;paint.pathEffect=if(part.dashed)DashPathEffect(floatArrayOf(3f,2f),0f)else null;canvas.drawPath(part.path,paint)}
        };canvas.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

class NotePreview(context:Context):View(context){
    init{setBackgroundColor(Color.WHITE)}
    var loadBitmap:(()->Bitmap?)?=null
    var page:NotePage?=null;set(value){field=value;invalidate()}
    var coverColor:Int?=null;set(value){field=value;invalidate()}
    private val renderer=NoteRenderer()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var bitmap:Bitmap?=null;set(value){field=value;invalidate()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);canvas.drawColor(Color.WHITE)
        val bitmap=bitmap?:loadBitmap?.invoke()
        if(bitmap!=null)canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)
        else page?.let{p->canvas.save();canvas.scale(width/p.width,height/p.height);renderer.template(canvas,p);p.objects.forEach{renderer.draw(canvas,it)};canvas.restore()}
        coverColor?.let{paint.color=it;canvas.drawRect(0f,0f,5*resources.displayMetrics.density,height.toFloat(),paint)}
    }
}

/** Native saturation/value surface and hue rail used by the original Color Mixer. */
class ClassicSpectrum(context:Context,private val hsv:FloatArray,private val changed:()->Unit):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription="채도와 밝기";isClickable=true;isFocusable=true}
    override fun onDraw(canvas:Canvas){
        val path=Path().apply{addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),17*resources.displayMetrics.density,17*resources.displayMetrics.density,Path.Direction.CW)}
        canvas.save();canvas.clipPath(path)
        paint.shader=LinearGradient(0f,0f,width.toFloat(),0f,Color.WHITE,Color.HSVToColor(floatArrayOf(hsv[0],1f,1f)),Shader.TileMode.CLAMP);canvas.drawPaint(paint)
        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP);canvas.drawPaint(paint);paint.shader=null
        paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=3*resources.displayMetrics.density;canvas.drawCircle(hsv[1]*width,(1-hsv[2])*height,10*resources.displayMetrics.density,paint);paint.style=Paint.Style.FILL;canvas.restore()
    }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
        if(event.actionMasked in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP)){parent.requestDisallowInterceptTouchEvent(true);hsv[1]=(event.x/width).coerceIn(0f,1f);hsv[2]=(1-event.y/height).coerceIn(0f,1f);invalidate();changed();if(event.actionMasked==android.view.MotionEvent.ACTION_UP)performClick();return true};return super.onTouchEvent(event)
    }
    override fun performClick():Boolean{super.performClick();return true}
}
class ClassicHue(context:Context,private val hsv:FloatArray,private val changed:()->Unit):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription="색조";isClickable=true;isFocusable=true}
    override fun onDraw(canvas:Canvas){
        val d=resources.displayMetrics.density;val x=12*d;val span=width-24*d
        paint.shader=LinearGradient(x,0f,width-x,0f,intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(x,height/2f-5.5f*d,width-x,height/2f+5.5f*d),6*d,6*d,paint);paint.shader=null
        val px=x+hsv[0]/359f*span;paint.color=Color.HSVToColor(floatArrayOf(hsv[0],1f,1f));canvas.drawCircle(px,height/2f,11*d,paint);paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=3*d;canvas.drawCircle(px,height/2f,11*d,paint);paint.style=Paint.Style.FILL
    }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean{if(event.actionMasked in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP)){parent.requestDisallowInterceptTouchEvent(true);val d=resources.displayMetrics.density;hsv[0]=((event.x-12*d)/(width-24*d)*359).coerceIn(0f,359f);invalidate();changed();if(event.actionMasked==android.view.MotionEvent.ACTION_UP)performClick();return true};return super.onTouchEvent(event)}
    override fun performClick():Boolean{super.performClick();return true}
}
