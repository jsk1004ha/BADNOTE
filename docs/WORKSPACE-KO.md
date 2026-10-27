# 로컬 작업 기준과 복구

2026-10-20 정리 전 루트는 4.0.1의 codex/fix-spen-zoom-file-export 브랜치였고, 최신 개선은 .worktrees/beta2-improvements에 따로 남아 있었습니다. 이 때문에 루트 README와 빌드를 읽으면 구버전이 기준으로 선택됐습니다.

## 현재 작업 폴더

- 루트 소스를 공개 v4.1.3-beta.2 커밋 d6873da66a2fd76029bf89141d3af81606e9480f 기반으로 전환했습니다.
- 이전 작업 폴더의 미커밋 개선과 새 소스를 포함한 249개 파일을 복사하고 SHA-256을 대조했습니다. 현재 앱 버전은 4.1.3-beta.2-local.1 / 415입니다.
- 로컬 브랜치는 codex/cleanup-beta2-workspace입니다. 펜·올가미·OCR 코드의 미커밋 변경은 이전 작업을 옮긴 것입니다. 이번 정리에서 앱 동작을 새로 구현하지 않았습니다.
- GitHub에 커밋·푸시·PR·태그·릴리스를 반영하지 않습니다.

## 자료 구분

현재 안내는 README.md와 docs/에 모았습니다. 3.x·초기 네이티브·이전 베타 설계 및 게시 워크플로는 docs/archive/로 옮겼습니다. 3.x 웹 소스는 legacy/web/에서 브러시·아이콘 회귀 참조로 유지하며 관련 도구 경로를 갱신했습니다.

사용하지 않는 Android 웹/Tesseract 자산과 폐기 스크립트는 _delete_after_review/에 모았습니다. 이 폴더와 이전 .worktrees/의 수동 삭제는 [삭제 안내](CLEANUP-KO.md)를 따릅니다. 이전 폴더의 전체 이동·삭제와 중복 파일 삭제는 자동 승인 검토에서 정책상 차단되어 제가 실행하지 않았습니다.

## 백업

백업은 프로젝트 밖의 [2026-10-20-workspace-cleanup](../../badnote-backups/2026-10-20-workspace-cleanup)에 있습니다.

- root-4.0.1/: 정리 전 루트의 source.zip, 파일 해시 manifest.json, changes.patch, status.txt.
- beta2-local/: 최신 개선의 같은 소스 백업, 원본 공개 APK와 로컬 검증 evidence/.
- recovery.json: 이전 루트 변경을 보관한 Git stash의 정확한 객체 ID.

백업 ZIP 두 개의 모든 파일을 SHA-256으로 검증했습니다. 서명·SDK 설정은 현재 로컬 파일을 유지합니다. 이전 폴더를 지우기 전에 수동 정리 스크립트가 키·설정·산출물 사본도 대조합니다.

복구할 때는 source.zip을 별도 폴더에 풀어 비교하십시오. 이전 루트의 미커밋 변경은 recovery.json의 oldRootStash를 확인해 원래 브랜치나 별도 복구 브랜치에 적용할 수 있습니다. 현재 최신 소스에 구버전 stash 전체를 덮어쓰지 마십시오.

## 이번 정리의 확인 결과

- 최신 Android 구현·테스트 115개 파일이 이전 최신 작업 사본과 바이트 단위로 일치했습니다.
- 루트에서 JVM 테스트 118개/20 suites가 실패·오류·건너뜀 없이 통과했고 Update Debug APK를 빌드했습니다.
- 생성 APK의 415 / 4.1.3-beta.2-local.1-debug, 패키지·서명·ZIP CRC·16KiB 정렬·고정 PDFium을 확인했습니다.
- Python 도구 회귀와 이전 웹의 일반·입력·파일 내보내기 검사 세 가지가 통과했습니다.
- 로컬 문서 링크 42개와 수동 삭제 미리보기를 확인했습니다. 실제 삭제·기기 계측·GitHub 반영은 하지 않았습니다.

검증 기록은 build/workspace-native-tests.log, build/workspace-apk-verification.json, build/workspace-tool-validation/, build/workspace-web-validation/에 있습니다. 일반 검색은 .ignore 설정으로 과거 자료와 사본을 제외합니다. 해당 자료를 검색하려면 rg --no-ignore를 사용하십시오.

## 삭제 중단 후 재실행 수정

2026-10-20 사용자가 삭제를 실행한 뒤 Git 등록은 해제됐지만 이전 폴더가 남았습니다. 이 환경의 긴 경로 지원 설정은 꺼져 있었으며, 274자 파일은 일반 경로로 조회되지 않고 확장 경로로만 조회됐습니다. 원래 스크립트는 이 상태의 재실행에서 상위 저장소를 읽어 소스 변경 오류로 중단했습니다.

cleanup_local.ps1을 확장 경로 처리·실제 Git 등록 조회·부분 삭제 재시도에 맞춰 수정했습니다. 이미 사라진 2개 소스는 백업을 확인하고 허용하며, 남은 247개 소스의 해시와 보존한 산출물을 대조합니다. 바뀐 소스·알 수 없는 파일·링크는 계속 중단 대상으로 유지합니다.

Windows PowerShell 5.1과 PowerShell 7에서 실제 잔여 폴더의 삭제 없는 미리보기가 통과했습니다. 별도의 build/cleanup-tests/ 임시 파일로 300자를 넘는 경로·읽기 전용·특수 문자 파일명·해시 불일치·범위 제한·열거 후 추가된 파일 보존·재시도를 검사했습니다. 사용자의 실제 삭제 대상은 이 후속 수정에서 삭제하지 않았습니다. 실행 기록은 build/cleanup/에 남습니다.
