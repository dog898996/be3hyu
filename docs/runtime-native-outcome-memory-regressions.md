# 도구 경험 원문과 기억 인용 검증

새 `RuntimeNativeOutcomeMemoryTest` 8개와 `RuntimeReadFailureTest` 5개를 최신 생산 소스 snapshot으로 재컴파일해 SDK28 Robolectric에서 실행했다. 실제 SQLite와 private 파일을 사용한 13개가 모두 통과했고 실패·skip은 없었다. JUnit 실행 시간은 7.739초였다. 생산 코드는 수정하지 않았다.

실제 파일 쓰기·읽기에서 생성한 `native_tool_outcome`은 1,000자 미만의 system 원문이며 정확한 record SHA로 영수증과 연결된다. 인자, 파일 경로, 내용 값, 결과 dictionary, 예외 메시지는 포함하지 않는다. Boolean 상태만 정해진 필드에 복사하고 `isOwnerInstruction`, `argsIncluded`, `privateResultValuesIncluded`, `overallTaskSuccessVerified`는 false다. 같은 action 영수증을 다시 요청하면 같은 원문 ID/hash를 반환하고 원문을 중복 생성하지 않는다.

없는 파일의 실제 IOException도 `tool_read_failed` system 원문으로 인용할 수 있다. 다음 턴의 실제 `memory.source.read`로 원문을 읽고 `memory.save`의 유일한 출처로 연결하는 경로를 확인했다. 이 출처만 인용한 모델의 `fact`·`preference` 제안은 `generated_summary`로 저장되고 `provenance:generated_assertion`을 가진다. 사용자 사실·승인·선호로 취급하지 않으며 sourceQuoteVerified와 meaningIndependentlyVerified도 false다. 시험이 작성한 실행 교훈의 의미가 독립 검증됐다는 뜻은 아니다.

실제 `PhoneTools.phone_url_open`을 shadow Android handler로 실행해 launch requested 상태를 확인했다. 원문에 `requested:true`, `completionConfirmed:false`, `browserLoaded:false`가 들어가며 URL과 handler package는 빠진다. 이후 제공자가 사이트 작업을 완료했다고 주장해도 미확인 native effect가 있는 목표를 완료 보관하지 않는다. accepted·initiated의 혼합 자료는 production serializer의 좁은 reflection seam으로 별도 검사했다. true Boolean만 복사하고 문자열 `started`, 숫자 `browserLoaded`, 임의 taskCompleted·authenticationVerified·nested private 값을 제외한다. 실제 gesture나 웹 로딩은 실행하지 않았다.

보호 원본 경로에 대한 실제 쓰기 IOException은 `uncertain` 상태와 예외 클래스 이름만 원문에 남기고 자동 재실행·continuation을 막는다. 예외 메시지나 대상 경로를 실행 교훈 원문에 옮기지 않는다. 또 실제 검색 결과 30개의 candidate excerpt가 200,000자를 넘는 경로에서는 반환 JSON 원본을 private artifact에 저장하되, 경험 원문은 작고 비공개 값 없는 typed `ok` 상태만 가진다. 이 큰 결과 시험은 accepted·initiated가 붙은 200,000자 gesture 반환을 시험한 것은 아니다.

FakeTransport는 요청 ID만 기록했다. 실제 모델·네트워크·계정·휴대폰 전송·physical gesture는 사용하지 않았다. 정확한 생산 source SHA와 시험 범위는 [검증 기록](benchmarks/runtime-native-outcome-memory-regressions.json)에 있다.
