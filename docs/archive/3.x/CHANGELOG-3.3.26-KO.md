# 3.3.26 변경 내역

## 버전

- 표시 버전을 `3.3.26`으로 올렸습니다.
- Android 업데이트 설치가 가능하도록 `versionCode 358`로 올렸습니다.

## 번역 및 국제화

- 포르투갈어(브라질) UI 번역을 분리된 locale 파일 기준으로 보강했습니다.
- 언어 전환 뒤 색상표, 수식 계산, S Pen 패널이 계속 동작하는지 웹 회귀 테스트에서 확인하도록 했습니다.
- 번역 업데이트 도구가 개인 PC의 절대 경로가 아니라 저장소 기준 상대 경로로 `web/` 폴더를 찾도록 정리했습니다.

## 빌드 동기화

- 웹 원본을 Android WebView asset으로 복사하는 `copy_script.ps1`을 추가했습니다.
- APK를 만들기 전에 `web/`과 `android/app/src/main/assets/public/`의 버전 표기가 어긋나지 않도록 동기화했습니다.

## 기여자

- 포르투갈어(브라질) 번역 및 UI 개선 기여자로 [dyduq12](https://github.com/dyduq12)를 추가했습니다.

## 검증

- 웹 회귀 테스트와 Android `updateRelease`, `sideBySideRelease` 빌드를 통과했습니다.
- 두 APK 모두 `versionName 3.3.26`, `versionCode 358`, 16KB zipalign, v1/v2/v3 서명 검증을 통과했습니다.
