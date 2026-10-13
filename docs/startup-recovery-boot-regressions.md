# 복원 중단 후 앱 시작과 원문 보존 검증

복원 저널이 남은 상태에서 Store를 먼저 생성하면 없는 `originals` 폴더가 빈 폴더로 만들어질 수 있었다. 이어진 committed 복구가 이를 정상 namespace로 보고 이전 원문 세대를 삭제하는 실제 손실을 재현했다. 잘린 저널은 Runtime 생성 전에 앱 시작을 종료시켜 기존 차단 화면에도 도달하지 못했다.

수정된 경로는 Files 복구부터 검사하고, 실패하면 Store·Runtime·phone journal·collector·semantic index·브라우저를 만들기 전에 차단된 앱 상태를 남긴다. 잘린 저널, committed originals 누락, 실수로 Store를 먼저 호출한 경우, 세 namespace 중 뒤쪽만 누락된 경우, 반복 실패 재시도 모두에서 기존 원문·이전 세대·staging·저널을 보존하는지 검사했다. 특히 앞쪽 namespace가 정상이어도 전체 move 사전 검사 전에 이전 세대를 삭제하지 않는다.

정상 canonical 데이터와 저널 부재가 확인되는 orphan cleanup 실패는 `ready_cleanup_deferred` 경고로 남고 데이터 접근을 막지 않는다. 이 경고의 시험은 실제 파일 중 orphan의 `listFiles` 실패만 좁게 주입했다. 누락된 원본을 정상 복구한 것으로 간주하는 경로가 아니다.

잘못된 저널을 올바른 uncommitted 저널로 명시적으로 교정한 뒤 같은 App에서 worker 재시도를 수행했다. 이전 작업 파일로 rollback하고 원문 record SHA와 raw bytes를 유지하며 core를 한 번 초기화했다. 다음 재시도는 같은 Store/Runtime을 재사용한다. core 준비는 제공자 로그인이나 실제 작업 완료를 뜻하지 않으며 provider `ready`는 여전히 false였다.

실제 HoyoungApplication이 자기 자신을 application context로 반환하도록 framework에 붙인 fixture로 BootReceiver·notification·autonomy·accessibility 콜백도 검사했다. 차단 상태에서 phone journal fallback과 Store 생성이 거절되고 콜백의 null Runtime/collector 처리로 원문이 바뀌지 않았다. 초기화 상태를 test setter로 위조하지 않았다.

최신 Root/Storage 소스 25개를 별도 javac로 컴파일해 SDK28 Robolectric·native SQLite에서 `StartupRecoveryBootTest` **8/8**, 실패·skip 0으로 통과했다. 실행 시간은 5.102초였다. 앱과 service를 수동 framework attachment로 연결한 검증이며 실기기 재부팅·OEM 정책·실제 MainActivity 화면·브라우저 로그인은 시험하지 않았다. MainActivity는 이 snapshot에서 컴파일만 했다. 테스트 담당자는 생산 코드를 수정하거나 Gradle을 실행하지 않았다. source SHA와 구체적 범위는 [검증 기록](benchmarks/startup-recovery-boot-regressions.json)에 있다.
