# Android 빌드 — 4.2.2

현재 버전은 **4.2.2 / versionCode 433**입니다. 실제 값은 [app/build.gradle](app/build.gradle)에서 읽습니다. 프로젝트 루트에서 실행하며 .worktrees/ 사본을 빌드하지 않습니다.

## 요구 사항

- Java 17 이상
- Android SDK Platform 36 / Build Tools 36.1.0
- NDK 28.2.13676358 / CMake 3.22.1
- 포함된 Gradle wrapper 8.14.5

android/local.properties 또는 ANDROID_SDK_ROOT로 SDK 경로를 설정합니다. PDFium은 tools/pdfium.lock.json의 고정 배포본을 tools/prepare_pdfium.py가 준비하고 해시를 확인합니다. 최초 빌드에는 도구·의존성 다운로드가 필요할 수 있습니다.

## 검사와 분리된 Debug 앱

프로젝트 루트에서:

    python tools/test_native.py

JVM 검사와 Update Debug APK를 빌드합니다. 최종 설치 파일은 build/apk/bad-note-Android-4.2.2-Debug.apk이며 패키지는 com.inkforge.note4.debug입니다. 기기에 설치하려면 명시적인 테스트 기기를 지정합니다.

    python tools/test_native.py --device <adb-serial> --adb <adb-path>

이 명령은 테스트 APK를 설치하고 계측을 실행합니다. OCR 등 개별 계측은 tools/run_native_validation.py instrument의 --instrument-mode와 --device 인자를 사용합니다.

## 로컬 release

android/local-signing.properties.example을 참고해 android/local-signing.properties와 키스토어를 준비합니다. 비밀 값은 저장소에 포함하지 않습니다.

    python tools/build_apk.py --variant both

정식 배포판과 Debug 빌드는 새 OCR 레이아웃과 혼합 OCR 소스를 사용합니다. 산출물 이름은 Gradle의 versionName에서 정합니다.

- build/apk/bad-note-Android-4.2.2-Update.apk — com.inkforge.note4
- build/apk/bad-note-Android-4.2.2-SideBySide.apk — com.inkforge.note5

직접 Gradle을 사용할 경우에도 성공한 assemble 결과를 루트 build/apk/에 자동으로 복사합니다. SideBySide Debug는 -SideBySide-Debug.apk, 계측용 테스트 APK는 build/apk/tests/의 -AndroidTest.apk로 구분합니다. Android 모듈의 기본 출력은 빌드·설치 도구가 계속 사용합니다.

    android/gradlew.bat -p android :app:assembleUpdateRelease :app:assembleSideBySideRelease

공개 APK를 업데이트하려면 같은 패키지 ID와 공개 배포 인증서가 필요합니다. 로컬 검증에는 분리된 Debug 앱을 사용합니다.

## GitHub 릴리스 검증

Native Android regressions 워크플로는 도구·JVM·lint·에뮬레이터 검사를 실행합니다. 벡터 PDF와 태블릿 기능의 API 23·35 계측은 각각의 워크플로에서 실행합니다. 서명된 배포용 APK는 APK 확인 절차로 검증한 뒤 GitHub Releases에 올립니다.

## APK 확인

    python tools/verify_apk.py build/apk/bad-note-Android-4.2.2-Update.apk --build-tools <SDK>/build-tools/36.1.0 --expected-package com.inkforge.note4 --expected-version-code 433 --expected-version-name 4.2.2 --expected-certificate 45bc9430d2c32457c5d944561176a7ec861b29f6bcb1b4244806307c9f3d0847 --pdfium-manifest build/dependencies/pdfium-8086/verification.json

현재 네이티브 구조와 자료 구분은 [네이티브 안내](../docs/NATIVE-KO.md), [문서 안내](../docs/README.md)를 참고하십시오.
