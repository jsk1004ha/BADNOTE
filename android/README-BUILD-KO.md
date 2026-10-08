# Android 빌드

4.0부터 Kotlin 네이티브 런타임을 빌드합니다. Gradle wrapper가 포함되어 별도 Gradle 설치는 필요하지 않습니다. `web/` 편집기는 APK에 포함하지 않습니다.

## 요구 사항

- Java 17 이상
- Android SDK Platform 36
- Android Build Tools 36.1.0
- 포함된 Gradle wrapper 8.14.5

`local.properties` 또는 `ANDROID_SDK_ROOT`로 SDK 경로를 설정하십시오. `local-signing.properties.example`을 `local-signing.properties`로 복사하고 본인의 키스토어 정보를 입력해야 릴리스 APK가 서명됩니다.

```bash
cd ..
python3 tools/build_apk.py --variant both
```

결과는 `build/apk/bad-note-Android-4.0.0-Update.apk`와 `build/apk/bad-note-Android-4.0.0-SideBySide.apk`입니다. JVM 검사는 `python tools/test_native.py`, Android 검사는 같은 명령에 `--device <adb serial>`을 추가합니다. 기기 검사는 별도 `.debug` 패키지만 사용합니다. 자세한 구조와 검증 범위는 [네이티브 전환 문서](../docs/NATIVE-4.0-KO.md)를 참고하십시오.

빌드 변형:

- `update`: `com.inkforge.note4`
- `sideBySide`: `com.inkforge.note5`

제공된 소스 ZIP에는 개인 키스토어와 비밀번호가 포함되지 않습니다. 3.2.0의 임시 서명키도 보존되지 않았으므로 3.2.0 위에 직접 덮어쓰는 서명 일치 업데이트는 만들 수 없습니다.
