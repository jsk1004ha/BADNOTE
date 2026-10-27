# BADNOTE 작업 지침

이 폴더는 Android 네이티브 앱의 주 작업 폴더다. 상위 폴더의 일반 스크립트 안내보다 이 문서의 프로젝트별 지침을 우선한다.

## 현재 기준

- 공개 기반: v4.1.3-beta.2, 커밋 d6873da66a2fd76029bf89141d3af81606e9480f.
- 현재 개발판: 4.1.4-beta.1, versionCode 421. 실제 버전의 최종 기준은 android/app/build.gradle이다.
- 시작할 때 README.md, docs/README.md, android/app/build.gradle과 git status를 확인한다. 미커밋 펜·올가미·OCR 개선도 현재 구현에 포함한다.
- 루트의 android/에서 작업한다. .worktrees/는 수동 삭제를 기다리는 이전 사본이며 새 작업의 기준으로 사용하지 않는다.
- docs/archive/, legacy/, _delete_after_review/는 과거 자료다. 그곳의 3.x·4.0.1 버전이나 배포 지시를 현재 요구 사항으로 적용하지 않는다.
- 구버전 Git 태그·main 브랜치·GitHub Latest만 보고 현재 소스를 구버전으로 바꾸거나 다운그레이드하지 않는다.

## 원격 반영 범위

커밋·푸시·PR·원격 태그·릴리스·게시 작업은 사용자가 명시적으로 요청한 범위에서 수행한다. 2026-10-27에 사용자가 누적 개선을 4.1.4 Beta 1 PR과 기존 형식의 베타 릴리스로 반영하도록 요청했다. PR 병합은 별도의 명시적 요청이 있어야 한다. 공개 업데이트 APK는 기존 배포 인증서와 일치할 때만 게시하며 로컬 검증용 서명으로 대체하지 않는다.

## 구조와 검증

- 앱: android/app/src/main/java/com/inkforge/notesstudio/ — Kotlin Canvas 편집기, Compose 일반 화면, Room 저장소.
- OCR 계약: android/ocr-contracts/.
- 배포 자산: android/app/src/nativeAssets/와 Gradle이 생성하는 PDFium 자산. legacy/web/는 APK에 포함하지 않는다.
- JVM 및 Debug 빌드: python tools/test_native.py.
- 현재 OCR 베타 release: python tools/build_apk.py --variant both --ocr-beta.
- 최종 APK는 루트 build/apk/에 모은다. 직접 Gradle assemble을 실행해도 자동 복사되며 계측용 APK는 build/apk/tests/에 둔다. 사용자에게 설치 파일을 안내할 때 이 경로를 사용한다.
- Python 도구 회귀: python tools/run_native_validation.py tools --output build/validation-tools.
- 기기 검사는 명시한 별도 .debug 패키지를 사용한다. 기존 사용자 앱·노트·기기를 초기화하지 않는다.
- 키스토어와 local-*.properties의 비밀 값을 출력하거나 소스에 추가하지 않는다.

현재 기능은 docs/NATIVE-KO.md, 로컬 정리·복구는 docs/WORKSPACE-KO.md, 사용자가 실행할 삭제 안내는 docs/CLEANUP-KO.md를 참조한다.
