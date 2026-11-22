# 정밀 지우개·텍스트 편집 — local.5

기준: 4.1.3-beta.2-local.5 / versionCode 419. 기존 local.4 소스와 APK의 펜·올가미·OCR 개선을 유지한 로컬 수정이다. GitHub 커밋·푸시·PR·배포는 하지 않는다.

## 참고한 Goodnotes 동작

- [공식 지우개 안내](https://support.goodnotes.com/hc/en-us/articles/7353718249231-Erase-handwriting-and-page-content-with-the-Eraser-tool): 정밀 지우개는 원 내부만 제거하고 획 지우개는 닿은 획 전체를 제거한다. Goodnotes 자체는 입력한 텍스트 지우기를 지원하지 않는다. BADNOTE는 사용자 요청에 따라 텍스트에도 부분 지우기를 제공한다.
- [공식 텍스트 도구 안내와 메뉴 이미지](https://support.goodnotes.com/hc/en-us/articles/7353727615375-Type-text-with-the-Text-Tool): 색상·크기·글꼴·서식·정렬/간격·목록·상자 스타일, 객체 이동, 재탭 편집과 변형 손잡이를 참고했다.
- [공식 UI 안내](https://support.goodnotes.com/hc/en-us/articles/13682253498767-Improved-User-Interface): 선택 객체 근처에 뜨는 메뉴와 도구별 추가 설정을 참고했다. BADNOTE의 기기 기본 글꼴과 Android 입력기는 유지한다.

## 수정

도형·텍스트는 정밀 모드에서도 객체를 통째로 삭제하던 분기를 제거했다. 형광펜은 굵기만큼 중심선을 잘라내던 처리를 대신해 원 내부를 잘라 표시한다. 객체별 `eraseMask`는 회전 전 경계에 정규화된 좌표로 저장되므로 이동·배율 변경·회전과 함께 움직인다. 객체의 원본 데이터와 부분 서식은 유지하고, 지운 영역은 화면·미리보기·공유 렌더러에서 제외한다. 아래에 겹쳐진 다른 내용은 지우지 않는다. 획 모드와 전체 모드, 잠금·숨김 보호, 취소와 실행 취소는 유지한다.

텍스트 메뉴의 기본 PopupMenu 목록을 카드형 PopupWindow로 교체했다. 색상은 스와치, 글꼴은 실제 글꼴 미리보기, 크기는 숫자 타일과 증감, 서식은 B/I/U/S 토글, 정렬과 간격은 선택 버튼, 상자 스타일은 배경 미리보기와 테두리·여백으로 고른다. 흰색 플로팅 바에 현재 색상·크기·글꼴·서식·정렬/간격·목록·배경을 표시한다. 팝오버는 Android 입력 포커스와 키보드를 유지한다.

빈 입력 상자의 서식은 즉시 미리보기에 적용한다. 글자 선택이 있으면 선택 범위, 입력 중 커서만 있으면 이후 입력, 상자 선택 상태에서는 전체 글자에 적용한다. 선택/커서 서식도 플로팅 바에 반영한다. 상자 위의 이동 손잡이를 끌면 입력 중에도 이동하고, 메뉴의 이동 버튼은 키보드를 닫아 상자 전체를 드래그할 수 있게 한다.

## 검증과 산출물

2026-10-27에 변경 부위 중심으로 확인했다.

- `ExactEraseGeometryTest`: JVM 18개, 실패·오류 0개. 기존 펜 정밀 지우개 기하 계산 회귀를 확인했다.
- API 35 에뮬레이터, 가로 1202dp, `com.inkforge.note4.debug`의 `textEditingOnly`: 통과. 카드 버튼 실제 터치, 빈 상자의 글꼴·크기·색상·배경, 키보드가 열린 미리보기 이동, 선택/커서 서식과 플로팅 표시, 한글 조합, 이동/너비 변경, 저장·재열기, 실행 취소/다시 실행, 목록과 입력 완료를 확인했다.
- `PrecisionEraseChecks`: 도형·텍스트·굵은 형광펜의 지우개 원 안/밖 픽셀, 회전·이동·반복 입력·JSON 재읽기와 아래 객체 보존을 확인했다. 복잡한 클리핑이 기존 도형 가장자리의 안티앨리어싱 농도를 바꾸는 경우가 있어, 원 밖의 흰 배경 합성 색상 비교에 8/255 이내 오차를 허용한다. 원 안쪽은 투명해지고 원 바깥의 본문은 유지되어야 통과한다.
- 실제 캔버스의 획/정밀 모드, 텍스트·도형·형광펜, 잠김·숨김 보호, S Pen 버튼 경로, 취소와 실행 취소/다시 실행: 통과.
- OCR beta Update·SideBySide release 빌드 및 두 APK의 패키지·419 버전·서명·ZIP 무결성·16KiB 정렬·고정 PDFium 검사: 통과.

실물 Galaxy/S Pen 검사는 수행하지 않았다. 기기 입력 계측은 별도 `.debug` 패키지를 사용했고 기존 앱이나 노트를 초기화하지 않았다. 테스트의 팝오버 좌표를 별도 창의 화면 좌표로 고쳤으며, 긴 텍스트 편집 후의 페이지 재정렬과 키보드 숨김 애니메이션이 끝난 뒤 다음 터치를 보내도록 했다.

기록: `build/local5-focused-build.log`, `build/local5-text-precision-checks.log`, `build/local5-release-build.log`, `build/local5-update-verification.json`, `build/local5-sidebyside-verification.json`. 메뉴와 미리보기 캡처는 `build/local5-font-panel.png`, `build/local5-style-panel.png`, `build/local5-selected.png`에 있다.

- [Update APK](../build/apk/bad-note-Android-4.1.3-beta.2-local.5-Update.apk)
- [SideBySide APK](../build/apk/bad-note-Android-4.1.3-beta.2-local.5-SideBySide.apk)

기존 local.4 APK는 보존했다. 현재 로컬 서명으로 생성했으며 공개 배포판과 로컬 서명의 구분은 [빌드 안내](../android/README-BUILD-KO.md)를 따른다.
