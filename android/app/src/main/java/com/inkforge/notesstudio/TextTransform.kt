package com.inkforge.notesstudio

import kotlin.math.*

/** Text rectangles rotate around their centre. Keep the opposite handle fixed during reflow. */
internal object TextTransform {
    data class Point(val x: Float, val y: Float)
    fun rotated(x: Float, y: Float, angle: Float) = Point(x*cos(angle)-y*sin(angle), x*sin(angle)+y*cos(angle))
    fun origin(x:Float,y:Float,w:Float,h:Float,newW:Float,newH:Float,angle:Float,anchorX:Float,anchorY:Float):Point {
        val old=rotated((anchorX-.5f)*w,(anchorY-.5f)*h,angle)
        val next=rotated((anchorX-.5f)*newW,(anchorY-.5f)*newH,angle)
        return Point(x+w/2+old.x-next.x-newW/2,y+h/2+old.y-next.y-newH/2)
    }
    fun scale(w:Float,h:Float,dx:Float,dy:Float,angle:Float,left:Boolean,top:Boolean):Float {
        val local=rotated(dx,dy,-angle)
        val vx=if(left)-w else w; val vy=if(top)-h else h
        return (1+(local.x*vx+local.y*vy)/(w*w+h*h).coerceAtLeast(1f)).coerceIn(.1f,8f)
    }
}
