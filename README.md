# bad note 3.3.21

S Pen과 대용량 PDF를 전제로 만든 Android 필기 노트 앱입니다. 필기, PDF 주석, 손글씨 OCR, 손글씨 수식 계산, 도형 변환, 폴더 관리, 파일 즐겨찾기, 자동 업데이트를 한 앱 안에서 처리합니다.

## 바로 설치

GitHub Releases에서 기기에 맞는 APK를 받습니다.

- `bad-note-Android-3.3.21-Update.apk`: 기존 `com.inkforge.note4` 설치판을 업데이트할 때 사용
- `bad-note-Android-3.3.21-SideBySide.apk`: 기존 앱과 별도로 설치할 때 사용

배포 서명키가 다른 기존 설치판 위에는 Android가 업데이트 설치를 거부할 수 있습니다. 그 경우 기존 앱에서 `.ifnote`로 백업한 뒤 병행 설치판을 사용하십시오. 자세한 절차는 `docs/INSTALL-KO.txt`에 있습니다.

## 핵심 기능

- **필기 엔진**: 만년필, 볼펜, 젤펜, 브러시, 연필, 형광펜, 지우개, 올가미를 지원합니다.
- **S Pen 대응**: 측면 버튼을 누르는 동안 지우개로 전환하고, 손 잠금 상태에서 손가락 입력이 필기로 새는 문제를 줄입니다.
- **PDF 작업**: PDF를 새 노트로 불러오고, 검색 텍스트를 색인하며, 필기·텍스트·도형 주석을 XFDF로 내보냅니다.
- **OCR**: Android ML Kit Digital Ink 기반으로 한글·영문 필기 인식을 우선 사용하고, 화면에 머문 페이지를 유휴 상태에서 자동 색인합니다.
- **수식 계산**: 손글씨 수식을 인식해 결과 객체를 페이지에 삽입합니다. 자동 계산은 기본값이 꺼져 있습니다.
- **도형 변환**: 직선, 곡선, 원, 사각형, 삼각형, 오각형, 육각형을 필기 후 유지 동작으로 정리합니다.
- **대용량 최적화**: 큰 PDF는 현재 화면 근처의 페이지만 마운트하고, 이미지 캐시와 캔버스 픽셀 예산을 제한합니다.
- **정리 기능**: 중첩 폴더, 폴더 삭제, 파일 즐겨찾기, 마지막 페이지 복원, 페이지 번호 이동을 지원합니다.
- **다국어 UI**: 설정에서 한국어, 영어, 일본어, 중국어, 포르투갈어 표시 언어를 선택할 수 있습니다.

## 사용 흐름

1. 앱을 열고 **신규** 또는 **PDF로 새 노트**를 선택합니다.
2. 필기 도구를 고르고 페이지에 씁니다.
3. 필요한 경우 문서 옵션에서 OCR, 수식 계산, PDF 주석 내보내기를 실행합니다.
4. 자주 쓰는 문서는 별표로 즐겨찾기에 고정합니다.
5. 설정에서 언어, 스타일러스 전용 필기, 낙서 지우기, 도형 변환, 자동 OCR을 조정합니다.

## 빌드

필요 조건:

- Java 17
- Android SDK 36
- Gradle 8.14 이상
- 릴리스 서명 설정: `android/local-signing.properties.example`을 복사해 작성

```bash
python3 tools/build_apk.py --variant both
```

웹 회귀 테스트:

```bash
python3 tools/test_web.py --web web
```

## 소스 구성

- `web/`: 노트 편집기, 라이브러리, 설정, PDF/OCR UI
- `android/`: Android WebView, ML Kit, S Pen 입력, APK 업데이트 브리지
- `tools/test_web.py`: Playwright 기반 회귀 테스트
- `tools/build_apk.py`: Gradle 릴리스 빌드 도우미
- `docs/`: 설치 안내, 기능 설명, 버전별 변경 내역

## 검증 기준

릴리즈 전 다음 항목을 확인합니다.

- 웹 회귀 테스트 통과
- update / side-by-side APK 빌드 통과
- APK zip CRC, 16KB zipalign, v1/v2/v3 signing 검증
- APK 내부에 최신 `web/` 자산 포함
- GitHub Releases에 버전 태그와 APK asset 업로드

## 알려진 한계

- PDF 주석 내보내기는 XFDF 형식입니다. PDF 앱마다 XFDF 가져오기 지원 수준이 다를 수 있습니다.
- Bluetooth Air Actions 같은 제조사 전용 S Pen 원격 기능은 일반 Android MotionEvent 범위 밖일 수 있습니다.
- 복잡한 적분, 행렬, 연립방정식 전체를 푸는 서버형 HMER/CAS 파이프라인은 포함하지 않았습니다.

## 문서

- 기능 안내: `docs/README-KO.md`
- 설치 안내: `docs/INSTALL-KO.txt`
- 최신 변경 내역: `docs/CHANGELOG-3.3.21-KO.md`
