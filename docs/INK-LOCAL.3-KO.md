# 필기 캐시 복구와 부하 검증 — local.3

현재 작업 버전은 4.1.3-beta.2-local.3 / versionCode 417이다. 로컬 소스와 APK만 변경하며 원격 저장소·릴리스에는 반영하지 않는다.

## 사라짐 원인과 수정

Android가 RenderNode의 그리기 명령을 회수한 뒤에도 Java 객체는 남는다. 기존 코드는 객체가 있으면 명령도 유효하다고 가정했다. 페이지 전체·개별 획·쓰는 중인 획의 안정된 구간에 같은 문제가 있었다. Android의 [hasDisplayList 계약](https://developer.android.com/reference/android/graphics/RenderNode#hasDisplayList())은 그리기 전에 유효성을 확인하고, 비어 있으면 다시 기록하도록 요구한다.

- 페이지와 개별 획의 비워진 명령만 다시 기록한다. 기존 Picture와 원본 필기는 보존한다.
- 상위 화면 명령이 남아 있어도 하위 획 명령이 회수됐는지 확인한다.
- 쓰는 중인 획은 기존 보정 좌표·Path로 회수된 구간만 다시 기록한다.
- 메모리 예산, 화면 타일, 변경 객체 갱신, 비동기 비트맵 생성은 유지한다. 획 수 제한으로 필기를 숨기지 않는다.
- 정밀 지우개가 공간 인덱스 예산을 소진했을 때, 실제로 닿지 않는 후보는 표본 객체를 만들기 전에 기존 충돌 판정으로 제외한다.
- 반복 지우기의 변하지 않은 점은 정규화된 불변 표본을 재사용한다. 중간 교차점은 수치 보간을 그대로 수행하고, 최종적으로 남은 점의 JSON만 만든다. 압력·시각·기울기·확장 필드와 부호 있는 0의 기존 처리 결과를 비교한다.
- 같은 지우개 입력 안에서 바뀌지 않은 인접 점 쌍은 이벤트 전체 영역과의 불교차 판정을 재사용한다.
- 넓은 획 후보를 매 MOVE마다 새 해시 집합으로 복사하지 않는다. 기존 후보 순서와 격자 중복 제거는 유지하고, 최종 결과 목록만 만든다.

## 화면 검사 보정

기존 HardwareRasterOracle은 RenderNode 초기화 안에서 width/height를 잘못 참조하여 0×0 화면을 만들었다. 빈 이미지끼리 같다고 판정할 수 있었으므로 과거의 해당 GPU 픽셀 비교 결과는 유효한 근거로 사용하지 않는다. 창 스크린샷·소프트웨어 렌더링·원본 데이터 검사와는 구분한다.

검사 장치는 명시한 크기를 사용하고 실제 프레임을 기다려 독립 비트맵으로 읽는다. 검정·흰색 화면을 구별하는 사전 검사에 실패하면 비교를 시작하지 않는다. 이 보정을 적용한 펜 검사는 GPU 3,248건, 긴 획 378건, 반복 투명도 42건과 연속 접촉·취소·실행 취소를 통과했다. 기록: `build/ink-recovery-pen.log`.

수정 전 local.2에서는 강제 캐시 회수 뒤 페이지·개별 획과 6종의 펜에서 누락을 재현했다. 수정판은 하위 명령만 회수되는 경우를 추가한 27개 비교를 통과하고, Picture 재생성 횟수와 기존 Path 참조도 유지했다. 기록: `build/ink-recovery-baseline.log`, `build/ink-recovery-fixed.log`.

확장 검사에서는 1,024획 및 6,144획×32점, 6종 펜, 일반/전면 렌더러, 9,001점의 긴 획을 검사했다. 48MiB 표시 명령 예산을 넘겨 일부 획이 비캐시 경로로 그려지는 경우와 8,192점의 진행 중 경로 캐시 한도를 넘기는 경우를 포함한다. 독립적으로 새로 그린 원본·회수 전후·다음 점 추가 후의 실제 GPU 픽셀 40개 비교가 모두 일치했다. 기록: `build/ink-recovery-over-budget.log`.

## 성능 측정용 분리 앱

`profile` 빌드 유형은 release 설정을 상속하고 디버깅을 끈다. 패키지와 서명은 기존 검사 앱의 `com.inkforge.note4.debug`와 debug 키를 사용하므로 사용자용 release 앱에 덮어쓰지 않는다. Android [성능 측정 안내](https://developer.android.com/studio/profile)는 디버그 빌드의 실행 비용 때문에 release 기반 측정을 권한다.

    cd android
    ./gradlew.bat -PbadnoteTestBuildType=profile -PbadnoteOcrBeta=true :app:assembleUpdateProfile :app:assembleUpdateProfileAndroidTest

산출물은 루트 `build/apk/*-Profile.apk`와 `build/apk/tests/*-Profile-AndroidTest.apk`다. 일반 Debug·release 생성 명령은 그대로다. 검사 결과에 빌드 유형, debuggable 여부, 추적 도구 사용 여부를 기록한다. 내부 호출을 기록한 실행의 시간은 추적을 끈 실행과 직접 비교하지 않는다.

## 검증 상태

2,048획×64점과 4,098점의 추가 필기, 획 지우개·정밀 지우개를 실제로 연결된 에뮬레이터 편집 화면에서 검사했다. 최종 Debug 실행은 원본·순서·보호 획·접촉당 실행 취소/다시 실행/취소·저장 데이터·GPU/소프트웨어 픽셀 비교를 통과했다. 기록: `build/ink-recovery-filled-final.log`, `build/ink-recovery-filled-final.json`.

이 Debug 실행의 정밀 지우개 MOVE p95는 245.98ms로, 끊김 없음의 통과 결과가 아니다. 검사 환경의 메모리 압박도 관측되어 앞선 실행과 단순 성능 비교하지 않는다. 또한 화면 원본 비교·해시 계산 같은 검사 작업이 섞인 구간의 윈도우 프레임 시간은 제품의 평상시 프레임 시간으로 해석하지 않는다. 입력 처리 시간과 윈도우 프레임 시간은 구분한다.

후속 실행은 디버깅을 끈 Profile 앱에서 진행하고, 연속 입력 측정 구간 사이의 픽셀 비교를 분리했다. 검증 전용 앱 전체를 `speed`로 미리 컴파일한 진단 실행에서도 아래 지연이 남았다. 이는 일반 사용자의 기본 설치 조건을 나타내는 수치가 아니다. 별도로 시도한 사용자 정의 Baseline Profile은 효과를 분리해 확인하지 못했으므로 최종 소스에 추가하지 않았다.

| 측정 | Profile | 전체 사전 컴파일 후 | 후보 복사 제거·인접 점 판정 재사용 후, 전체 사전 컴파일 |
| --- | ---: | ---: | ---: |
| 긴 필기 MOVE p95 | 3.21ms | 2.24ms | 1.60ms |
| 획 지우개 MOVE p95 | 70.37ms | 58.37ms | 17.52ms |
| 정밀 지우개 MOVE p95 | 110.15ms | 76.27ms | 54.19ms |

세 실행 모두 원본·순서·보호 획·실행 취소/취소·저장·GPU/소프트웨어 비교를 통과했다. 긴 필기 MOVE는 64회, 각 지우개 MOVE는 4회인 단일 실행의 관측치이며 통계적 성능 보장으로 해석하지 않는다. 기록: `build/ink-recovery-filled-profile-continuous.json`, `build/ink-recovery-filled-profile-aot.json`, `build/ink-recovery-filled-query-aot.json`.

마지막 실행의 정밀 지우개 입력 구간에서 창 그리기 명령 발행 p95는 960.88ms, GPU 완료 p95는 206.92ms, 전체 창 프레임 p95는 2,737.79ms였다. 입력 처리와 표시 완료는 다르며, 낮은 입력 처리 시간만으로 무끊김이라고 판정하지 않는다. 고밀도 장면의 반복 그리기 지연은 남아 있다. 측정 항목은 Android의 [FrameMetrics 계약](https://developer.android.com/reference/android/view/FrameMetrics)을 따른다.

최종 알고리즘의 JVM 검사 183개가 실패 없이 통과했다. 좌표·압력·메타데이터·숫자 타입·기존 원본 보존, 캐시 예산 초과 시의 정확한 지우기, 후보 순서·중복·결과 목록 소유권을 포함한다. 기록: `build/ink-recovery-query-native.log`.

현재 결과는 표시 누락의 재현 조건을 수정하고 검증한 결과이며, 사용자가 요구한 무제한 필기·무끊김 종료 조건에는 도달하지 못했다. 실제 Galaxy/S Pen의 펜촉부터 화면까지의 지연은 측정하지 않았다. 사용자 요청에 따라 물리 기기 검사는 이번 범위에서 제외했다.

## 로컬 설치 파일

2026-10-27 `python tools/build_apk.py --variant both --ocr-beta`로 두 release APK를 생성했다. 버전 `4.1.3-beta.2-local.3`, 코드 417, 패키지, 로컬 서명, ZIP 무결성, 16KiB 정렬, 고정 PDFium 자산 검증이 통과했다. 로컬 인증서는 이전 local.1/local.2와 같으며 공개 Beta 2 인증서와는 다르다. 기존 공개 앱 삭제나 데이터 초기화는 수행하지 않았다.

- [기존 로컬 패키지용 Update APK](../build/apk/bad-note-Android-4.1.3-beta.2-local.3-Update.apk) — `com.inkforge.note4`, SHA-256 `636986996c2cb6ba647627d9309b9b79e2b977ea8fd22e95520d9a93150c936d`.
- [병행 패키지용 SideBySide APK](../build/apk/bad-note-Android-4.1.3-beta.2-local.3-SideBySide.apk) — `com.inkforge.note5`, SHA-256 `5a484bae50339c62ca042c9aa33c86d57ddd8ec30d2f88d20ef5954484a4b7c4`.

기록: `build/ink-recovery-release-build.log`, `build/ink-recovery-update-verification.json`, `build/ink-recovery-sidebyside-verification.json`. Python 도구 회귀의 OCR·APK·PDFium·검증 실행기·업데이트 검사도 통과했다(`build/ink-recovery-tools-validation.log`). 커밋·푸시·원격 게시 없이 로컬에만 보관한다.
