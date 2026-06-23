# InkForge Notes Studio 3.3.2

Android용 필기 노트 앱입니다. 3.2.0의 라이브러리·편집 UI, 펜, 형광펜, 지우개, 올가미, 텍스트, 스티키 노트, 암기 가림 테이프, 검색, 페이지 관리, PDF·PNG·IFNOTE 내보내기 기능을 유지하면서 인식·스타일러스·대용량 문서 경로를 개선했습니다.

상세 사용법은 `docs/README-KO.md`, 설치 주의사항은 `docs/INSTALL-KO.txt`를 참고하십시오.

## 소스 구성

- `web/`: 노트 편집기와 문서 UI
- `android/`: Android 네이티브 WebView·ML Kit·스타일러스 브리지
- `tools/test_web.py`: 브라우저 회귀 시험
- `tools/build_apk.py`: Gradle 릴리스 빌드 도우미

## 빌드

Java 17, Android SDK 36, Gradle 8.14 이상이 필요합니다. 릴리스 서명 설정은 `android/local-signing.properties.example`을 복사해 작성합니다. 실제 키스토어는 소스 패키지에 포함하지 않습니다.

```bash
python3 tools/build_apk.py --variant both
```
