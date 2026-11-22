# 3.3.6 변경 내역

- S Pen 버튼을 화면에 닿기 전에 누른 상태도 지우개 입력으로 유지하도록 수정했습니다.
- Android `MotionEvent` 버튼 상태를 native latch로 보존해, 이후 touch down/move가 buttonless로 들어와도 지우개 세션으로 시작되게 했습니다.
- WebView/JS 레이어에도 S Pen barrel-button latch를 추가해 이벤트 순서 차이와 지연에 더 강하게 처리했습니다.
- 공중 버튼 press 후 실제 stroke가 삭제되는 회귀 테스트를 추가했습니다.
