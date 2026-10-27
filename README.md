# BADNOTE 4.1.4 Beta 1

갤럭시 탭·S Pen용 Android 필기 앱입니다. 현재 작업 기준은 **4.1.4-beta.1 / versionCode 421**이며, 공개 v4.1.3-beta.2 이후의 텍스트·지우개·펜·올가미·페이지·OCR 개선을 모았습니다. 실제 버전 값은 [앱 빌드 설정](android/app/build.gradle)을 따릅니다. [Beta 1 변경 사항과 설치·검증 범위](docs/RELEASE-4.1.4-BETA.1-KO.md)를 확인하십시오.

**프로젝트 루트의 android/가 현재 앱 소스입니다.** docs/archive/의 구버전 기록은 현재 작업 기준으로 사용하지 마십시오.

## 최근 개선

- 텍스트 UI는 iPad Goodnotes 자료를 기준으로 연속 도구막대·그룹형 팝오버·선택/입력별 손잡이로 다시 구성했습니다. 회전 상태의 폭 조절과 모서리 확대도 반대쪽 기준점을 유지합니다. [local.6 수정 내역](docs/TEXT-UI-LOCAL.6-KO.md) · [조사 근거와 범위](docs/GOODNOTES-IPAD-TEXT-REFERENCE.md).
- 도형·텍스트·형광펜의 정밀 지우개 개선은 유지합니다. [local.5 지우개 수정과 검증](docs/TEXT-ERASER-LOCAL.5-KO.md).

- 텍스트 도구에서 페이지를 탭하면 상자와 키보드를 열어 바로 입력합니다. 한글 조합·선택 범위와 커서 서식·상자 이동/크기 조절·저장을 연결했습니다. 텍스트·형광펜·도형에 지우개를 적용하고 손 도구의 축소 아이콘을 −로 바꿨습니다. [텍스트 입력 후속 수정과 검증](docs/TEXT-LOCAL.4-KO.md).

- 페이지 관리 패널을 작은 미리보기 한 열로 줄였습니다. 상단 기본 도구 모음은 원래 위치를 유지하고, 펜 팔레트는 패널을 제외한 필기 영역에 맞춰 배치합니다. 페이지 선택 시 현재 배율을 유지하며 중앙 정렬합니다. 점 세 개 메뉴는 버튼 옆에 떠 있고, 누른 페이지만 추가 확인창 없이 삭제합니다. [local.4 변경과 검증](docs/PAGES-LOCAL.4-KO.md).

- Android가 회수한 필기 표시 캐시를 다시 그리기 전에 복구합니다. 쓰는 중인 획과 확대·이동·지우개 중 기존 필기를 함께 보호하며, 원본 획·경로·타일 재사용은 유지합니다. 고밀도 지우기의 표시 지연은 남아 있으며 무끊김 검증은 완료되지 않았습니다. [local.3 수정과 검증](docs/INK-LOCAL.3-KO.md).

- 상단 도구·펜 설정을 실제 너비에 맞춘 한 줄로 복원했습니다. 확대·지우기·도구 전환 중 기존 필기를 유지하고, 페이지 드래그 중 주변 페이지가 부드럽게 이동합니다. [이번 수정과 검증](docs/EDITOR-LOCAL.2-KO.md)을 참고하십시오.

- S Pen 버튼을 놓아도 현재 접촉은 펜을 뗄 때까지 지우개로 유지하고 다음 접촉에서 도구를 복원합니다.
- 다중 획 올가미 이동은 드래그 중 표시 위치만 바꾸고 완료할 때 원본 좌표를 한 번 갱신합니다. 취소·실행 취소·메타데이터 보존을 포함합니다.
- 한글·영문 혼합 필기의 실제 모델 후보와 단어 조합을 개선했습니다. 선택 영역 변환, 결과 교정·복사·원본 보존 삽입을 지원합니다.
- 수식은 별도 명령으로 숫자·기본 연산·괄호·분수·지수·제한된 여러 줄을 처리합니다. 인식과 계산은 별도이며 복잡한 수학 OCR·범용 LaTeX 인식은 지원 범위 밖입니다.
- 다획 필기·지우개는 변경된 명령 묶음과 공간 인덱스 항목만 갱신합니다. PDF 확대 중에는 기존 타일과 전체 페이지 미리보기를 유지하고 고해상도 타일로 교체합니다.
- 문서 검색·현재 페이지 OCR은 편집기 상단에서 바로 실행합니다. 검색 결과를 누르면 좌표가 있는 일치 영역을 강조합니다. OCR 결과는 내부 진단 없이 한 편집란에서 줄바꿈·띄어쓰기를 유지하며 복사·교정·삽입합니다.
- 큰 수식은 원본 획을 보존하고 모델 입력만 정규화·단순화합니다. 처리할 수 없는 일부 줄이 다른 줄의 결과를 버리지 않도록 했습니다.

실제 사람의 필기 정확도와 물리 Galaxy/S Pen 지연은 측정하지 않았습니다. [OCR 구현·검증](docs/OCR-NATIVE-IMPLEMENTATION-KO.md), [펜·올가미 측정](docs/PEN-4.1.3-PERFORMANCE-KO.md), [현재 설치 범위](docs/RELEASE-4.1.4-BETA.1-KO.md)에 실행 단계와 결과를 구분해 기록했습니다.

## 빌드와 검증

Java 17 이상, Android SDK 36, Build Tools 36.1.0, NDK 28.2.13676358, CMake 3.22.1이 필요합니다. Gradle wrapper와 고정 PDFium 준비 도구를 사용합니다.

    python tools/test_native.py
    python tools/build_apk.py --variant both --ocr-beta

SDK·서명 설정과 개별 Gradle 명령은 [Android 빌드 안내](android/README-BUILD-KO.md)에 있습니다. Debug·release APK는 직접 Gradle을 실행해도 루트 build/apk/에 자동으로 모입니다. 계측용 테스트 APK는 build/apk/tests/에 생성됩니다.

| 산출물 접미사 | 패키지 | 용도 |
| --- | --- | --- |
| -Debug.apk | com.inkforge.note4.debug | 공개 앱과 분리한 검증 |
| -Update.apk | com.inkforge.note4 | 기존 패키지 업데이트 |
| -SideBySide.apk | com.inkforge.note5 | 병행 설치 또는 기존 병행 앱 업데이트 |

공개 업데이트 APK는 기존 배포 인증서와 일치해야 합니다. 로컬 검증용 서명은 공개 배포 키를 대체하지 않습니다. 공개 설치판을 유지하며 검증하려면 분리된 Debug 패키지를 사용하십시오. GitHub 베타는 prerelease로 게시하며 정식 Latest와 자동 업데이트 정책은 유지합니다.

기존 노트는 ZIP v5 .ifnote로 내보내고 가져올 수 있으며 3.x JSON 가져오기와 명시적인 호환 JSON 내보내기도 지원합니다. 같은 패키지·서명 업데이트의 기존 데이터 이전과 다른 패키지 사이의 .ifnote 이전을 구분합니다.

## 폴더

| 위치 | 역할 |
| --- | --- |
| android/app/ | Kotlin 편집기·Compose 화면·Room 저장소·네이티브 PDF |
| android/ocr-contracts/ | OCR 입력·결과 계약 |
| tools/ | 현재 빌드·APK·회귀·평가 도구 |
| docs/ | 현재 버전의 안내·구현·검증 |
| docs/archive/ | 과거 버전의 변경 기록·설계·배포 자료 |
| legacy/web/ | 브러시·아이콘 비교에 사용하는 3.x 원본 |
| build/ | 로컬 APK·의존성·검증 산출물 |

현재 기능과 구조는 [네이티브 안내](docs/NATIVE-KO.md), 전체 문서 목록은 [문서 안내](docs/README.md)에 있습니다.

## 기여자

- 프로젝트 관리 및 개발: [jsk1004ha](https://github.com/jsk1004ha)
- 초기 네이티브 개발: [greencow](https://github.com/grncow)
- 포르투갈어 번역 및 UI 개선: [dyduq12](https://github.com/dyduq12)
- 아이콘: [Photon616](https://github.com/Photon616)
