package com.inkforge.notesstudio;

import android.view.MotionEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class StylusButtonStateTest {
    @Test public void moveOnlyButtonChangesAreImmediate() {
        StylusButtonState state = new StylusButtonState();
        state.update(MotionEvent.ACTION_DOWN, 0, 0);
        assertTrue(state.update(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_STYLUS_PRIMARY, 0));
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, state.getButtons());
        assertTrue(state.update(MotionEvent.ACTION_MOVE, 0, MotionEvent.BUTTON_STYLUS_PRIMARY));
        assertEquals(0, state.getButtons());
    }

    @Test public void releaseDoesNotRelatchTheActionButton() {
        StylusButtonState state = new StylusButtonState();
        state.update(MotionEvent.ACTION_BUTTON_PRESS, 0, MotionEvent.BUTTON_STYLUS_PRIMARY);
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, state.getButtons());
        state.update(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.BUTTON_STYLUS_PRIMARY, MotionEvent.BUTTON_STYLUS_PRIMARY);
        assertEquals(0, state.getButtons());
    }

    @Test public void releasingOneButtonPreservesTheOther() {
        StylusButtonState state = new StylusButtonState();
        state.update(MotionEvent.ACTION_BUTTON_PRESS, 96, MotionEvent.BUTTON_STYLUS_SECONDARY);
        state.update(MotionEvent.ACTION_BUTTON_RELEASE, 96, MotionEvent.BUTTON_STYLUS_PRIMARY);
        assertEquals(MotionEvent.BUTTON_STYLUS_SECONDARY, state.getButtons());
    }

    @Test public void tipLiftPreservesAHeldButtonButCancelClearsIt() {
        StylusButtonState state = new StylusButtonState();
        state.update(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_STYLUS_PRIMARY, 0);
        state.update(MotionEvent.ACTION_UP, MotionEvent.BUTTON_STYLUS_PRIMARY, 0);
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, state.getButtons());
        state.update(MotionEvent.ACTION_CANCEL, MotionEvent.BUTTON_STYLUS_PRIMARY, 0);
        assertEquals(0, state.getButtons());
        state.update(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_STYLUS_PRIMARY, 0);
        state.update(MotionEvent.ACTION_HOVER_EXIT, MotionEvent.BUTTON_STYLUS_PRIMARY, 0);
        assertEquals(0, state.getButtons());
    }
}
