# 3.3.28 변경 내역

## S Pen 버튼 지우개

- S Pen이 화면에 닿아 필기 중인 상태에서 측면 버튼을 눌러도 현재 필기 세션을 즉시 지우개 세션으로 전환합니다.
- Android `Activity.dispatchGenericMotionEvent()`에서 스타일러스의 `ACTION_BUTTON_PRESS`와 `ACTION_BUTTON_RELEASE`를 WebView보다 먼저 받아 전달합니다.
- `KEYCODE_STYLUS_BUTTON_PRIMARY/SECONDARY/TERTIARY/TAIL`은 장치 이름이나 source 메타데이터가 없어도 Activity에서 직접 처리합니다.
- 현재 접촉 상태를 네이티브에서 유지하므로 버튼 이벤트가 hover로 잘못 처리되지 않습니다.
- Activity 선점 경로와 WebView의 기존 generic-motion 경로가 같은 이벤트를 두 번 보내지 않도록 중복 전달을 제거했습니다.
- 버튼을 놓으면 기존 필기 도구로 복귀합니다.

## 원인

- 기존 웹 필기 엔진은 네이티브 버튼 이벤트가 도착하면 접촉 중인 획을 지우개로 바꾸는 기능이 정상 동작했습니다.
- 실제 기기에서는 접촉 중 발생한 generic-motion 버튼 이벤트가 WebView의 `onGenericMotionEvent()`까지 전달되지 않는 경우가 있어, 다음 접촉의 `buttonState`를 읽을 때까지 전환되지 않았습니다.
- 참고한 xnotes-android도 generic-motion과 KeyEvent를 별도로 latch하지만 접촉 시작 시점에만 버튼 상태를 읽습니다. BADNOTE는 Activity 선점 전달을 추가해 접촉 도중 전환까지 처리합니다.

## 버전

- 표시 버전과 자동 업데이트 비교 버전을 `3.3.28`로 올렸습니다.
- Android `versionCode`는 `360`입니다.
- 앱 내 업데이트 내역은 한국어, 영어, 일본어, 중국어, 포르투갈어로 제공합니다.

## 검증

- 수정 전 네이티브 소스 계약 테스트에서 Activity generic-motion 캡처가 없음을 재현했습니다.
- 수정 후 네이티브 소스 계약 테스트와 Android debug 컴파일이 통과했습니다.
- 전체 Playwright 웹 회귀 테스트와 두 Android release 빌드를 검증합니다.

## APK

- `bad-note-Android-3.3.28-Update.apk`
  - 패키지: `com.inkforge.note4`
  - 크기: `50,995,840 bytes`
  - SHA-256: `a1b78792a5fb559fe8fb679f6ce0648125aaf15f5687a51cb8271a8fb72e34c8`
- `bad-note-Android-3.3.28-SideBySide.apk`
  - 패키지: `com.inkforge.note5`
  - 크기: `50,995,849 bytes`
  - SHA-256: `103656fa3316ce9c681ab812cf75f0738d0f20aaf2ef21afaca3b80ba98ccbd4`

두 APK 모두 ZIP CRC, 16KB zipalign, v1/v2/v3 서명, `versionName 3.3.28`, `versionCode 360` 검증을 통과했습니다.

## 확인하지 못한 항목

- 실제 S Pen 기기에서 제조사별 generic-motion 라우팅 차이는 자동화 환경에서 검증하지 못했습니다.
