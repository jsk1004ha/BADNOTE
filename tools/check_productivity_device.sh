#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d -b crash -v threadtime > build/productivity-crash.log || true' EXIT
adb install -r build/productivity-apks/sideBySide/debug/app-sideBySide-debug.apk
adb install -r build/productivity-apks/androidTest/sideBySide/debug/app-sideBySide-debug-androidTest.apk
adb shell am instrument -w \
  com.inkforge.note5.debug.test/com.inkforge.notesstudio.ProductivityInstrumentation | tee build/productivity-device.log
python3 - <<'PY'
from pathlib import Path
log = Path('build/productivity-device.log').read_text()
assert log.count('INSTRUMENTATION_RESULT: passed=true') == 1, log
assert 'INSTRUMENTATION_RESULT: productivity=windows_shortcuts_pdf_colors_saf_restore_schedule_verified' in log, log
assert 'INSTRUMENTATION_CODE: 0' in log, log
PY
