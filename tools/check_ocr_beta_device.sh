#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/ocr-beta
adb install -r android/app/build/outputs/apk/sideBySide/debug/app-sideBySide-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/sideBySide/debug/app-sideBySide-debug-androidTest.apk
for mode in ocrOnly s5cOnly; do
  adb shell am instrument -w -e "$mode" true \
    com.inkforge.note5.debug.test/com.inkforge.notesstudio.NativeSmokeInstrumentation \
    | tee "build/ocr-beta/$mode.log"
done
PYTHONPATH=tools python3 - <<'PY'
from pathlib import Path
from run_native_validation import require_instrument_success
for mode, marker in (
    ('ocrOnly','INSTRUMENTATION_RESULT: ocrTasks=real_task_permit_timeout_recovery_failure_verified'),
    ('s5cOnly','INSTRUMENTATION_RESULT: s5c=raw_samples_ocr_cancel_correction_model_generation_verified'),
):
    require_instrument_success(Path(f'build/ocr-beta/{mode}.log').read_text(),marker)
PY
