# 이전 웹 구현

web/는 3.3.30 웹 앱의 보관 소스입니다. 현재 Android APK에는 포함하지 않습니다. 현재 브러시 산술·래스터 비교와 아이콘 원본 추출에 사용하므로 유지합니다.

현재 도구 tools/capture_brush_reference.mjs, tools/render_brush_reference.py, tools/sync_native_icons.py와 웹 호환 검사는 이 경로를 사용합니다. tools/update_translations.py는 과거 웹 사전용 도구입니다. 현재 네이티브 번역 사전은 android/app/src/nativeAssets/locales/에 있습니다.

중복 Android 웹 자산·미사용 Tesseract 모델과 폐기 도구는 프로젝트 루트의 _delete_after_review/로 옮겼습니다.
