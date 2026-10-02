package com.inkforge.notesstudio;

import android.view.MotionEvent;

/** Tracks the current barrel buttons, including devices that only report MOVE changes. */
final class StylusButtonState {
    private static final int MASK = MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY;
    private int buttons;

    int getButtons() {
        return buttons;
    }

    boolean update(int action, int rawButtons, int actionButton) {
        int previous = buttons;
        int current = rawButtons & MASK;
        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            current |= actionButton & MASK;
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
            current &= ~(actionButton & MASK);
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_HOVER_EXIT) {
            current = 0;
        }
        // getActionButton is undefined for MOVE/DOWN/UP. Never use it as a latch.
        buttons = current;
        return previous != buttons;
    }
}
