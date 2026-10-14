# 태블릿 부가 기능 — PR #9

## 사용 흐름

- 라이브러리 문서 메뉴의 **다른 창에서 열기**, 또는 편집기 문서 옵션의 **다른 문서를 새 창에서 열기**를 사용합니다. 서로 다른 문서가 별도 Android task로 열립니다. 기기의 최근 앱·분할 화면 기능으로 배치합니다. 동일 문서는 이미 열린 편집 창으로 돌아갑니다. 문서 창의 닫기 버튼은 저장 후 해당 task를 닫습니다. 다른 문서·새 노트를 열면 별도 문서 창으로 이동합니다.
- **설정 → 자동 백업 → 백업 폴더 선택**에서 SAF 폴더를 지정하고 자동 백업을 켭니다. 기본은 꺼짐입니다. 약 12시간 주기이며 충전·유휴 조건과 Android의 실행 제한에 따라 지연됩니다. **지금 백업**은 현재 필기를 저장한 뒤 실행합니다.
- PDF 다크 모드는 설정과 문서 옵션에서 켭니다. PDF 타일에만 색상 필터를 적용합니다. 필기, 일반 이미지·노트 용지, 원본 자산, PNG/PDF 내보내기의 색상은 보존합니다. 어두운 필기색은 반전하지 않으므로 필요하면 펜 색상을 직접 선택합니다.
- `Ctrl+Z` 실행 취소, `Ctrl+Shift+Z` / `Ctrl+Y` 다시 실행, `Ctrl+S` 현재 필기 저장. 숫자 `1..9`는 펜·형광펜·지우개·올가미·손·텍스트·도형·테이프·레이저입니다. 입력창·대화상자·처리 중에는 편집기 단축키를 적용하지 않습니다.

## 창과 저장 수명

`NoteApplication`이 하나의 `NoteRepository`와 순차 실행기를 소유합니다. 창마다 Canvas, Undo, OCR 인스턴스, 문서·페이지·도구 상태를 가집니다. `WindowClaims`는 동일 문서의 두 편집기를 막고 마지막 저장이 완료될 때 소유권을 반환합니다. 라이브러리·공유 진입은 기존 MainActivity의 singleTask 동작을 유지합니다. 독립 DocumentActivity의 새 창 Intent에는 문서별 data URI와 `NEW_DOCUMENT`를 사용합니다. 강제로 같은 문서를 중복 생성하지 않습니다. 표시 언어·펜 설정은 변경한 키만 DB의 최신 환경설정에 합칩니다.

## 백업 보장과 범위

- 변경 감지는 문서 메타데이터, 정렬된 페이지 메타데이터·세대, OCR 상태·영역·교정의 SHA-256입니다. 열었던 페이지 위치는 제외합니다. 변경된 문서의 전체 v5 `.ifnote`를 한 번 기록합니다. 자산 블록 수준의 증분 동기화는 아닙니다.
- 공유 저장 실행기에서 짧은 트랜잭션으로 페이지·OCR 메타데이터를 임시 파일에 고정합니다. 트랜잭션을 닫은 뒤 불변 자산을 스트리밍하고, SAF 쓰기는 별도 작업 스레드에서 64 KiB씩 복사합니다. 복사가 끝난 후 `.pending` 이름을 `.ifnote`로 바꾸고 해시·URI·세대를 기록합니다. 이름 변경을 지원하는 SAF 공급자가 필요합니다.
- 이전 완료본을 덮어쓰거나 자동 삭제하지 않습니다. 실패·취소 시 이번 임시 결과만 정리합니다. 프로세스가 갑자기 종료되면 `.pending` 파일이 남을 수 있으며 완료 백업으로 기록되지 않습니다. 백업 폴더의 오래된 복사본은 사용자가 정리합니다.
- 휴지통과 PDF 가져오기가 진행 중인 문서는 제외합니다. 현재 저장된 문서·필기·이미지·PDF·녹음·OCR sidecar를 보존하며 앱 전역 환경설정은 포함하지 않습니다.
- 예약 작업은 앱 화면이 보이거나 저장 대기열이 남아 있으면 시작하지 않으며 백업 중 화면이 열리면 청크 경계에서 취소합니다. 폴더 변경·권한 상실·저장 실패는 상태에 표시합니다. 폴더 삭제 뒤에는 다시 선택합니다. 원격 공급자의 서버 동기화 완료까지 검증하는 기능은 아닙니다.
- WebDAV / Google Drive 전용 동기화, 충돌 병합, 서버 계정 연결, 새 릴리즈는 이번 범위에 포함하지 않습니다. Drive 등의 SAF 공급자는 선택한 폴더 인터페이스로 사용할 수 있습니다.

## 검증

`ProductivityTest`는 96 MiB JVM에서 200 MiB 파일의 64 KiB 복사·해시·중복 건너뛰기·삭제된 대상 복구와 쓰기/닫기/이름 변경/기록 실패·취소를 검사합니다. 창 소유권과 입력창·수정키 보호도 검사합니다.

`ProductivityInstrumentation`은 API 23·35에서 실제 두 task·독립 저장·문서 소유권·Activity 재생성·키보드 이벤트, PDF 타일 색상·필기 보존·출력 색상, 비공개 debug SAF provider의 백업·재가져오기·권한 상실·예약 조건을 검사합니다. debug provider는 release에 포함되지 않습니다. 실제 태블릿의 분할 화면 배치와 물리 키보드는 별도 실기기 확인 대상입니다.

```bash
cd android
bash ./gradlew -PbadnoteProductivityTests=true :app:testSideBySideDebugUnitTest :app:assembleSideBySideDebug :app:assembleSideBySideDebugAndroidTest
# APK를 build/productivity-apks/{sideBySide,androidTest/sideBySide}/debug에 배치한 뒤
cd ..
bash tools/check_productivity_device.sh
```

CI: `.github/workflows/productivity.yml` — Python/PDF 병합 회귀, 전체 JVM suite, APK 빌드, API 23·35 계측. 체크 결과는 PR에 기록합니다.

Android 문서: [문서별 task](https://developer.android.com/guide/components/activities/recents), [SAF와 영구 권한](https://developer.android.com/training/data-storage/shared/documents-files), [JobInfo 실행 조건](https://developer.android.com/reference/android/app/job/JobInfo.Builder).
