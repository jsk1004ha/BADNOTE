# Android 빌드 — 4.1.4 Beta 1

현재 버전은 **4.1.4-beta.1 / versionCode 421**입니다. 실제 값은 [app/build.gradle](app/build.gradle)에서 읽습니다. 프로젝트 루트에서 실행하며 .worktrees/ 사본을 빌드하지 않습니다.

## 요구 사항

- Java 17 이상
- Android SDK Platform 36 / Build Tools 36.1.0
- NDK 28.2.13676358 / CMake 3.22.1
- 포함된 Gradle wrapper 8.14.5

android/local.properties 또는 ANDROID_SDK_ROOT로 SDK 경로를 설정합니다. PDFium은 tools/pdfium.lock.json의 고정 배포본을 tools/prepare_pdfium.py가 준비하고 해시를 확인합니다. 최초 빌드에는 도구·의존성 다운로드가 필요할 수 있습니다.

## 검사와 분리된 Debug 앱

프로젝트 루트에서:

    python tools/test_native.py

JVM 검사와 Update Debug APK를 빌드합니다. 최종 설치 파일은 build/apk/bad-note-Android-4.1.4-beta.1-Debug.apk이며 패키지는 com.inkforge.note4.debug입니다. 기기에 설치하려면 명시적인 테스트 기기를 지정합니다.

    python tools/test_native.py --device <adb-serial> --adb <adb-path>

이 명령은 테스트 APK를 설치하고 계측을 실행합니다. OCR 등 개별 계측은 tools/run_native_validation.py instrument의 --instrument-mode와 --device 인자를 사용합니다.

## 로컬 release

android/local-signing.properties.example을 참고해 android/local-signing.properties와 키스토어를 준비합니다. 비밀 값은 저장소에 포함하지 않습니다.

    python tools/build_apk.py --variant both --ocr-beta

--ocr-beta는 release의 badnoteOcrBeta 플래그를 켭니다. Debug는 두 OCR 플래그가 기본으로 켜집니다. 산출물 이름은 Gradle의 versionName에서 정합니다.

- build/apk/bad-note-Android-4.1.4-beta.1-Update.apk — com.inkforge.note4
- build/apk/bad-note-Android-4.1.4-beta.1-SideBySide.apk — com.inkforge.note5

직접 Gradle을 사용할 경우에도 성공한 assemble 결과를 루트 build/apk/에 자동으로 복사합니다. SideBySide Debug는 -SideBySide-Debug.apk, 계측용 테스트 APK는 build/apk/tests/의 -AndroidTest.apk로 구분합니다. Android 모듈의 기본 출력은 빌드·설치 도구가 계속 사용합니다.

    android/gradlew.bat -p android -PbadnoteOcrBeta=true :app:assembleUpdateRelease :app:assembleSideBySideRelease

현재 로컬 인증서는 공개 Beta 2 인증서와 다릅니다. 공개 설치판 위의 업데이트에는 원래 배포 키가 필요합니다. 분리된 Debug 앱으로 검증할 수 있습니다. 자세한 설치 범위는 [현재 수정판 안내](../docs/RELEASE-4.1.4-BETA.1-KO.md)를 참고하십시오.

## GitHub 베타 게시

`Native checks and beta release` 워크플로는 PR에서 네이티브·도구·기기 검사를 실행합니다. 원본 저장소 `jsk1004ha/BADNOTE`의 `codex/release-4.1.4-beta.1` 브랜치 push에서만 서명된 공개 베타 게시까지 진행합니다. 포크와 PR에서는 배포 키를 사용하거나 릴리스를 게시하지 않습니다.

저장소 관리자가 검토한 PR 커밋을 원본 저장소의 해당 브랜치로 가져오면 같은 커밋의 검사 후 배포합니다. 기존 `BADNOTE_KEYSTORE_BASE64`, `BADNOTE_KEYSTORE_PASSWORD`, `BADNOTE_KEY_ALIAS`, `BADNOTE_KEY_PASSWORD` Actions secrets가 필요합니다. `prepare_release_signing.py`가 공개 배포 인증서와 일치를 확인하며 누락·불일치 때 로컬 키로 대체하지 않습니다.

버전·패키지·서명·16KiB 정렬·PDFium 검증을 통과한 Update·SideBySide 두 APK를 릴리스 초안에 올린 뒤 prerelease로 공개합니다. 제목은 `BADNOTE 4.1.4 Beta 1`, 태그는 `v4.1.4-beta.1`, 본문은 [릴리스 문서](../docs/RELEASE-4.1.4-BETA.1-KO.md)를 사용하며 정식 Latest는 바꾸지 않습니다. 기존 태그가 다른 커밋을 가리키면 덮어쓰지 않습니다.

## APK 확인

    python tools/verify_apk.py build/apk/bad-note-Android-4.1.4-beta.1-Update.apk --build-tools <SDK>/build-tools/36.1.0 --expected-package com.inkforge.note4 --expected-version-code 421 --expected-version-name 4.1.4-beta.1 --pdfium-manifest build/dependencies/pdfium-8086/verification.json

현재 네이티브 구조와 자료 구분은 [네이티브 안내](../docs/NATIVE-KO.md), [문서 안내](../docs/README.md)를 참고하십시오.
