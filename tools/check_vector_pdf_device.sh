#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d -b crash -v threadtime > build/vector-pdf-crash.log || true' EXIT
adb install -r build/vector-pdf-apks/sideBySide/debug/app-sideBySide-debug.apk
adb install -r build/vector-pdf-apks/androidTest/sideBySide/debug/app-sideBySide-debug-androidTest.apk
adb shell am instrument -w \
  com.inkforge.note5.debug.test/com.inkforge.notesstudio.VectorPdfInstrumentation | tee build/vector-pdf-device.log
python3 - <<'PY'
from pathlib import Path
log = Path('build/vector-pdf-device.log').read_text()
assert log.count('INSTRUMENTATION_RESULT: passed=true') == 1, log
assert 'INSTRUMENTATION_RESULT: vectorPdf=native_pages_brushes_fonts_background_budget_cancel_verified' in log, log
assert 'INSTRUMENTATION_CODE: 0' in log, log
PY
