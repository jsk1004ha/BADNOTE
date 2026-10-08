# bad note 4.0.1 — Kotlin native

갤럭시 탭·S Pen용 Android 필기 앱입니다. 라이브러리, 필기 편집기, 저장, PDF, 공유, OCR, 녹음과 설정을 Kotlin으로 전면 교체했습니다. 앱의 편집 화면에서 JavaScript나 WebView를 사용하지 않습니다. WebView는 기존 설치판의 IndexedDB를 읽는 일회성 이전에만 사용합니다.

## 설치와 데이터 이전

빌드 결과는 `build/apk/`에 생성됩니다.

- `bad-note-Android-4.0.1-Update.apk`: `com.inkforge.note4` 업데이트
- `bad-note-Android-4.0.1-SideBySide.apk`: `com.inkforge.note5` 업데이트 또는 병행 설치

기존 앱과 동일한 패키지·서명으로 업데이트하면 첫 실행에 노트, PDF 배경, 이미지, 녹음, 폴더와 설정을 이전합니다. 이전은 문서별 트랜잭션으로 처리하고, 완료한 문서는 재시도 시 건너뜁니다. 기존 IndexedDB는 삭제하지 않습니다. 다른 패키지의 데이터는 Android 격리 때문에 자동으로 읽을 수 없으므로 `.ifnote` 파일을 가져와야 합니다.

**4.0의 `.ifnote`는 ZIP 기반 버전 5입니다.** 원본 자산과 편집 가능한 페이지를 담습니다. 3.x의 JSON `.ifnote`도 가져올 수 있습니다. 4.0에서 내보낸 파일은 3.x에서 열 수 없으며, 4.0 데이터가 3.x 저장소로 역동기화되지는 않습니다. 업데이트 전 3.x 백업을 보관하십시오.

## 기능

- Kotlin Canvas 필기: 필압·기울기·방향·historical samples, S Pen 버튼 지우개, 스타일러스 전용 입력, 손가락 팬·핀치 확대
- 만년필·볼펜·젤펜·브러시·연필·파인라이너, 형광펜, 필압·색상·굵기·획 안정화·불투명도 설정
- 획·정밀 지우개, 낙서 지우기, 올가미 이동·확대·축소·색 변경·복사·붙여넣기·잠금, 실행 취소·다시 실행
- 텍스트·스티키 노트·이미지·도형·스티커·암기 가림 테이프·자·레이저 포인터
- 연속/단일 페이지, 페이지 추가·복제·이동·삭제, 북마크·목차·문서 탭·최근 페이지 복원
- 중첩 폴더, 즐겨찾기, 태그, 검색, 휴지통과 복원
- 원본 PDF 보관 및 화면에 보이는 영역의 타일 렌더링, PDF 삽입, `.ifnote`/PDF/PNG/XFDF 내보내기
- ML Kit 한글·영문 및 선택 언어 필기 OCR, 유휴 OCR 검색 색인, 이미지 OCR, 손글씨 수식과 기기 내 계산
- 마이크 녹음·재생, 한국어·영어·일본어·중국어·포르투갈어 사전, 앱 업데이트 확인

## 대용량 내보내기와 공유

노트는 SQLite WAL에 문서 정보·페이지·객체를 분리해 저장하며, 대형 자산은 별도 파일입니다. `.ifnote` 내보내기는 페이지 단위 JSON과 자산을 `ZipOutputStream`에 64 KiB씩 기록합니다. 문서 전체 복제나 자산 base64 변환, 전체 결과 Blob 생성이 없습니다. PDF도 한 페이지씩 JPEG 임시 파일로 만든 뒤 최종 출력에 복사합니다.

공유 파일을 디스크에 완성한 다음 Android `ACTION_SEND`와 FileProvider 읽기 권한으로 시스템 공유 창을 엽니다. Web Share API의 transient user activation에 의존하지 않습니다. 공유 파일은 수신 앱이 비동기로 읽을 수 있도록 유지하고, 7일이 지난 파일은 다음 내보내기 때 정리합니다.

## 빌드와 검증

Java 17 이상, Android SDK 36 / Build Tools 36.1.0이 필요합니다. Gradle 8.14.5 wrapper, AGP 8.13.2, Kotlin 2.3.0을 사용합니다.

```powershell
python tools/test_native.py
python tools/build_apk.py --variant both
```

릴리스 빌드는 `android/local.properties`의 SDK 경로와 `android/local-signing.properties`의 서명 설정이 필요합니다. 예제 서명 파일을 참고하십시오. 실제 비밀 값은 저장소에 포함하지 않습니다.

에뮬레이터/테스트 기기를 명시해 네이티브 회귀 테스트를 실행할 수 있습니다. 테스트는 별도 `.debug` 패키지만 사용합니다.

```powershell
python tools/test_native.py --device emulator-5580 --adb "C:/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe"
python tools/verify_apk.py build/apk/bad-note-Android-4.0.1-Update.apk --build-tools "C:/Users/User/AppData/Local/Android/Sdk/build-tools/36.1.0"
```

테스트에는 96 MiB JVM에서의 100/300 MiB 자산 왕복·SHA-256 검증, 100 MiB legacy base64 가져오기, 실제 Android 저장소의 300 MiB 내보내기·가져오기, S Pen MotionEvent, 데이터 이전, 공유 URI가 포함됩니다. 물리 Galaxy/S Pen의 지연·압력 특성·펌웨어 동작은 별도 실기기 확인이 필요합니다.

## 소스

- `android/app/src/main/java/com/inkforge/notesstudio/*.kt`: 네이티브 앱 전체
- `android/app/src/nativeAssets/migration.html`: 이전용 로컬 IndexedDB 리더
- `android/app/src/nativeAssets/locales/`: 네이티브 UI 번역 사전
- `android/app/src/test/`: 기하·계산·스트리밍 파일 테스트
- `android/app/src/androidTest/`: Android 저장소·입력·이전 회귀 테스트
- `web/` 및 기존 `assets/public/`: 3.x 참고 소스와 호환 회귀 자료. **4.0 APK에는 포함하지 않습니다.**

현재 범위와 호환 제약은 `docs/NATIVE-4.0-KO.md`에 정리했습니다.

## 4.0.1 수정

3.3.30 화면의 원본 벡터 아이콘, 어두운 라이브러리, 파란 편집 도구막대, 검은 도구 설정 막대, 페이지 사이드바와 각 설정 창을 네이티브 View로 재구성했습니다. 색상 조합기의 채도·명도 색상판과 색조 막대, 기존 용지 크기·격자 간격도 복원했습니다.

낙서 지우기는 개별 입력점 사이의 이동량 대신 획의 주축과 왕복 구간으로 판정합니다. 촘촘한 S Pen historical samples, 수평·대각선·세로 왕복, 확대율 변화에서도 동일하게 처리하며 3회 이상 겹친 필기만 지웁니다. 잠긴 획, 숨긴 객체, 텍스트, 형광펜은 보존합니다.

```powershell
python tools/test_native.py --ui-only --device emulator-5580 --adb "C:/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe"
```

## 기여자

- 프로젝트 관리 및 개발: [jsk1004ha](https://github.com/jsk1004ha)
- 포르투갈어 번역 및 UI 개선: [dyduq12](https://github.com/dyduq12)
- 아이콘: [Photon616](https://github.com/Photon616)
