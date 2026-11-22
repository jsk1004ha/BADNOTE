# 상단 도구·필기 표시·페이지 이동 수정

2026-10-23, **4.1.3-beta.2-local.2 / versionCode 416**. 기존 local.1 소스와 미커밋 펜·올가미·OCR 개선을 유지한 로컬 수정판이다. 기존 APK를 보존하며 GitHub 반영은 하지 않는다.

## 변경

- 상단 도구 모음에서 `screenWidthDp <= 840`이면 두 행으로 만드는 분기를 제거했다. 상단은 62dp 한 줄이고, 탐색 버튼을 제외한 도구 영역만 남은 너비 안에서 가로로 스크롤된다. 펜 세부 설정도 상·하단에서는 60dp 한 줄을 사용하며 실제 편집 화면 너비로 크기를 정한다. 사용자가 선택한 넓은 화면의 좌·우 세로 도킹은 유지한다.
- 완성된 필기 화면은 대기·확대·지우기·도구 전환 때 일반 View가 계속 그린다. 전면 버퍼의 완료 알림만으로 View를 빈 오버레이로 바꾸지 않는다. 준비가 끝난 화면에서 실제 전면 펜 접촉을 시작한 동안만 Surface에 표시를 맡긴다. 크기 변경 도중 버퍼 크기가 맞지 않아 그리지 못한 프레임은 완료된 페이지로 인정하지 않는다. 기존 명령·타일 캐시, 입력 표본과 필압, 부분 지우개 갱신은 유지한다.
- 페이지를 길게 눌러 이동하면 안정된 페이지 ID와 `notifyItemMoved`로 주변 썸네일을 180ms 동안 이동시킨다. 드래그 중에는 이동할 자리를 표시하고 가장자리 자동 스크롤을 유지한다. 표시 순서만 미리 바꾸며 놓을 때 한 번 저장한다. 취소·영역 밖 놓기에는 원래 순서로 돌아간다. 북마크·현재 페이지·접근성 동작은 페이지 ID를 따른다.

## 참고한 동작

- [Goodnotes 공식 UI 안내](https://support.goodnotes.com/hc/en-us/articles/13682253498767-Improved-User-Interface): 주요 도구·더보기와 선택한 도구 설정의 구성을 참고했다. 한 줄 유지와 폭 계산은 이번 사용자 요구에 맞췄다.
- [삼성 공식 Samsung Notes 안내](https://insights.samsung.com/2025/03/27/10-tips-for-increased-productivity-with-samsung-notes/): 페이지 정렬에서 길게 눌러 새 위치로 끌어 놓는 동작을 참고했다. 주변 페이지 이동 애니메이션은 이번 구현이다.

## 검증

- JVM 181개 / 27 suites 통과. 실패·오류·건너뜀 0개.
- 별도 `com.inkforge.note4.debug` 앱의 집중 검사에서 한 줄 상단·펜 설정과 15개 전환 프레임의 변경하지 않은 필기 픽셀을 확인했다. 펜 시작·이동·완료, 정밀 지우개, 실행 취소·다시 실행, 확대·축소·취소와 Surface 준비 상태를 포함한다.
- 811dp 세로 화면에서 실제 길게 누르기와 드래그를 실행했다. 드래그 중 실제 썸네일 순서 변경·저장 전 미리보기, 양방향 이동, 13쪽 목록의 가장자리 스크롤, 접근성 이동, 취소·밖에 놓기, 저장 대기·실행 취소·동시 페이지 추가 처리가 통과했다.
- 실제 합성 화면의 전면 버퍼 완료 대기 중 다음 획, 빠른 연속 필기, 취소·실행 취소·다시 실행 검사도 통과했다. 기존 브러시 픽셀 회귀는 변경 없이 통과했다.
- Debug·Update·SideBySide APK 빌드 성공. Release 두 파일의 패키지·416/버전명·서명·ZIP CRC·16KiB 정렬·고정 PDFium 확인. 기존 local.1 Update APK와 서명 인증서가 같다.
- 로그: `build/editor-debug-validation.log`, `build/editor-continuity.log`, `build/editor-page-management.log`, `build/editor-pen-presentation.log`, `build/editor-release-build.log`. APK 확인 결과: `build/editor-update-verification.json`, `build/editor-sidebyside-verification.json`.

실제 Galaxy/S Pen의 입력 지연과 드라이버별 표시 동작을 측정한 결과는 아니다. 장시간 OCR·대형 문서 성능 검사는 이번 검증에 추가하지 않았다.

## 설치 파일

- `build/apk/bad-note-Android-4.1.3-beta.2-local.2-Update.apk`
- `build/apk/bad-note-Android-4.1.3-beta.2-local.2-SideBySide.apk`
- `build/apk/bad-note-Android-4.1.3-beta.2-local.2-Debug.apk`

패키지·서명에 따른 설치 범위는 [기존 설치 안내](RELEASE-4.1.3-BETA.2-KO.md)를 따른다. 공개 배포 인증서와 현재 로컬 인증서는 서로 다르다.
