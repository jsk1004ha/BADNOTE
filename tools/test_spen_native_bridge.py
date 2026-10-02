#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/java/com/inkforge/notesstudio/MainActivity.java"


def require(pattern: str, source: str, message: str) -> None:
    if not re.search(pattern, source, re.MULTILINE | re.DOTALL):
        raise AssertionError(message)


def main() -> None:
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")

    require(
        r"dispatchTouchEvent[\s\S]*?webView\.dispatchStylusTouchFromHost\s*\(\s*event\s*\)[\s\S]*?super\.dispatchTouchEvent",
        source,
        "Touch button state must be captured before Chromium handles the motion",
    )
    require(
        r"boolean buttonsChanged = stylusButtons\.update[\s\S]*?if \(!buttonsChanged &&",
        source,
        "Motion throttling must never discard a button press or release",
    )

    require(
        r"public\s+boolean\s+dispatchGenericMotionEvent\s*\(\s*MotionEvent\s+event\s*\)",
        source,
        "MainActivity must capture generic stylus button events before WebView routing",
    )
    require(
        r"dispatchGenericMotionEvent[\s\S]*?isStylusMotionEvent\s*\(\s*event\s*\)[\s\S]*?"
        r"webView\.dispatchStylusFromHost\s*\(\s*event\s*\)",
        source,
        "Activity-level generic motion must be forwarded directly to the ink WebView",
    )
    require(
        r"void\s+dispatchStylusFromHost\s*\(\s*MotionEvent\s+event\s*\)"
        r"[\s\S]*?dispatchStylus\s*\(\s*event\s*,\s*!stylusContactActive\s*\)",
        source,
        "Forwarded button events must preserve whether the pen is already touching the screen",
    )
    require(
        r"stylusContactActive\s*=\s*true[\s\S]*?ACTION_UP[\s\S]*?stylusContactActive\s*=\s*false",
        source,
        "InkWebView must track contact across the complete stylus motion set",
    )
    require(
        r"isStandardStylusButtonKey[\s\S]*?KEYCODE_STYLUS_BUTTON_PRIMARY"
        r"[\s\S]*?KEYCODE_STYLUS_BUTTON_SECONDARY[\s\S]*?KEYCODE_STYLUS_BUTTON_TERTIARY"
        r"[\s\S]*?KEYCODE_STYLUS_BUTTON_TAIL",
        source,
        "Standard stylus KeyEvents must work even when the device omits stylus source metadata",
    )

    print("S Pen native bridge source contract: PASS")


if __name__ == "__main__":
    main()
