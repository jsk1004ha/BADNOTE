# iPad Goodnotes 텍스트 UI 조사 — 2026-10-27

이번 로컬 변경은 iPad용 Goodnotes의 텍스트 객체 메뉴를 기준으로 한다. Windows/Web 화면은 기준으로 쓰지 않았다. iPad 실기기를 직접 조작한 결과가 아니며, 공개된 공식 화면·원본 녹화·스크린샷을 관찰했다. **최신 버전의 모든 하위 화면을 직접 확인했다는 뜻은 아니다.**

| 항목 | 관찰된 구조 | 근거와 범위 |
|---|---|---|
| 객체 도구막대 | 밝은 회색 연속 막대, 색상 원, 흰 크기 캡슐, Aa, BI, 정렬, 목록, 상자 스타일, 편집 동작 | [공식 Text Tool 안내](https://support.goodnotes.com/hc/en-us/articles/7353727615375-Type-text-with-the-Text-Tool), [2026-01 iPad 원본 화면 3594–3601](https://feedback.goodnotes.com/forums/191274-customer-suggestions-for-goodnotes-apple/suggestions/50906399-improve-textbox-toolbar-remove-floating-toolbar) |
| 선택/입력 | 첫 탭 선택, 다시 탭 입력. 선택한 상자를 드래그해 이동. 입력 중에는 네이티브 캐럿과 범위 선택 | 같은 공식 안내 및 원본 화면 |
| 변형 | 흰 네모 모서리는 비율 확대, 파란 원은 폭 변경, 밖으로 나온 흰 원은 회전 | 공식 안내. 일부 2025 화면은 폭 핸들만 보이므로 입력/선택 상태를 구분 |
| 글꼴 | 기본 Modern/Classic/Technical 목록 → Custom Fonts → 검색·글꼴군 → 굵기 | 원본 화면 3597–3601. Android에서는 실제 제공되는 글꼴군으로 구성 |
| 더보기 | 구분선이 있는 행 목록, 우측 아이콘, 하단 잘라내기/앞/뒤 아이콘 | 공식 첨부 14556774626063. Goodnotes AI는 BADNOTE에 제공되지 않으므로 넣지 않음 |
| 기본값 | 도구막대 T-heart, 객체 더보기 Save as Default | [Goodnotes 관리자 2025-12-05 설명](https://feedback.goodnotes.com/forums/191274-customer-suggestions-for-goodnotes-apple/suggestions/50535219-changing-default-font-textbox) |
| 크기/간격 | +/- 및 슬라이더, 자동 줄 간격 스위치, pt 값 | [공식 줄 간격 안내](https://support.goodnotes.com/hc/en-us/articles/360004148975-The-text-box-doesn-t-grow-to-accommodate-a-larger-text-size). 최신 크기 팝오버 전체는 공개 근거 부족. 기존 iPad 조절 방식 참고 |
| 상자 스타일 | 프리셋/고급 탭, 배경색/테두리색, 모서리 반경, 그림자, 테두리 폭, 여백 | [Goodnotes 공식 편집 녹화](https://www.goodnotes.com/blog/editable-stickers-goodnotes-marketplace), [도쿄대 원본 튜토리얼](https://utelecon.adm.u-tokyo.ac.jp/en/articles/goodnotes-writing/). 이 하위 화면은 이전 UI 근거 |
| 색상 | 프리셋/사용자 지정, 색상 선택, HEX | 공식 편집 녹화(상자 색상) 및 [피드백 원본 화면](https://feedback.goodnotes.com/forums/191274-customer-suggestions-for-goodnotes-apple/suggestions/46166683-color-presets-for-text-styling). 피드백에 그려진 제안 UI는 현행 기능의 증거로 취급하지 않음 |
| 서식/목록 | BI, 밑줄, 취소선; 글머리·번호·들여쓰기 | 공식 Text Tool 안내. 최신 하위 팝오버 전체 배치는 미확인 |

공개 자료는 build/goodnotes-ipad-reference/에 로컬 참고용으로만 보관한다. Goodnotes 스크린샷과 Apple 전용 글꼴은 APK 자산으로 복사하지 않는다. 기존 정밀 지우개 개선은 유지한다.

## 구현 범위 제한

사용자 요청에 따라 기존 BADNOTE에 없는 기능은 추가하지 않는다. AI, 링크, 사용자 폰트 설치, 그림자·모서리 반경·별도 테두리 색상 등 미지원 항목은 메뉴에 넣지 않는다. 기존 3개 글꼴, 색상, 크기, 서식, 정렬·줄 간격, 목록, 배경·테두리·여백, 객체 편집 기능의 UI와 제스처만 다듬는다.
