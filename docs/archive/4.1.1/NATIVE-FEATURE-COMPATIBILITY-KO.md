# 네이티브 통합 기능 대응표

기준은 4.0.1 소스 커밋 `5b7c850f05af2a7d7988b10598a47c9e6dab4b20`이다. `tools/capture_native_contract.py --ref <커밋>`은 Git의 원본 12개 Kotlin 파일에서 함수 324개와 설정·객체 종류의 리터럴을 추출한다. 생성 파일은 `build/native-contract/baseline.json`이다. 함수가 존재하는 것만으로 동작 검증을 통과한 것으로 보지 않는다.

아래 표는 Compose·Room·OCR·출력 경로를 바꾸면서 유지할 사용자 흐름이다. 최종 검증 결과는 별도 실행 영수증에 연결한다. 공개 4.0.1 릴리스의 기록을 새 구현의 결과로 재사용하지 않는다.

| 영역 | 기준 연결 지점 | 유지할 동작 | 새 경로의 검증 |
|---|---|---|---|
| 문서 목록·탐색 | `showLibrary`, `documentCard`, `newNote` | 격자/목록, 정렬, 검색, 최근 문서, 문서 탭, 템플릿 선택과 생성 | 실제 Compose 항목 열기·정렬·검색·새 노트 |
| 폴더 | `newFolder`, `folderMenu`, `mutateDocument` | 중첩 폴더, 이름·색상, 문서 이동, 삭제 후 문서 보존 | 폴더 생성·이름 변경·문서 이동·재시작 |
| 문서 옵션 | `documentMenu` | 제목·태그·색상, 즐겨찾기·고정, 복제, 휴지통·복원, 영구 삭제 확인 | 각 명령 후 저장소 내용·순서·OCR 교정 비교 |
| 페이지·목차 | `addPage`, `pageMenu`, `jumpPage`, `renderSidebar`, `outlineMenu` | 추가·복제·재배치·삭제, 제목·큰 텍스트 목차, 페이지·녹음 링크, 마지막 위치 | 사이드바·목차 실제 이동, 삭제 중 늦은 작업 거절 |
| 보기·안전 여백 | `changePageMode`, `RootSafeArea`, `onConfigurationChanged` | 문서별 단일/연속 모드, 확대·페이지 맞춤, 상태바·홈 버튼·컷아웃·키보드와 위아래 8dp | 휴대폰·태블릿·제스처/3버튼·IME·회전·반복 인셋 |
| 펜·색상 | `renderActiveDock`, `penMenu`, `penSettingsSheet`, `colorMenu`, `widthMenu` | 기존 펜 종류·설정·굵기·팔레트·기울기, 도구 순서와 좁은 도구막대 | 원본 JS 계산/그리기 명령 fixture와 실제 Canvas 렌더 |
| 입력·지우기·Undo | `InkCanvasView`, `StylusButtonState`, `ObjectChange` | historical samples, 펜 버튼 전환, 취소·손바닥 배제, 정밀/획/낙서 지우개, 제스처별 Undo·Redo | 실제 MotionEvent, 원거리·잠금·형광펜 보존, 한 명령 복원 |
| 객체 편집 | `insertObject`, `showSelection`, `objectMenu` | 텍스트·도형·이미지·스티키·테이프·수식, 올가미·이동·크기·회전·잠금·복사·붙여넣기·겹침 순서 | 화면·PNG/PDF의 동일 위치/회전, 저장 후 재열기 |
| 인식·수식 | `recognize`, `scheduleOcr`, `mathMenu`, `MathEngine` | 수동/자동 동일 OCR, 원문·후보·교정, 검색·복사·선택 삽입, 확인 후 수식 계산·변수·각도 단위 | 실제 Task 수명, 오래된 결과 거절, 원본 획 불변, phone 진행·취소 |
| PDF·파일 | `importUri`, `PdfImporter`, `BackgroundLoader`, `NativeFileExport` | 원본 PDF·구형 JPEG, 원문 검색, ZIP v5 기본·명시적 구형 JSON, PNG/PDF/XFDF, 취소·오류 | API23/35 원문, 500페이지·대형 자산, 왕복·독립 PDF 파서·FD/타일 수명 |
| 녹음 | `AudioController`, `toggleRecording`, `stopRecording`, `audioMenu` | 권한 거절·중단, 실제 MIME/코덱, 파일 확정·재생·삭제·페이지 연결 | 권한/실패/재생/녹음 포함 왕복; 에뮬레이터 음원을 실물 마이크 성능으로 해석하지 않음 |
| 공유·수신·업데이트 | `handleIncoming`, `onActivityResult`, `AppUpdater` | SAF 저장, FileProvider URI·읽기 권한·ClipData, 파일 열기 수신, 업데이트 다운로드·설치 권한·취소 | 지연된 URI 읽기, 수신 파일, 네트워크/권한 오류와 두 설치 변형 |
| 설정·언어·접근성 | `showSettings`, `loadSettings`, `loadDictionary`, `gestureGuide`, `applyHudOpacity` | 기존 설정 키·기본값·알 수 없는 필드, 언어, HUD, 제스처 안내, OCR·입력 정책 | 실제 composable 변경·재시작·언어 변경, 읽기 가능한 라벨·포커스·스크롤 |
| 기존 데이터 이전 | `LegacyMigration`, `NoteRepository`, `NativeDatabase` | 설치 DB와 IndexedDB 원본 보존, 문서별 확정·재시도, unknown JSON·ID·순서·자산 | v1 실제 schema fixture, 중단/재개, 오류 복구, update/sideBySide 저장소 분리 |

예측 좌표와 화면 캐시는 원본 획·OCR·내보내기의 저장 데이터로 쓰지 않는다. 자동 OCR 색인은 별도 저장소를 사용하고 보이는 텍스트 객체를 추가하지 않는다. 모델 출력이 실패하거나 입력이 모호하면 미완료·검토 상태로 남긴다.

사용자는 현재 실기기와 정답 필기 자료가 없다고 확인했다. 실제 CER·물리 S펜 지연·마이크 품질·대상 기기 메모리와 프레임 조건은 미측정이다. 합성 이벤트·에뮬레이터·정적 검사와 분리해서 보고한다.
