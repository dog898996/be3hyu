# 파일 조회 실패와 작업 상태 검증

SDK28 Robolectric에서 실제 private 파일과 SQLite를 사용해 20개 회귀 테스트를 실행했다. `FileListingResponsivenessTest` 3개, 기존 `InternalFilesTest` 9개, `VerifiedImageSourceTest` 3개, 새 `RuntimeReadFailureTest` 5개가 모두 통과했다. 실패·조건부 skip은 없고 JUnit 실행 시간은 5.306초였다. 최신 Runtime/InternalFiles와 협력 클래스 14개를 별도 source snapshot으로 javac 재컴파일했으며 생산 코드는 수정하지 않았다.

파일 목록은 350개 중 최신 100개만 반환하고 오래된 이름도 검색한다. 내용은 읽지 않고 불완전 목록임을 표시한다. 목록 조회가 attachment monitor를 기다리지 않으며, 다른 원본의 깨진 sidecar나 symlink가 선택한 파일의 직접 identity 조회를 망가뜨리지 않는다. 이것은 실제 파일을 사용한 기능·동시성 회귀 검증이고 S24 화면의 최대 지연 측정은 아니다.

없는 파일을 `files.read`로 읽으면 실제 IOException이 발생한다. Runtime은 이를 `completed` 영수증의 `ok:false`, `code:tool_read_failed`, `completionConfirmed:false`로 저장한다. 같은 응답의 다음 읽기가 실행되고 결과 확인 continuation이 전달되며 `uncertaintyBlocked`가 켜지지 않는다. continuation 중 들어온 새 사용자 입력도 보관되고 그 턴이 끝나면 실행된다. 실패를 성공한 조회로 표시하지 않는다.

반대로 immutable-original 경로에 대한 `files.write`의 IOException은 `uncertain` 영수증·요청·목표를 남기며 소유자의 결과 확인을 요구한다. 후속 action과 자동 재실행을 막고 새 입력은 큐에 보관한다. 이 fixture에서는 보호 경로에 쓰기 전에 실패해 파일 효과가 없었고 기존 파일도 그대로였다. 다른 변경 도구의 예외는 효과 이후에 생길 수 있으므로 조회 실패와 같은 방식으로 처리하지 않는다는 보수적 계약을 검증한다.

알려진 읽기 실패 후 제공자가 결과 보고를 마치면 목표를 `reported_completed`로 보관할 수 있지만 `completionVerified`와 `overallOutcomeVerified`는 계속 false다. 이름을 모르는 과거 도구의 실패 영수증은 안전한 조회로 추측하지 않는다. 실제 저장된 unknown legacy receipt가 있으면 완료 보고가 와도 목표 보관이 차단되고 `awaiting_result`와 자동 반복 금지가 유지된다.

FakeTransport는 요청 ID만 기록했다. 실제 모델·웹·메시지 전송·휴대폰 설정 변경은 하지 않았다. source SHA와 정확한 범위는 [검증 기록](benchmarks/runtime-read-failure-regressions.json)에 있다.
