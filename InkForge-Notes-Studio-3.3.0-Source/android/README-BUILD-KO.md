# Android 빌드

## 요구 사항

- Java 17
- Android SDK Platform 36
- Android Build Tools 36.1.0
- Gradle 8.14 이상

`local.properties` 또는 `ANDROID_SDK_ROOT`로 SDK 경로를 설정하십시오. `local-signing.properties.example`을 `local-signing.properties`로 복사하고 본인의 키스토어 정보를 입력해야 릴리스 APK가 서명됩니다.

```bash
cd ..
python3 tools/build_apk.py --variant both
```

빌드 변형:

- `update`: `com.inkforge.note4`
- `sideBySide`: `com.inkforge.note5`

제공된 소스 ZIP에는 개인 키스토어와 비밀번호가 포함되지 않습니다. 3.2.0의 임시 서명키도 보존되지 않았으므로 3.2.0 위에 직접 덮어쓰는 서명 일치 업데이트는 만들 수 없습니다.
