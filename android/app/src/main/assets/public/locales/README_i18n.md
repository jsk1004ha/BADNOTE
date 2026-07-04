# Internationalization (i18n) Guide / 국제화(i18n) 가이드

## English 🇺🇸

### How the translation system works
The translation system has been refactored for better maintainability. Instead of a giant dictionary inside `app.js`, translations are now separated into individual files inside this `locales/` folder (e.g., `pt.js`, `en.js`, `ja.js`, `zh.js`).

`app.js` automatically looks for the global variables `window.I18N_EN`, `window.I18N_PT`, etc. If a string is not found in the target language file, it falls back to the original text (which is usually Korean).

### How to add new texts
When you add new UI elements or dynamic strings in `app.js` or `index.html` using Korean text:
1. You **do not** need to manually add them to each `locales/*.js` file one by one.
2. Simply open a terminal in the project root and run the automation script:
   ```bash
   python tools/update_translations.py
   ```
3. The script will automatically scan the entire project for new Korean texts, connect to a translation API, and update all `locales/*.js` files with the new translations.
4. After running the script, review the translated files to correct any context-specific translation errors.

---

## 한국어 🇰🇷

### 번역 시스템 작동 방식
유지보수를 위해 번역 시스템이 리팩토링되었습니다. `app.js` 내부에 거대한 딕셔너리를 두는 대신, 번역본은 이제 이 `locales/` 폴더 내의 개별 파일(`pt.js`, `en.js`, `ja.js`, `zh.js` 등)로 분리되었습니다.

`app.js`는 자동으로 `window.I18N_EN`, `window.I18N_PT` 등의 전역 변수를 찾습니다. 대상 언어 파일에서 문자열을 찾지 못하면 원본 텍스트(주로 한국어)가 표시됩니다.

### 새 텍스트 추가 방법
한국어 텍스트를 사용하여 `app.js` 또는 `index.html`에 새로운 UI 요소나 동적 문자열을 추가하는 경우:
1. 각 `locales/*.js` 파일에 **일일이 수동으로 추가할 필요가 없습니다.**
2. 프로젝트 루트에서 터미널을 열고 다음 자동화 스크립트를 실행하기만 하면 됩니다:
   ```bash
   python tools/update_translations.py
   ```
3. 스크립트가 프로젝트 전체에서 새로운 한국어 텍스트를 자동으로 스캔하고, 번역 API에 연결하여 모든 `locales/*.js` 파일을 새 번역본으로 업데이트합니다.
4. 스크립트 실행 후, 번역된 파일들을 검토하여 문맥에 맞지 않는 번역 오류를 수정하십시오.
