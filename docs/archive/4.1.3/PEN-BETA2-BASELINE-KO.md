# 공개 Beta 2 성능 검증 이력

> 과거 배포·설계 기록입니다. 현재 작업 기준은 [문서 안내](../../README.md)를 확인하십시오.

## 원래 Beta 1 → Beta 2 검증 기록

대상 원본은 `9112b7d`의 `4.1.3-beta.1`이다. 필압·시간·기울기·포인터와 historical samples, 브러시의 최종 출력, 저장·실행 취소·다시 실행을 유지한다. 런타임 의존성과 데이터베이스 스키마는 추가하지 않는다.

## 재현과 합격 기준

실제 `InkCanvasView.onTouchEvent`에 한 획의 1,024개 표본을 전달한다. 완전한 MOVE 이벤트에는 과거 표본 8개와 현재 표본 1개가 들어간다. 첫 1회를 준비 실행하고 다음 3회의 p95 중앙값을 비교한다. 저장된 1,024개 표본의 시간·필압·기울기·방향·포인터를 검증한다.

입력 처리와 화면 제출 사이를 구분하기 위해 이 측정은 동기식 표면 수신기를 사용한다. GPU 완료, 화면 주사와 실제 Galaxy/S Pen 지연은 측정하지 않는다. 별도의 실제 SurfaceView·화면 픽셀·획 전환 검사로 표시 동작을 확인한다.

성능 합격 기준은 동일 기기·작업량에서 입력 처리 p95가 50% 이상 줄고 8ms 미만인 것이다. 브러시 참조·네이티브 회귀·lint·APK 검증도 통과해야 배포한다. 통과 전 수치를 추정해 기재하지 않는다.

```powershell
python tools/evaluate_ink.py --device emulator-5580 --output build/pen-performance/baseline.json
python tools/evaluate_ink.py --device emulator-5580 --baseline build/pen-performance/baseline.json --output build/pen-performance/evaluation.json
```

## 확인된 병목과 수정 가설

원본은 historical sample마다 전체 획을 JSON 문자열로 직렬화하고, 렌더러에서 다시 파싱한다. 따라서 한 이벤트에서 누적 획 전체를 여러 번 복사·제출한다. 모든 표본을 먼저 보존한 뒤 이벤트당 한 번 불변 프레임을 제출하면 중복 작업을 줄일 수 있다.

수정본은 브러시 설정을 획 시작에 한 번 복사하고 좌표 목록만 이벤트마다 고정한다. 그리기 작업자는 대기 중인 최신 전체 스냅샷을 소비하므로 오래된 화면 요청이 누적되지 않는다. 원시 입력이나 저장되는 좌표를 삭제하는 방식은 사용하지 않는다. `NoteRenderer.stroke`는 좌표를 `JSONObject`로 만들지 않고 브러시에 직접 전달한다. 기존 JSON 복사의 Float 소수점 정규화 시점까지 보존하며, 7종 브러시 × 4종 표본 구성 × 2개 배율의 56개 명령 비교가 기존 오차 기준으로 통과했다.

원본은 이미 표시 중인 전면 버퍼를 CLEAR한 뒤 전체 획을 재생한다. 또한 이전 획의 commit이 끝나기 전에도 새 전면 획을 받는다. 전체 획 교체와 commit 중 입력 표시를 각각 보호해야 한다. 전면 전체 재생은 브러시의 표본 수에 따른 테이퍼와 투명도 순서를 보존하기 위해 유지한다.

`Canvas.saveLayer`에서 전체 획을 재생하고 `BlendMode.SRC`로 복원하면 투명 픽셀까지 교체할 수 있다. 추가 offscreen target은 대략 화면 너비 × 높이 × 4바이트이며 렌더링 비용이 증가할 수 있어 별도 비교가 필요하다. [Canvas 공식 문서](https://developer.android.com/reference/android/graphics/Canvas#saveLayer(float,float,float,float,android.graphics.Paint)), [SRC 공식 문서](https://developer.android.com/reference/android/graphics/BlendMode#SRC), [commit 공식 문서](https://developer.android.com/reference/androidx/graphics/lowlatency/CanvasFrontBufferedRenderer#commit()).

## 실행 기록

- 원본 앱 코드에 측정용 instrumentation만 추가해 APK를 빌드했다. 기준 APK는 `build/pen-performance/baseline-apk/`에 보존한다.
- 첫 기준 검사에서 테스트 전용 미저장 페이지의 capture sequence 등록이 외래 키 오류로 실패했다. 실제 저장된 테스트 페이지를 사용하는 방식으로 테스트 설정을 수정했다. 앱의 외래 키 검사를 완화하지 않았다.
- 현재 native harness는 과거 4.1.1 실행의 `interrupt_pending` 소유권 때문에 새 begin을 거절했다. 기존 상태를 임의 변경하지 않고 performance-goal과 원본 scope 기록, 실제 실행 로그 및 별도 독립 검토로 진행한다. native acceptance를 받았다고 주장하지 않는다.
- 첫 추가 브러시 회귀 검사에서 짧은 획의 Float 정규화 차이를 발견했다. 허용 오차를 늘리지 않고 원본 JSON 처리 순서를 재현해 수정했다. 이후 단위 테스트 63개와 lint, 두 release 변형 및 debug instrumentation 빌드가 통과했다.
- 기존 화면 회귀 검사에서 전면 획 취소 후 다음 획이 일반 Canvas로 넘어가는 문제를 발견했다. 취소가 이미 표시된 canonical scene의 준비 상태를 내리지 않도록 수정했다. 픽셀 제거와 취소 횟수 검사는 유지하고, 다음 획이 실제 전면 버퍼에서 그려지는지까지 추가 검증한다.
- 에뮬레이터의 DEX 로딩 중 시작 ANR은 이전 공개 APK에서도 관측됐다. 배포 패키징·보안 검사·Android watchdog을 바꾸지 않고 테스트 기기에만 `cmd package compile -m verify -f`를 적용했다. 이 준비는 AOT 컴파일을 적용하지 않는다. [ART 공식 문서](https://developer.android.com/topic/performance/memory/guide/app-code#art-compilation-modes).
- 이후 에뮬레이터에서 RenderThread 대기와 Android 시스템 UI의 응답 지연도 관측했다. 데이터 삭제나 snapshot 복원 없이 에뮬레이터를 재시작하고 그래픽 백엔드·표시 크기를 바꿔 설치 화면을 검사했다. 이 준비는 배포 APK에 포함되지 않으며, 앞의 입력 처리 측정과 별개의 화면 검증 환경이다.

## 입력 처리 측정 결과

Android 15 x86_64 에뮬레이터 `emulator-5580`, 동일한 1,024개 입력과 준비 실행 1회·측정 3회를 사용했다. 성능 측정은 앱의 실제 입력 메서드를 호출하는 `UpdateDebug` + AndroidTest APK로 수행했다. 서명한 release APK의 설치·화면 확인은 별도 검사다. 값은 각 실행의 MOVE 처리 p95이며 마지막 열은 세 p95의 중앙값이다.

| 항목 (ms) | 실행 1 | 실행 2 | 실행 3 | 중앙값 |
| --- | ---: | ---: | ---: | ---: |
| Beta 1, 경과 시간 | 407.221 | 653.322 | 7,279.947 | 653.322 |
| Beta 2, 경과 시간 | 13.254 | 3.346 | 3.097 | 3.346 |
| Beta 1, 스레드 CPU 시간 | 382.695 | 581.139 | 3,960.967 | 581.139 |
| Beta 2, 스레드 CPU 시간 | 8.451 | 1.351 | 0.671 | 1.351 |

동결한 평가식에서 p95 중앙값은 99.49% 감소하고 8ms 미만으로 통과했다. 세 실행 모두 1,024개 표본을 저장했다. 한 획의 전면 갱신 요청은 1,024회에서 115회(DOWN 1회, MOVE 114회)로 줄었다. Beta 2의 측정 실행별 최대 처리 시간은 26.996 / 26.383 / 31.360ms였다.

원본의 측정 편차가 크고, 빌드 종료 후 추가한 원본 재측정은 600초 제한을 초과했다. 따라서 99.49%는 보존한 기준 실행과의 비교이며 일반 기기에서의 개선율로 해석할 수 없다. 수정본도 한 실행의 p95는 8ms를 넘었다. 합격 기준은 사전에 정한 세 p95의 중앙값이다. 실제 S Pen·GPU·화면 주사 지연과 장시간 필기 체감은 별도 실기기 검증이 필요하다.

기록: `build/pen-performance/baseline.json`, `evaluation.json`, `evaluation.log`, `build/pen-cancel-reuse-build.log`.

## 빌드와 회귀 검사

- 단위 테스트 63개, 15개 suite: 실패·오류·건너뜀 0. 브러시 명령 동등성 56개 조합 포함.
- `lintUpdateDebug`: Error/Fatal 0, Warning 105. 이번 펜 처리 수정 범위 밖 경고의 정리는 수행하지 않았다.
- 실제 SurfaceView와 화면 픽셀: 교체 raster 42건, 최신 스냅샷 128건, 이전 commit 지연 중 다음 획 표시·빠른 연속 획·취소·실행 취소/다시 실행 통과.
- 기존 전면 버퍼 회귀: 취소 직후 다음 획의 전면 재사용, 투명도 재생, 페이지 경계, highlighter의 일반 Canvas 전환, 확대, 표면 재생성·가로/세로 회전 통과.
- 기존 UI 탐색과 필기 중 OCR 작업 대기·원시 입력 저장 검사도 통과했다. 이번 실행은 정해진 펜 관련 검사 5개이며, 모든 과거 native 검사 전체를 재실행한 결과는 아니다.
- Update와 SideBySide release: 동일 인증서, versionCode 414, 최소 API 23·대상 API 36, 서명·ZIP CRC·16KB 정렬·고정 PDFium 일치 검증 통과. OCR 베타 설정 두 항목도 활성 상태를 확인했다.
- 고정 PDFium 검증은 manifest·lock 파일의 SHA와 두 최종 APK의 ABI별 라이브러리·notice 바이트를 직접 대조했다. `pdfium-identity-final.json`에 expected/observed SHA를, `native-execution-provenance.json`에 최종 instrumentation 로그·APK의 SHA를 기록했다.

정해 둔 입력 처리·브러시·표시 회귀와 아래 실제 업데이트 검사가 통과해 이번 최적화를 채택했다. 실물 S Pen의 체감 지연은 별도 미측정 범위로 남긴다.

## 실제 서명 APK 업데이트

공개된 Beta 1 APK를 설치하고 화면에서 만든 데이터를 보존한 채, 최종 서명 Beta 2 APK를 `adb install -r`로 설치했다. 두 패키지 모두 versionCode 413→414, DB v2→v2이며 아래 기존 행의 모든 SQL 열과 JSON 값·배열 순서를 비교했다.

| 변형 | 문서 | 페이지 | 기존 객체 | 설정 | 이미지 자산 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Update | 1 | 1 | 펜 획 1 | 4 | 0 |
| SideBySide | 1 | 1 | 펜 획 1 + 이미지 1 | 4 | 1 |

Update에서는 업데이트 후 원본 획을 확인하고 새 펜 획을 저장했다. 앱을 강제 종료·재실행한 뒤 두 획이 함께 보이는 화면과 DB 객체 증가, 원본·신규 획의 저장 본문 보존을 확인했다.

SideBySide에서는 Beta 1의 파일 선택기로 `web/icons/icon-192.png`를 실제 첨부했다. Beta 2 설치 전후 및 새 앱에서 문서를 연 후에도 이미지 9,449바이트의 SHA-256 `121cc8fcdd31de6eeb745b9b1734f92b8c532ee8a262ea0223ea94a9eec4aa0d`가 같고, 기존 획과 이미지가 함께 표시됐다. 기존 `check_release_upgrade.compare`의 비어 있지 않은 자산 조건을 그대로 적용했다. Update의 이미지 첨부 fixture는 별도로 실행하지 않았다.

설치 화면 검증은 같은 Android 15 AVD에서 데이터 삭제·snapshot 복원 없이 `-gpu host`, 표시 720×1600·density 280으로 수행했다. 입력 처리 측정과 펜 표시 instrumentation은 앞서 수행한 별도 환경의 결과다. 기록은 `release-upgrade-data.json`, `release-upgrade-ui.json`, `sbs-upgrade-asset.json`과 각 receipt에 연결한 실제 화면·DB 캡처에 남겼다.

## 배포 APK 식별

| 변형 | 패키지 | SHA-256 |
| --- | --- | --- |
| Update | `com.inkforge.note4` | `a88f21b4f482138e80eb4a11268851395b5b17fee4cfdcb1cfa3f9d9c6dbe563` |
| SideBySide | `com.inkforge.note5` | `f128f0792da3f19c992d18c3eee7b7d4b14a2ec0a199272505880f0bece47b42` |
