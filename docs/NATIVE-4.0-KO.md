# 4.0 네이티브 전환

## 변경 범위

Kotlin Activity와 전용 Android View가 라이브러리·필기·도구·설정·내보내기를 처리합니다. 기존 Java WebView Activity/브리지/내보내기를 제거했습니다. 앱 실행 경로에 `app.js`, `packDocument()`, `navigator.share()`가 없습니다. 기존 웹 소스는 이전 형식 참고용으로 보존하되 빌드의 asset source set에서 제외합니다.

`MainActivity`는 화면과 작업을 연결하고, `InkCanvasView`/`InkGeometry`/`NoteRenderer`는 입력·객체 편집·그리기를 맡습니다. `NoteRepository`는 SQLite WAL과 자산 파일을 관리합니다. `BackgroundLoader`는 최대 24 MiB 타일/이미지 캐시와 제한된 PDF 렌더러를 사용합니다. PDF 처리, OCR, 저장은 각각 직렬 작업 경로로 분리합니다.

## 내보내기 메모리

`.ifnote` v5는 `manifest.json`, `pages/<id>.json`, `assets/<id>`를 포함하는 ZIP입니다. 자산은 문자열로 만들지 않고 64 KiB 버퍼로 읽고 씁니다. 문서 메타데이터와 페이지 하나의 편집 객체만 직렬화합니다. 내보내기는 저장 작업 뒤에 실행해 일관된 상태를 읽습니다. 파일 준비 중 편집 UI는 잠깁니다. 오류·취소 시 준비 중인 파일을 삭제합니다.

구형 JSON 파일의 `data:*;base64` 문자열도 문자 스트림에서 파일로 직접 디코딩합니다. 자산 전체 문자열을 먼저 생성하는 방식은 사용하지 않습니다. 페이지의 객체 데이터 자체는 페이지 단위 메모리에 올라갑니다. 자산 총용량에 비례하는 메모리 사용은 제거했지만, 한 페이지의 극단적인 객체 수에 대한 별도 제한과 최적화 여지는 남아 있습니다.

## 공유와 저장

공유 파일 준비 완료 → `FileProvider` content URI 생성 → `ACTION_SEND` → Android 공유 창 순서입니다. 클릭 직후 잠시만 유지되는 브라우저 활성화 상태와 무관합니다. URI에는 읽기 권한과 ClipData를 함께 제공합니다. 사용자 지정 저장은 `ACTION_CREATE_DOCUMENT` 후 스트림 복사로 처리하고, 성공·취소·실패를 구분합니다.

## 데이터 이전

같은 패키지 업데이트의 최초 실행에서 기존 `appassets.androidplatform.net` origin의 IndexedDB를 읽습니다. 원본 DB를 수정하거나 지우지 않습니다. PDF Blob은 48 KiB 조각, 기존 base64는 64 KiB 조각으로 네이티브 파일에 옮깁니다. 페이지와 메타데이터를 임시 저장한 뒤 문서별 트랜잭션으로 확정합니다. 이미 확정한 문서는 재시도 시 건너뜁니다.

패키지나 서명이 달라지면 Android 앱 저장소는 별개입니다. 이 경우 구형 `.ifnote` 백업을 가져오십시오. 이전 형식의 PDF 배경 자산이 누락된 파일은 조용히 빈 배경으로 처리하지 않고 가져오기 오류를 표시합니다.

## 호환 범위

- 구형 필기·형광펜·도형·텍스트·이미지·스티키 노트·테이프·수식·녹음 객체와 기타 JSON 속성을 보존합니다.
- 필기 렌더러를 교체했으므로 펜 질감과 압력 곡선은 3.x와 완전히 동일하지 않습니다.
- 새 PDF는 원본을 보관합니다. 구형 JPEG 배경은 그대로 이전하며 잃어버린 원본 해상도를 복원하지는 않습니다.
- 내보낸 PDF는 기존처럼 페이지를 래스터화합니다. PDF 원본은 `.ifnote`에 보존됩니다. 원본과 벡터 주석을 병합하는 PDF 내보내기는 별도 과제입니다.
- XFDF는 필기·도형·텍스트 주석을 내보냅니다. 대상 PDF 앱의 가져오기 지원과 표현 차이가 있습니다.
- PDF 자체 텍스트 추출은 Android 15 이상에서 수행합니다. 이전 Android 또는 스캔 PDF는 명시적 이미지 OCR을 사용할 수 있습니다.
- 필기 OCR은 ML Kit 모델이 최초 다운로드된 뒤 오프라인으로 동작합니다. 첫 모델 다운로드에는 네트워크가 필요합니다. 서버형 수학 OCR/CAS는 포함하지 않습니다.
- v5 `.ifnote`는 4.0 이상에서 열립니다. 3.x로의 역저장이나 양방향 DB 동기화는 지원하지 않습니다.
- 필기/객체 편집의 실행 취소 이력은 메모리 예산 내에서 유지됩니다. 페이지 구조 변경 이력은 포함하지 않습니다.

## 검증

`tools/test_native.py`로 재현합니다. JVM 테스트는 96 MiB 힙에서 100/300 MiB 자산 ZIP 왕복과 SHA-256, 100 MiB legacy base64 디코딩, 잘못된 경로·취소·정밀 지우개·계산식을 검증합니다.

Android 테스트는 별도 `.debug` 패키지에서 네이티브 S Pen 이벤트·취소·터치 배제·지우개·undo/redo, `.ifnote` 왕복, PDF 생성·렌더링·원본 보관, 실제 저장소의 300 MiB 내보내기/가져오기, 공유 URI 읽기 권한, IndexedDB 이전과 재시도 및 원본 보존을 확인합니다.

2026-10-08 로컬 검증 결과:

- JVM 13개 검사 통과. 최대 힙 96 MiB에서 100/300 MiB ZIP 왕복과 100 MiB 구형 base64 가져오기의 SHA-256이 일치했습니다.
- Android 15(API 35) 태블릿 에뮬레이터의 최대 앱 힙 192 MiB에서 실제 저장소의 300 MiB 내보내기·가져오기가 통과했습니다.
- S Pen MotionEvent, 지우개·undo/redo, PDF 생성·렌더링, 지연된 공유 URI 권한, 기존 IndexedDB 이전·재시도·원본 보존, 가져온 목차·녹음·페이지 링크, 네이티브 뒤로가기 검사를 통과했습니다.
- Android Lint는 오류 0개, 경고 42개입니다. 남은 경고는 아이콘·번역 리소스·그리기 중 소규모 객체 할당·API/라이브러리 갱신 제안 등이며, 오류 발생 시 빌드가 실패하도록 설정했습니다.
- 4.0.0 업데이트용·병행 설치용 릴리스 빌드가 성공했습니다. 두 APK의 ZIP CRC, v1/v2/v3 서명, 16 KiB ZIP 정렬을 검증했고 기존 3.3.30 업데이트 APK와 인증서 SHA-256이 일치합니다. 기존 웹 편집기 assets가 APK에 포함되지 않는 것도 확인했습니다.
- `build/native-final-tests.log`와 `build/native-instrumentation.log`에 실행 결과를 남깁니다. 자동화 이벤트 검증은 실물 펜의 성능 측정을 대신하지 않습니다.

물리 Galaxy/S Pen의 장시간 필기감, 펜별 압력·기울기, 제조사 원격 Air Actions는 에뮬레이터만으로 검증할 수 없습니다.

## 4.0.1 — 기존 UI 복원과 왕복 지우기

- `ClassicUi.kt`는 원래 SVG 경로를 Android Canvas로 그립니다. `tools/sync_native_icons.py`로 3.3.30 원본의 아이콘 89개를 동기화합니다. 편집기는 계속 Kotlin View/Canvas이며, 웹 편집기 런타임은 배포하지 않습니다.
- 원래 라이브러리·문서 탭·편집 도구막대·도구별 설정 막대·실행 취소 버튼·페이지 및 확대율 HUD·페이지 사이드바·템플릿·설정·색상 조합기 구성을 복원했습니다. 작은 화면에서는 하단 내비게이션과 하단 시트를 사용합니다.
- 낙서 판정에서 매 샘플의 최소 이동량·최소 세로 높이 제한을 제거했습니다. 주축에 투영한 좌표의 극점으로 왕복을 세고, 최소 네 번의 왕복 구간 중 세 구간 이상이 실제로 닿은 필기만 지웁니다. 선분 교차 검사로 희소한 기존 필기도 처리합니다.
- `NativeCoreTest`는 촘촘한 샘플, 완전히 평평한 경로, 수평·45도·수직, 확대율, 주변 획·잠금·형광펜 보존과 일반 글씨의 오인식을 검사합니다.
- `NativeSmokeInstrumentation`은 Android `MotionEvent.addBatch`로 historical samples를 전달하며 50%·100%·380% 확대율의 세 방향 왕복, undo/redo, 기능 비활성화와 형광펜 입력, 주요 UI 메뉴 접근을 검사합니다. `--ui-only`는 이 회귀 검사만 실행하며 기존 대용량 내보내기·마이그레이션 검사는 생략합니다.

4.0.1 최종 검증 결과 (2026-10-08):

- JVM 16개 검사와 Android UI·왕복 지우기·스타일러스 회귀 검사가 통과했습니다. 실제 S Pen 하드웨어 검사는 수행하지 않았습니다.
- Android Lint는 오류 0개, 경고 95개, 참고 1개입니다. 그리기 중 할당, RTL 및 접근성 등 남은 경고는 후속 개선 대상입니다.
- 업데이트용·병행 설치용 서명 APK의 릴리스 빌드, ZIP CRC, v1/v2/v3 서명, 16 KiB ZIP 정렬을 확인했습니다. 두 APK는 기존 3.3.30과 같은 인증서를 사용합니다.
- 서명된 업데이트 APK를 API 35 에뮬레이터에 설치하고 실행 및 라이브러리·편집기 화면을 확인했습니다.
- 결과: `build/classic-ui-final-build.log`, `build/classic-ui-checks.log`, `build/classic-ui-instrumentation.log`, `build/classic-ui-release.log`, `build/classic-ui-verify-update.json`, `build/classic-ui-verify-sidebyside.json`.
