package com.inkforge.notesstudio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class TextTransformTest {
    private fun anchor(x:Float,y:Float,w:Float,h:Float,angle:Float,ax:Float,ay:Float):TextTransform.Point {
        val r=TextTransform.rotated((ax-.5f)*w,(ay-.5f)*h,angle)
        return TextTransform.Point(x+w/2+r.x,y+h/2+r.y)
    }
    @Test fun rotatedReflowKeepsOppositeTopCornerFixed() {
        for(angle in listOf(0f,.7f,1.57f,-1.1f))for(ax in listOf(0f,1f)) {
            val before=anchor(100f,200f,300f,80f,angle,ax,0f)
            val p=TextTransform.origin(100f,200f,300f,80f,170f,160f,angle,ax,0f)
            val after=anchor(p.x,p.y,170f,160f,angle,ax,0f)
            assertEquals(before.x,after.x,.001f);assertEquals(before.y,after.y,.001f)
        }
    }
    @Test fun allFourScalingCornersPreserveTheirOppositeAnchor() {
        for(ax in listOf(0f,1f))for(ay in listOf(0f,1f)) {
            val before=anchor(100f,200f,300f,80f,.6f,ax,ay)
            val p=TextTransform.origin(100f,200f,300f,80f,450f,120f,.6f,ax,ay)
            val after=anchor(p.x,p.y,450f,120f,.6f,ax,ay)
            assertEquals(before.x,after.x,.001f);assertEquals(before.y,after.y,.001f)
        }
    }
    @Test fun verticalAndRotatedDiagonalDragsScaleProportionally() {
        assertEquals(1.5f,TextTransform.scale(100f,100f,0f,100f,0f,false,false),.001f)
        val delta=TextTransform.rotated(150f,40f,(PI/3).toFloat())
        assertEquals(1.5f,TextTransform.scale(300f,80f,delta.x,delta.y,(PI/3).toFloat(),false,false),.001f)
    }
}
