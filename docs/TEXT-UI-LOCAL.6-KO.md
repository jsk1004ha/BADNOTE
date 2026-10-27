# local.6 텍스트 UI 재작업

기준: 4.1.3-beta.2-local.6, versionCode 420. 공개 beta.2와 기존 로컬 펜·올가미·OCR·정밀 지우개 수정을 유지한 로컬 변경이다. GitHub에 커밋·푸시·PR·릴리스를 만들지 않았다.

## 조사 범위

[iPad Goodnotes 조사 근거](GOODNOTES-IPAD-TEXT-REFERENCE.md)에 공식 안내, 원본 iPad 스크린샷, 공식 편집 녹화와 관찰 범위를 정리했다. 최신 버전의 모든 하위 메뉴를 iPad 실기기에서 직접 조작한 것은 아니므로 완전한 1:1 복제라고 단정하지 않는다. Android 키보드와 기본 글꼴은 Android의 것을 사용한다.

사용자 요청에 따라 Goodnotes AI, 링크, 사용자 글꼴 설치, 그림자·모서리 반경·별도 테두리 색상 등 기존 BADNOTE에 없는 기능은 넣지 않았다.

## 변경

- 큰 타일 버튼을 밝은 연속 도구막대와 그룹형 팝오버로 바꿨다. 선택 상태에는 객체 작업, 입력 상태에는 범위 잘라내기·복사·붙여넣기를 표시한다.
- 글꼴은 기존 세 글꼴을 실제 서체로 미리 보여 주는 목록이다. 크기는 슬라이더·증감 버튼, 서식과 정렬은 분할 버튼, 목록과 더보기는 행 목록으로 구성했다.
- 색상은 프리셋·색상환·HEX, 상자 스타일은 기존 배경·테두리·안쪽 여백을 프리셋/고급으로 구성했다. 지원되지 않는 메뉴를 빈 버튼으로 넣지 않았다.
- 빈 텍스트 미리보기와 커서 서식도 즉시 도구막대에 반영된다. 선택/입력 상태가 바뀔 때를 제외하고 도구막대를 통째로 재생성하지 않는다.
- 입력 중에는 상단 이동 손잡이와 양옆 폭 손잡이를 표시한다. 선택 상태에서는 상자 드래그, 흰 모서리 비율 확대, 파란 양옆 폭 조절, 흰 원 회전 손잡이를 사용한다.
- 모서리 확대는 가로·세로 이동을 함께 계산한다. 회전 상태의 폭 조절에서도 반대쪽 위 모서리를 고정해 상자가 밀리는 현상을 수정했다. 폭 변경은 글자 크기를 유지하고, 비율 확대는 글자와 여백을 함께 확대한다.
- 드래그 중에는 객체 메뉴를 숨기고 놓으면 다시 배치한다. 메뉴는 회전된 상자의 중심에 맞추고, 팝오버는 키보드와 화면 경계에 따라 위·아래·옆에 배치한다. 팝오버 화살표는 누른 버튼을 가리킨다.
- 팝오버 하단 탭은 스크롤과 분리했다. 기존 선택 범위를 유지하고, Android의 별도 텍스트 액션 메뉴와 객체 메뉴가 중복되지 않게 했다.
- 복사·잘라내기는 입력 중 선택한 범위에 적용한다. 목록 서식을 바꿀 때 기존 글자 서식 범위를 보존한다. 편집 세션의 문서 실행 취소 단위는 유지한다.

주요 소스: InlineTextEditor.kt, TextChrome.kt, TextTransform.kt, MainActivity.kt. 지우개 마스크 구현과 저장 형식은 이번 재작업에서 변경하지 않았다.

## 화면

- [글꼴 목록](../build/local6-text-screens/text-editor-1202dp-font-panel.png)
- [색상환](../build/local6-text-screens/text-editor-1202dp-color-custom.png)
- [상자 스타일 고급](../build/local6-text-screens/text-editor-1202dp-style-advanced.png)
- [입력 상태](../build/local6-text-screens/text-editor-1202dp-typing.png)
- [회전·크기 조절 후 선택 상태](../build/local6-text-screens/text-editor-1202dp-selected.png)

## 검증 결과

전체 종합 검사 대신 변경된 텍스트 UI·변형 계산·기존 지우개에 범위를 한정했다.

- JVM: TextTransformTest 3개와 ExactEraseGeometryTest 18개, 총 21개 통과.
- Android API 35 에뮬레이터, 가로 1202dp, 별도 com.inkforge.note4.debug 패키지에서 실제 터치 계측 통과. 한글 조합, 빈 미리보기, 서식 메뉴, 커서/범위 서식, 선택/입력 전환, 이동·폭 변경·모서리 확대·회전·회전된 폭 변경, 연속 입력, 목록, 저장/재열기, 실행 취소/다시 실행을 확인했다.
- 정밀 지우개 픽셀 검사와 텍스트·도형·형광펜 실제 지우개 검사가 통과했다.
- Update / SideBySide OCR beta Release 빌드 성공. 두 APK 모두 패키지·버전 420·서명·ZIP 정렬·64비트 네이티브 16KiB 정렬·PDFium 자산 식별 검증을 통과했다.
- [최종 계측 결과](../build/local6-text-result.json), [계측 로그](../build/local6-text-checks.log), [릴리스 빌드](../build/local6-release-build.log), [Update APK 검사](../build/local6-update-verify.log), [SideBySide APK 검사](../build/local6-sidebyside-verify.log).

실제 iPad 및 Galaxy Tab/S Pen 하드웨어에서의 비교·회귀 검사는 수행하지 않았다. 기존 사용자 앱이나 노트를 초기화하지 않았다.

## 설치 파일

- [기존 앱 업데이트용](../build/apk/bad-note-Android-4.1.3-beta.2-local.6-Update.apk) — com.inkforge.note4
- [병행 설치용](../build/apk/bad-note-Android-4.1.3-beta.2-local.6-SideBySide.apk) — com.inkforge.note5
- [디버그 검증용](../build/apk/bad-note-Android-4.1.3-beta.2-local.6-Debug.apk) — com.inkforge.note4.debug

SHA-256:

```text
Update      751ecf5acc99df429263565361c5f9c89acc9d637ea486ea0b7902a59e82af2e
SideBySide  0757f902adb049b0e90a2e524a1e6da675f3895a261415a2a5c0077089cf4e95
```
