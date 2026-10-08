package com.inkforge.notesstudio

import android.view.MotionEvent

class StylusButtonState{
    private var buttons=0
    fun getButtons()=buttons
    fun update(action:Int,rawButtons:Int,actionButton:Int):Boolean{
        val previous=buttons;val mask=MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        buttons=rawButtons and mask
        when(action){
            MotionEvent.ACTION_BUTTON_PRESS->buttons=buttons or (actionButton and mask)
            MotionEvent.ACTION_BUTTON_RELEASE->buttons=buttons and (actionButton and mask).inv()
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_HOVER_EXIT->buttons=0
        }
        return previous!=buttons
    }
}
