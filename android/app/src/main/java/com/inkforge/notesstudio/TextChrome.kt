package com.inkforge.notesstudio

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import kotlin.math.*

/** Small vector controls keep the text bar consistent across Android font/emoji sets. */
internal class TextIcon(context:Context,var symbol:String):View(context) {
    var ink=0xff242426.toInt(); var swatch=Color.BLACK
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable=true;isFocusable=false }
    override fun onDraw(c:Canvas) {
        val s=resources.displayMetrics.density
        c.save();c.translate(width/2f-12*s,height/2f-12*s);c.scale(s,s)
        pen.color=ink;pen.strokeWidth=1.8f;pen.style=Paint.Style.STROKE;pen.strokeCap=Paint.Cap.ROUND;pen.strokeJoin=Paint.Join.ROUND
        fun line(a:Float,b:Float,d:Float,e:Float)=c.drawLine(a,b,d,e,pen)
        fun label(text:String,bold:Boolean=false,italic:Boolean=false) { pen.style=Paint.Style.FILL;pen.textSize=20f;pen.textAlign=Paint.Align.CENTER;pen.typeface=Typeface.create("sans-serif",TextBoxLayout.fontStyle(bold,italic));c.drawText(text,12f,19f,pen) }
        when(symbol) {
            "color","swatch"->{pen.style=Paint.Style.FILL;pen.color=swatch;c.drawCircle(12f,12f,11f,pen);pen.color=if(androidx.core.graphics.ColorUtils.calculateLuminance(swatch)>.6)Color.BLACK else Color.WHITE;pen.style=Paint.Style.STROKE;if(symbol=="color"){line(9f,11f,12f,14f);line(12f,14f,15f,11f)}else if(isSelected){line(6f,12f,10f,16f);line(10f,16f,18f,7f)}else if(androidx.core.graphics.ColorUtils.calculateLuminance(swatch)>.85){pen.color=0xffccccd0.toInt();pen.strokeWidth=.7f;c.drawCircle(12f,12f,11f,pen)}}
            "align-left","align-center","align-right"->{for(i in 0..3){val short=i%2==1;val x=if(!short||symbol=="align-left")3f else if(symbol=="align-right")9f else 6f;line(x,5f+i*5,x+if(short)12f else 18f,5f+i*5)}}
            "list","numbered","indent","outdent"->{for(i in 0..2){val y=6f+i*6;line(9f,y,22f,y);if(symbol=="list"){pen.style=Paint.Style.FILL;c.drawCircle(3f,y,1.5f,pen);pen.style=Paint.Style.STROKE}else if(symbol=="numbered"){pen.style=Paint.Style.FILL;pen.textSize=7f;pen.textAlign=Paint.Align.LEFT;c.drawText("${i+1}",1f,y+2,pen);pen.style=Paint.Style.STROKE}};if(symbol=="indent"||symbol=="outdent"){val x=if(symbol=="indent")2f else 6f;val end=if(symbol=="indent")6f else 2f;line(x,8f,end,12f);line(end,12f,x,16f)}}
            "spacing"->{line(7f,4f,7f,21f);line(4f,7f,7f,4f);line(7f,4f,10f,7f);line(4f,18f,7f,21f);line(7f,21f,10f,18f);for(i in 0..2)line(14f,6f+i*6,22f,6f+i*6)}
            "style"->{pen.style=Paint.Style.FILL;pen.color=swatch;c.drawRoundRect(3f,3f,21f,21f,3f,3f,pen);pen.color=ink;pen.style=Paint.Style.STROKE;c.drawRoundRect(3f,3f,21f,21f,3f,3f,pen);if(Color.alpha(swatch)==0)line(6f,6f,18f,18f)}
            "more"->{c.drawCircle(12f,12f,10f,pen);pen.style=Paint.Style.FILL;for(i in 0..2)c.drawCircle(7f+i*5,12f,1.4f,pen)}
            "trash"->{c.drawRoundRect(6f,7f,19f,22f,2f,2f,pen);line(4f,5f,21f,5f);line(9f,2f,16f,2f);line(10f,10f,10f,18f);line(15f,10f,15f,18f)}
            "copy","duplicate"->{c.drawRoundRect(3f,3f,16f,17f,2f,2f,pen);pen.style=Paint.Style.FILL;pen.color=0xfff4f4f6.toInt();c.drawRoundRect(8f,8f,21f,22f,2f,2f,pen);pen.style=Paint.Style.STROKE;pen.color=ink;c.drawRoundRect(8f,8f,21f,22f,2f,2f,pen);if(symbol=="duplicate"){line(11f,15f,18f,15f);line(14.5f,11.5f,14.5f,18.5f)}}
            "paste"->{c.drawRoundRect(4f,5f,20f,23f,2f,2f,pen);c.drawRoundRect(8f,2f,16f,7f,1.5f,1.5f,pen);line(8f,12f,16f,12f);line(8f,16f,16f,16f)}
            "cut"->{c.drawCircle(6f,18f,3.5f,pen);c.drawCircle(18f,18f,3.5f,pen);line(8f,15f,19f,2f);line(16f,15f,5f,2f)}
            "pin"->{line(7f,3f,18f,3f);line(9f,3f,9f,10f);line(16f,3f,16f,10f);line(9f,10f,5f,15f);line(16f,10f,20f,15f);line(5f,15f,20f,15f);line(12.5f,15f,12.5f,23f)}
            "default"->{label("T");pen.style=Paint.Style.FILL;pen.color=0xff579cf3.toInt();c.drawPath(Path().apply{moveTo(20f,9f);cubicTo(9f,2f,17f,-2f,20f,2f);cubicTo(24f,-2f,29f,2f,20f,9f);close()},pen)}
            "front","back"->{c.drawRect(3f,8f,17f,22f,pen);c.drawRect(8f,3f,22f,17f,pen);line(12f,18f,12f,6f);val y=if(symbol=="front")6f else 18f;val d=if(symbol=="front")4 else -4;line(8f,y+d,12f,y);line(12f,y,16f,y+d)}
            "check"->{line(4f,12f,10f,18f);line(10f,18f,21f,5f)}
            "chevron"->{line(9f,6f,15f,12f);line(15f,12f,9f,18f)}
            "bold"->label("B",true)
            "italic"->label("I",false,true)
            "underline"->{label("U");line(5f,22f,19f,22f)}
            "strike"->{label("S");line(3f,12f,21f,12f)}
            "format"->{pen.style=Paint.Style.FILL;pen.textSize=18f;pen.typeface=Typeface.DEFAULT_BOLD;pen.textAlign=Paint.Align.LEFT;c.drawText("B",0f,19f,pen);pen.typeface=Typeface.create("serif",Typeface.ITALIC);c.drawText("I",14f,19f,pen)}
            else->label(symbol)
        }
        c.restore()
    }
}

/** Rounded native popover; use a side arrow when the keyboard leaves little vertical room. */
internal class TextPopover(context:Context,below:Boolean,arrow:Float):FrameLayout(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xfff2f2f7.toInt()}
    private val d=resources.displayMetrics.density
    private var edge=if(below)"top" else "bottom"
    private var offset=arrow
    init {setWillNotDraw(false);place(edge,arrow)}
    fun place(edge:String,offset:Float) {
        this.edge=edge;this.offset=offset
        val tip=(9*d).toInt()
        setPadding(if(edge=="left")tip else 0,if(edge=="top")tip else 0,if(edge=="right")tip else 0,if(edge=="bottom")tip else 0);invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        val tip=9*d;val left=paddingLeft.toFloat();val top=paddingTop.toFloat()
        val right=width-paddingRight.toFloat();val bottom=height-paddingBottom.toFloat()
        canvas.drawRoundRect(left,top,right,bottom,15*d,15*d,paint)
        val path=Path()
        if(edge=="top"||edge=="bottom") {
            val x=offset.coerceIn(22*d,width-22*d);val y=if(edge=="top")top else bottom
            path.moveTo(x-tip,y);path.lineTo(x,if(edge=="top")0f else height.toFloat());path.lineTo(x+tip,y)
        } else {
            val y=offset.coerceIn(22*d,height-22*d);val x=if(edge=="left")left else right
            path.moveTo(x,y-tip);path.lineTo(if(edge=="left")0f else width.toFloat(),y);path.lineTo(x,y+tip)
        }
        path.close();canvas.drawPath(path,paint)
    }
}

/** Hue ring and saturation/value disc, backed by the text tool's existing ARGB colour property. */
internal class TextColorWheel(context:Context,initial:Int,private val changed:(String)->Unit):View(context) {
    private val hsv=FloatArray(3).also{Color.colorToHSV(initial,it)}
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private val d=resources.displayMetrics.density
    private var hueGesture=false
    init {isClickable=true;contentDescription="사용자 지정 색상 선택"}
    override fun onDraw(canvas:Canvas) {
        val cx=width/2f;val cy=height/2f;val r=min(width,height)/2f-10*d;val inner=r*.72f
        pen.style=Paint.Style.STROKE;pen.strokeWidth=12*d
        pen.shader=SweepGradient(cx,cy,intArrayOf(Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED),null)
        canvas.drawCircle(cx,cy,r,pen);pen.shader=null
        val saved=canvas.save();canvas.clipPath(Path().apply{addCircle(cx,cy,inner,Path.Direction.CW)})
        pen.style=Paint.Style.FILL;pen.color=Color.HSVToColor(floatArrayOf(hsv[0],1f,1f));canvas.drawCircle(cx,cy,inner,pen)
        pen.shader=LinearGradient(cx-inner*.7f,cy,cx+inner*.7f,cy,Color.WHITE,Color.TRANSPARENT,Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,inner,pen)
        pen.shader=LinearGradient(cx,cy-inner*.7f,cx,cy+inner*.7f,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,inner,pen)
        pen.shader=null;canvas.restoreToCount(saved)
        fun cursor(x:Float,y:Float,radius:Float){pen.style=Paint.Style.STROKE;pen.color=0xff85858b.toInt();pen.strokeWidth=4*d;canvas.drawCircle(x,y,radius,pen);pen.color=Color.WHITE;pen.strokeWidth=2.5f*d;canvas.drawCircle(x,y,radius,pen)}
        val angle=hsv[0]*Math.PI.toFloat()/180
        cursor(cx+r*cos(angle),cy+r*sin(angle),7*d)
        cursor(cx+inner*.7f*(hsv[1]*2-1),cy+inner*.7f*(1-hsv[2]*2),6*d)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val cx=width/2f;val cy=height/2f;val r=min(width,height)/2f-10*d;val inner=r*.72f
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{hueGesture=hypot(event.x-cx,event.y-cy)>inner;parent.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_UP->{parent.requestDisallowInterceptTouchEvent(false);performClick()}
            MotionEvent.ACTION_CANCEL->{parent.requestDisallowInterceptTouchEvent(false);return true}
        }
        if(hueGesture)hsv[0]=((atan2(event.y-cy,event.x-cx)*180/Math.PI+360)%360).toFloat()
        else {hsv[1]=((event.x-cx)/(inner*1.4f)+.5f).coerceIn(0f,1f);hsv[2]=(.5f-(event.y-cy)/(inner*1.4f)).coerceIn(0f,1f)}
        changed(String.format("#%06X",0xffffff and Color.HSVToColor(hsv)));invalidate();return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
