# BADNOTE 네이티브 OCR 통합

## 범위와 기준

사용자가 제공한 ocr-plan.md와 kotlin-plan.md를 현재 네이티브 앱에 통합한다. 사용자는 두 문서의 기반 차이에 대해 **현재 네이티브 앱 기준으로 통합**하는 방향을 선택했다. OCR 문서의 Worker·IndexedDB·Java 브리지 요구는 Kotlin 백그라운드 작업·Room·ML Kit 호출로 옮긴다.

기준 커밋은 5b7c850f05af2a7d7988b10598a47c9e6dab4b20, 개발 브랜치는 codex/ocr-native-plan이다. 공개된 4.0.1 릴리스와 APK는 이번 개발 결과로 대체하지 않는다. 패키지, 최소 Android 6.0(API 23), 서명, 설치 변형, 기존 노트 및 브러시를 유지한다.

현재 4.0.1은 Kotlin Activity/View, badnote-native.db의 SQLite 버전 1, 원본 PDF 보관, 512 픽셀 PDF 타일, 객체 변경 단위 Undo, ZIP 버전 5 .ifnote를 사용한다. OCR은 수동·자동 처리와 이미지 인식이 있지만 내용 기반 캐시·편집 세대·별도 결과 저장소가 없다. Compose·Room·front buffer는 기준 코드에 없다. 이전 에뮬레이터 검증 결과는 새 변경의 검증으로 재사용하지 않는다.

사용자의 추가 지시에 따라 통합 버전은 **4.1.1-beta.1**, Android versionCode는 **411**로 배포한다. GitHub Pre-release로 게시하고 Latest로 지정하지 않는다. 자동 업데이트는 정식 릴리스만 허용하므로 현재 정식 대상은 4.0.1이다. 베타 피드백을 확인한 뒤 같은 4.1.1 정식판을 배포할 경우 versionCode를 412 이상으로 올린다. 버전 비교는 같은 4.1.1의 베타에서 정식으로 전환하는 업데이트도 허용한다. 이번 요청은 정식 승격을 포함하지 않는다.

## 호환성 결정

* 핵심 문서·페이지·객체·설정의 JSON은 알려지지 않은 필드를 포함해 보존한다. 데이터베이스는 하나의 Room 소유자가 연다. 파괴적 마이그레이션을 사용하지 않는다.
* 새 자산은 임시 파일의 크기·SHA-256 재읽기와 형식 검증을 마친 뒤 확정하고 DB 참조를 연결한다. 이미지의 제한된 실제 디코드, PDF의 원문 파서·첫 페이지, 새 AAC 녹음의 실제 샘플을 확인한다. 구형 자산과 불투명 파일의 바이트는 보존한다. 녹음기 중지 이후의 파일 검증·확정·DB 연결은 저장 executor에서 처리하며 최신 문서의 해당 필드만 갱신한다.
* Android 앱 권한에서 하드링크가 거부되는 실제 오류를 확인해, 공통 프로세스 잠금 아래 새 이름을 독점 예약하고 같은 디렉터리에서 검증된 파일로 교체한다. 기존 이름은 덮어쓰지 않고 DB에는 완성 파일만 연결한다. 예약 직후 프로세스가 종료되면 DB에서 참조하지 않는 0바이트 파일이 남을 수 있어 자동 삭제하지 않는다. 파일 이동의 원자성을 전원 손실 시 보존 보장으로 표현하지 않는다.
* Android 6.0의 WebView 44에서도 구형 IndexedDB를 이전할 수 있도록 이전 전용 코드는 ES5 문법과 콜백을 사용한다. Blob은 제한된 청크를 FileReader로 읽고, 키 조회는 cursor를 사용한다. 구형 WebView의 FileReader 내부 `blob:` 요청은 이스케이프된 origin을 확인해 로컬 appassets origin일 때만 브라우저에 맡긴다. 파일·콘텐츠 접근과 외부 네트워크 응답 차단은 유지한다. 청크 크기와 최종 자산의 크기·해시·이미지 디코드를 검사하며 빈 자산이나 손상된 이미지는 게시하지 않는다. 49,152바이트를 넘는 PNG의 정확한 바이트 일치와 실패 시 임시 파일 정리를 검사한다. 이전 오류는 명시적으로 전달하며 원본 IndexedDB는 읽기 전용으로 보존한다.
* 이미 배포된 ZIP 버전 5 .ifnote의 기본 내보내기와 읽기를 유지하고, 3.x 호환 JSON 내보내기를 별도 선택으로 추가한다. 두 형식 모두 읽고 원본 자산과 페이지 참조를 보존한다. Kotlin 문서의 JSON 전용 가정으로 실제 4.0.1 사용자 파일을 잃지 않도록 하는 조정이다.
* ML Kit 후보는 원문과 모델 내 순위를 보존한다. 제공되지 않은 confidence는 null이다. 한영 혼합의 엔진 불일치는 검토 상태로 표시한다. 문자열 길이·한글 포함 여부로 정답을 정하지 않는다.
* Android 6.0에서도 ML Kit 의존 코드의 `Collection.stream()`이 동작하도록 core-library desugaring과 `desugar_jdk_libs` 2.1.5를 사용한다. Java/Kotlin 17 및 minSdk 23은 유지한다. 실제 API 23 OCR 재생에서 확인된 `NoSuchMethodError`를 빌드 단계에서 해결하며 인식 실패를 성공으로 바꾸지 않는다. [Android 공식 설정](https://developer.android.com/studio/write/java8-support)을 따른다.
* 새 자동 색인은 보이는 텍스트 객체를 추가하지 않는다. 수동 결과 미리보기·교정·복사·배치에 맞춘 삽입은 원본 획을 유지한다.
* 실제 시간과 획 순서가 없는 구형 입력은 시간 없는 Ink로 처리한다. 합성 시간을 실제 필기 시간으로 표시하지 않는다.
* 구형 Android PDF 검색도 원본 텍스트를 추출한다. 추출 실패를 이미지 OCR 성공으로 바꾸지 않는다.

## 요구사항과 구현 순서

검증 상태는 작업 기록의 requirement-matrix.md 및 최종 verification.json으로 관리한다. 아래 표는 구현 대상이며 통과 선언이 아니다.

| ID | 대상 | 필요한 증거 |
|---|---|---|
| N01 | 기준 소스·입력·기능 대응 | 커밋, 원본 SHA-256, 기준 기능 목록 |
| N02 | 호환 의존성·빌드 | 실제 Gradle 빌드, minSdk·패키지·APK 검사 |
| N03 | 기존 노트·설정·자산 이전 | v1 DB fixture, unknown 필드·ID·자산 비교, 실패 복구 |
| N04 | 브러시 수식·seed·pressure | 같은 입력의 기존 JS와 Kotlin 결과 비교 |
| N05 | S펜 입력·이력·취소·Undo | 실제 MotionEvent 회귀 및 공간 검색 테스트 |
| N06 | API 31+ front buffer·23–30 Canvas | handoff·취소·알파·화면 변화·surface 회귀, 구형 fallback |
| N07 | PDF 타일·원문 검색 | API 23–34/35+ 원문, 페이지 전환·캐시 상한·오류 |
| N08 | Room 변경 단위 저장 | 트랜잭션·순서·자산 최종화·실패 경로 |
| N09 | 두 .ifnote 형식·내보내기 | 대형 JSON/ZIP roundtrip, 자산·참조·취소 |
| N10 | 영역·줄·읽기 순서 | 제목·두 단·점·표선 fixture, 모든 획 보존 |
| N11 | 순서·시간·편집 세대 | Undo/Redo 포함 단조 세대, 오래된 결과 거절 |
| N12 | OCR 큐·모델 수명·상한 | 실제 Task 완료까지 permit, 병합·취소·지연 응답 |
| N13 | 후보·혼합 언어 | 원문·순위·최대 5개·null 점수·명시적 오류 |
| N14 | OCR sidecar·교정·캐시 | 재시작·검색·복제·이전·내보내기·바이트 LRU |
| N15 | 결과 UI | 진행·부분 완료·교정·복사·원본 획 유지 |
| N16 | 정답·평가·측정 도구 | 실제 도구 실행, 실패 분모·읽기 순서·출처 회귀 |
| N17 | Compose 일반 화면 | 기존 메뉴·디자인·언어·접근성·insets 실제 화면 |
| N18 | 녹음·공유·업데이트·설치 | 기존 사용 흐름 회귀 및 두 설치 변형 |
| N19 | 통합 회귀·독립 검토 | 현재 파일에 대한 테스트·lint·빌드·UI·리뷰 |
| N20 | 실제 필기·대상 기기 성능 | 실제 필기 정답, 물리 기기의 인식률·PSS·S펜·프레임 |

사용자는 현재 실기기와 필기 자료가 없으며 실기기 검증을 미측정으로 기록하도록 확인했다. N20은 이번 구현 완료 판정에서 보류한 측정 항목이다. 에뮬레이터 동작과 합성 부하 결과를 실제 필기 인식률이나 물리 S펜 지연의 증거로 사용하지 않는다.

S0에서 기준을 고정하고, S1에서 데이터와 세대, S2에서 분석과 OCR 호출, S3에서 결과 UI·검색·평가, S4에서 파일·PDF, S5에서 브러시·입력·Compose·출력 경로를 구현한다. S6에서 조립한 현재 소스 전체를 검증하고 실패한 항목을 수정한다.

## 평가 파일과 도구

실제 원본·출력을 사용자가 명시적으로 내보낸 뒤 로컬에서 평가한다. 일반 오류 로그에 원본 획과 인식 문장을 남기지 않는다.

```powershell
python tools/annotate_ocr.py page.ink.json --output build/ocr/page.annotation.html
python tools/evaluate_ocr.py samples.jsonl --mode selected --output build/ocr/quality.json
python tools/evaluate_ocr.py samples.jsonl --mode raw --output build/ocr/raw-quality.json
python tools/evaluate_ocr.py samples.jsonl --mode corrected --output build/ocr/corrected-quality.json
python tools/evaluate_ocr.py page.truth.json --results page.result.json --output build/ocr/page-quality.json
python tools/bench_ocr.py generate --workload W1 --output build/ocr/load-w1
python tools/bench_ocr.py replay --device SERIAL --input build/ocr/load-w1/synthetic-W1-0.ink.json --path B2 --workload W1 --state warm --output build/ocr/replay-w1
python tools/bench_ocr.py summarize recorded-runs.json --output build/ocr/timings.json
python tools/test_ocr_evaluation.py
python tools/test_ocr_tools.py
```

조립이 끝난 소스의 통합 검사는 tools/run_native_validation.py로 수행한다. tools, web, build, instrument, replay, apk, upgrade, diff 단계가 전체 로그와 파일 SHA-256 영수증을 build/native-validation 아래 남긴다. 실행별 attempt 폴더를 만들어 재시도가 이전 실패 로그를 덮어쓰지 않는다. web은 기존 웹·입력·내보내기 회귀를 모두 실행한다. 검사 전후 소스가 바뀌면 실패로 처리한다. Android 계측은 명시한 시험 기기의 debug 앱만 대상으로 하고, 공개 APK를 설치하거나 릴리스를 발행하지 않는다. 이 로컬 영수증 자체를 ADHD native 검증 승인으로 표현하지 않는다.

OCR Task 수명과 PDF는 각각 `--instrument-mode ocr`, `--instrument-mode pdf`로 별도 실행한다. 기본 계측 성공만으로 두 검사를 실행했다고 간주하지 않는다. 최종 APK는 설치 변형별 패키지·출시 서명·minSdk 23을 검증하고, PDFium 준비 명세와 실제 3개 ABI 코어 및 고지 파일의 SHA-256을 비교한다.

입력·지우개·단일 접촉 Undo 검사는 `--instrument-mode s5a`로 별도 실행한다. 실제 S펜 동작의 지연 측정은 에뮬레이터 입력 계약 검사와 구분한다.

계측의 종료 코드만으로 통과를 판단하지 않는다. 검사별 완료 marker, `passed=true`, `INSTRUMENTATION_CODE: 0`이 함께 있어야 하며 충돌·오류·상충하는 실패 결과는 거부한다.

`--instrument-mode front`는 완료된 계측이 보고한 해당 실행의 private 디렉터리에서 실제 합성 화면 PNG를 회수해 해시를 기록한다. API 23–30의 Canvas 경로는 저장된 두 화면의 동일한 좌표가 흰 페이지에서 어두운 잉크로 바뀌었는지 호스트에서도 검사한다. 앱 창에 포커스가 있다는 것만으로 런처 화면이 이미 사라졌다고 가정하지 않는다. API 31 이상은 실제 Surface transaction의 committed listener와 세대 검사를 사용한다. API 29–30에는 graphics-core 1.0.4 공개 API의 동등한 준비 통지가 없어 일반 Canvas 경로를 유지한다. 부모 SurfaceView의 PixelCopy 오류와 실제 합성 화면 검사는 따로 기록한다.

설치 업데이트는 별도로 보관한 실제 서명 4.0.1 APK에서 베타 411/4.1.1-beta.1 APK로 검사한다. 두 설치 변형의 인증서·패키지가 일치하고 버전 코드가 증가해야 한다. 격리된 시험 에뮬레이터의 이전 앱에서 화면으로 실제 문서와 자산을 만든 뒤, 앱을 중단해 DB·WAL·자산을 보관하고 `install -r` 후 다시 연다. `check_release_upgrade.py`는 이렇게 보관한 v1/v2 DB의 원래 네 테이블과 자산 바이트를 비교한다. 원본 값·알 수 없는 필드·압력 0/null·배열 순서를 보존해야 하며 추가 메타데이터는 허용한다. 빈 데이터나 자산 없는 기록은 업데이트 증거로 거부한다. 이 비교 도구의 단위 시험은 실제 APK 업데이트를 대신하지 않는다.

```powershell
python tools/check_release_upgrade.py --before build/release-upgrade/update/before --after build/release-upgrade/update/after --out build/release-upgrade/update/comparison.json
python tools/test_release_upgrade.py
```

원본 브러시의 그리기 명령은 `capture_brush_reference.mjs`로 보관하며, `render_brush_reference.py`는 그 명령을 격리된 실제 Chrome Canvas에 재생한다. 원본 웹 소스 해시가 달라지면 실행을 거부한다. 1024×1024 흰색/옅은 파란색 체크 배경에서 같은 이동·배율·원본 획으로 36개 PNG를 만든다. 네이티브 PNG는 같은 조건에서 실제 `LegacyCanvasBrush.draw`로 별도 생성해야 한다.

```powershell
node tools/capture_brush_reference.mjs --output build/brush-reference/source-new.json
python tools/render_brush_reference.py --fixtures build/brush-reference/source-new.json --out build/brush-reference/chrome-new
python tools/compare_brush_rasters.py --reference build/brush-reference/chrome-new --native build/brush-reference/native --out build/brush-reference/comparison-new.json
```

최종 조립 후 `--instrument-mode brush`는 원본 JS 명령을 다시 캡처해 보호된 fixture와 비교하고 Chrome 기준 PNG를 새로 생성한다. 그 manifest를 시험 앱 private 디렉터리로 전달한 뒤 36개 실제 네이티브 Bitmap을 생성·회수하고 픽셀 비교까지 실행한다. 계측 marker만 통과해도 픽셀 비교가 실패하면 전체 단계는 실패다.

비교 허용치는 네이티브 결과를 얻기 전에 고정한다: 배경 대비 획의 RGB 변화량 합 차이 8% 이하, 기준 획 변화량으로 정규화한 전체 RGB 픽셀 오차 12% 이하, 양방향 획 픽셀 대응률 99% 이상이다. 픽셀 대응에는 1픽셀 가장자리 차이를 허용하며 배경 대비 채널 변화 8 이상을 획으로 센다. 단순 흰 배경의 전체 이미지 평균으로 작은 획 차이를 희석하지 않는다. 같은 이미지 통과 및 획 삭제·3픽셀 이동 거부는 비교 도구 자체의 검사이며, Android 출력 비교로 기록하지 않는다. 이 기준은 합성 브러시 회귀에만 사용한다.

정답 작성 HTML은 네트워크 요청 없이 원본 획과 ID를 표시한다. 모든 유효 획을 text, nonText, unreadable 중 하나에 배정하고 실제로 쓴 문장·읽기 순서·작성자·세션·기기를 저장한다. 원본 파일을 덮어쓰지 않는다. 다운로드한 .truth.json은 런타임 결과와 합쳐 평가 입력으로 사용한다.

평가 샘플의 최소 계약:

```json
{
  "schemaVersion": 1,
  "sampleId": "writer-01-page-001",
  "pageDigest": "실제 원본 버전의 digest",
  "provenance": {
    "kind": "real",
    "writerId": "writer-01",
    "sessionId": "session-02",
    "deviceId": "device-01",
    "language": "mixed"
  },
  "validStrokeIds": ["stroke-1"],
  "truthRegions": [
    {"id": "truth-1", "role": "text", "strokeIds": ["stroke-1"], "text": "실제로 쓴 문장"}
  ],
  "readingOrder": ["truth-1"],
  "path": "B2",
  "result": {
    "pageDigest": "실제 원본 버전의 digest",
    "status": "complete",
    "regions": [
      {"id": "region-1", "strokeIds": ["stroke-1"], "rawText": "엔진 원문", "selectedText": "선택 결과"}
    ]
  }
}
```

result.readingOrder가 있으면 그 ID 순서를, 없으면 regions 배열 순서를 그대로 평가한다. 기하 위치로 틀린 읽기 순서를 고치지 않는다. NFC·공백 포함 CER가 주지표이며 자모(NFD)·공백 제외 결과를 함께 기록한다. 대소문자·숫자·문장부호를 바꾸지 않는다. 동률은 match→substitute→delete→insert 순으로 결정한다.

실패·오래된 digest·미인식 영역은 정답 분모에서 빠지지 않는다. 사용자 취소는 따로 보고한다. 빈 정답의 CER는 null, 허위 텍스트 발생률은 별도다. 획 ID의 정확한 일대일 대응으로 줄 완전 일치율을 계산해 줄 병합·분할을 불일치로 센다. CER와 정답 문자 보존율은 서로 다른 지표다.

generate는 W0–W3에 해당하는 합성 부하만 만든다. 필기 정답이나 ML Kit 출력은 생성하지 않는다. 합성·에뮬레이터 결과와 실제 필기·물리 기기 결과는 보고서에서 분리한다.

replay는 명시한 기기에 이미 설치된 debug 앱으로 한 페이지를 실행한다. 설치된 APK를 읽어 실제 SHA-256을 기록하고, 입력을 앱의 private 디렉터리에 직접 전달한다. 원본 획과 인식 문장은 명시적으로 생성한 diagnostic.json에만 보관하며 일반 콘솔 로그에는 쓰지 않는다. B1은 제공한 정답 영역, B2는 자동 배치의 원문 1순위, B3는 명시한 policy 또는 cache 실험 경로다. 혼합 정책은 --policy mixed-review로, 캐시 실험은 --feature cache로 선택한다. 과거 B0 구현이 없으면 baselineUnavailable로 기록하고 측정 비교에 넣지 않는다. state는 운영자가 실제 준비 상태에 맞게 선언하며 런타임 모델 상태와 대조한다. 호스트 계측 명령 시간은 런타임 인식 시간과 따로 기록한다. 합성 부하의 빈 정답 영역은 언어 인식률 평가에 사용하지 않는다.

캐시 실험의 totalMs는 예열이 끝난 두 번째 coordinator 요청이다. 예열과 준비를 포함한 전체 시간은 replayTotalMs로 별도 보관한다. summarize는 정책·캐시 기능·입력 종류·측정 구간이 다른 기록을 서로 다른 묶음으로 집계한다. 서로 다른 구간의 시간을 섞어 속도 향상으로 표시하지 않는다.

이미 실행 중인 시험 앱을 읽기 전용으로 측정하는 예:

```powershell
python tools/bench_ocr.py collect --device SERIAL --phase ocr-off --page-digest DIGEST --workload W1 --path B2 --temperature-state stable --warm-state warm --output build/ocr/memory-off
python tools/bench_ocr.py collect --device SERIAL --phase ocr-on --page-digest DIGEST --workload W1 --path B2 --temperature-state stable --warm-state warm --output build/ocr/memory-on
```

메모리 관측은 인식 시간 측정과 별도 실행한다. collect는 프로세스·TOTAL PSS/RSS·프레임 원본 증거를 보관하며 앱을 초기화하거나 모델을 내려받지 않는다. 측정은 앱 프로세스 범위다. 온·오프의 렌더 상태·모델 warm 상태가 같은지 별도로 확인해야 한다. summarize 입력은 각 실제 replay의 deviceKind, deviceId, buildSha, inputDigest, path, workload, state, status, totalMs를 요구한다. 100회 미만 또는 입력 다양성이 없는 p95는 탐색값이다.

replay의 `--state warm`은 같은 계측 프로세스와 인식기에서 준비 실행을 요청한다. 결과의 processId·warmupPerformed·warmupStatus·warmupMs가 실제 준비 완료를 증명해야 warm 시간으로 집계한다. 준비 시간은 측정 실행 시간과 분리한다. B3 cache의 주 시간은 캐시를 준비한 뒤의 요청이며, 준비를 포함한 전체 replay 시간도 별도로 보관한다. 이전 S3 에뮬레이터 실행에서 사용자가 선언한 warm 라벨만 있는 기록은 이 조건을 충족하지 않는다.

## 의존성 근거와 남은 실측

현재 툴체인의 호환 후보는 Compose compiler 2.3.0 / BOM 2026.06.01, Room 2.8.5, KSP 2.3.10, coroutines 1.11.0, graphics-core 1.0.4다. 실제로 도입한 버전은 Gradle 설정을 기준으로 확인한다.

PdfiumAndroidKt 1.0.34는 AAR에 minSdk 23을 표기하지만 core ELF의 실제 선언 API가 26이므로 채택하지 않는다. 구형 Android 원문 추출에는 bblanchon/pdfium-binaries의 chromium/8086 릴리스(PDFium 157.0.8086.0)를 고정하고 필요한 JNI 코드를 API 23으로 빌드한다. tools/pdfium.lock.json은 공식 릴리스 archive URL·SHA-256·크기를 고정한다. tools/prepare_pdfium.py로 내려받은 3개 ABI의 core API는 23이며, 64비트 PT_LOAD 정렬은 16KB다. 32비트 ABI는 4KB다. PDFium 및 제3자 21개 고지를 함께 준비한다. 다운로드 archive의 검증과 최종 APK·실제 구형 OS 실행 검증은 서로 구분한다.

```powershell
python tools/prepare_pdfium.py
python tools/generate_pdf_fixtures.py --output build/ocr/pdf-fixtures-new
python tools/generate_pdf_fixtures.py --output build/ocr/pdf-complete-fixtures --stress-mib 100 300
python tools/test_pdfium_prepare.py
python tools/test_apk_native.py
```

PDF 시험 자료 생성은 설치된 PyMuPDF·pypdf를 사용한다. 한글 글꼴을 부분 포함하고 원문·회전·잘린 페이지·빈 페이지·암호화·손상·500페이지 파일을 만든다. 한글·영문 및 대형 파일의 모든 페이지를 별도 파서로 검사하며, 기존 출력 폴더는 덮어쓰지 않는다. Windows의 설치된 맑은 고딕을 기본으로 사용하고 다른 환경에서는 --font로 한글을 지원하는 설치 글꼴을 지정한다. 이 파일들은 원문 추출·수명 검사용 합성 자료이며 필기 정확도 자료가 아니다.

`--stress-mib 100 300`은 페이지별 원문과 RGB 이미지가 있는 약 100.5MiB·300MiB PDF도 만든다. 파일 전체를 메모리에 올리지 않고 64KiB씩 작성한다. 모든 페이지의 원문·이미지 참조를 독립 파서로 검사하고 첫·마지막 페이지를 렌더한다. `.ifnote`의 대형 자산 내보내기 검사와 PDF 자체의 대형 파일 가져오기 검사를 구분한다.

실제 Android 대형 PDF 검사는 `run_native_validation.py instrument --device SERIAL --instrument-mode pdf100` 또는 `pdf300`에 `--pdf-fixtures build/ocr/pdf-complete-fixtures`를 함께 지정한다. 해당 파일 하나와 manifest만 앱 private staging으로 전달한다. 첫 페이지 준비, 전체 원문 색인, 실제 앞뒤 타일 렌더, 삭제 및 FD 수명을 검사한다. `pdf-cancel`은 100MiB 가져오기 직후 삭제한 문서가 늦은 색인으로 되살아나지 않는지 별도로 검사한다. 생성 파일의 크기와 별도 파서 통과만으로 Android 가져오기 성공을 선언하지 않는다.

W3 검사는 `--instrument-mode w3 --w3-fixtures build/ocr/load-w3`로 실행한다. 300개 전체 페이지를 영속 저장하고 실제 편집기의 앞뒤 페이지 탐색과 한 W1 크기 페이지 OCR을 확인한다. 매 실행의 runId와 ready index를 확인해 원본 파일 하나씩 SHA-256 검증 후 원자적으로 전달한다. 이전 실행의 요청, 페이지 건너뛰기 또는 순서 되돌림을 거부한다. 전체 입력을 앱 private 폴더에 중복 보관하지 않는다. 완료 영수증은 300페이지·600,000획·12,000,000점과 OCR 페이지의 2,000획을 실제 저장/조회 결과로 요구한다. 이 부하는 손글씨 정확도 자료가 아니다.

일반 화면 검사는 `--instrument-mode ui`로 실제 Compose 접근성 버튼을 누르고 한국어/영어 라이브러리와 설정 화면을 회수한다. 같은 실행의 private 디렉터리와 완료 marker에 묶인 PNG만 보관한다. 소스의 번역 문자열 유무는 실제 언어 전환·저장·재진입 검사를 대신하지 않는다. API 35·23·29 검사는 task-owned 에뮬레이터를 순차 전환하며, 다른 기기의 데이터나 실행 상태는 바꾸지 않는다.

오디오 호환 시험 파일은 설치된 ffmpeg·ffprobe로 만든다.

```powershell
python tools/generate_media_fixtures.py --output build/media/compat-fixtures-new
```

AAC/M4A, Vorbis·Opus의 Ogg/WebM, 확장자가 사라진 구형 AAC 파일과 손상 파일을 생성하고 컨테이너·코덱·바이트 해시를 기록한다. Android에서는 실제 재생과 비동기 오류·클립 교체·화면 종료 시 자원 해제를 따로 검사해야 한다. 이 합성 음원은 마이크 품질을 검증하지 않는다. 재생 지원 근거는 [Android 미디어 형식](https://developer.android.com/media/platform/supported-formats)과 [MediaPlayer 상태·오류 처리](https://developer.android.com/reference/android/media/MediaPlayer)다.

근거: [Compose 설정](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [Compose BOM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom), [Room 릴리스](https://developer.android.com/jetpack/androidx/releases/room), [KSP](https://kotlinlang.org/docs/ksp-overview.html), [Graphics 릴리스](https://developer.android.com/jetpack/androidx/releases/graphics), [고정 PDFium 릴리스](https://github.com/bblanchon/pdfium-binaries/releases/tag/chromium%2F8086), [NDK의 구형 OS 지원 조건](https://developer.android.com/ndk/guides/common-problems#wrong_platform), [Android 16KB 페이지](https://developer.android.com/guide/practices/page-sizes).

시작 시 연결된 실제 Android 기기와 사용자 정답 필기 데이터가 없었다. 따라서 실제 한글·영어·혼합 CER, S펜 입력 지연, 대상 기기 peak PSS·주사율·발열·장시간 프레임 조건(N20)은 아직 미측정이다. 합성 fixture·빌드·에뮬레이터 통과를 이 조건의 통과로 표시하지 않는다.

원본 웹 브러시 명령 36개를 Chrome Canvas와 Android Bitmap에 실행한 비교에서는 미세한 단일 점 9개가 사전에 고정한 픽셀 허용 기준을 넘었다. 비교 기준은 변경하지 않았다. 동일 명령을 투명 캔버스에 실행한 추가 진단에서도 세 대표 사례의 원시 alpha가 달라, 색상 합성만의 차이로 설명할 수 없다. 내부 가장자리 래스터 처리 차이가 주된 원인으로 추정되며, 좌표·반경·불투명도를 임의 보정하지 않았다. 이 진단과 산술·입력·화면 검사의 통과를 픽셀 비교의 통과로 간주하지 않는다. 최종 실행별 결과와 파일 해시는 로컬 검증 영수증에 기록한다.
