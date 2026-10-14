# 계정 기억과 자동 로그인 상태

Android 0.4.0의 계정 보관함은 서비스별 정확한 로그인 정보, 최신 정정과 이전 버전, 삭제 상태를 기존 원문·기억 저장소에 함께 보관한다. 계정 탭에서 직접 추가·수정·조회·삭제할 수 있고, 대화에서는 `accounts.list/get/save/history/delete` 도구로 같은 저장소를 사용한다. 이 개발 채팅에 보낸 계정이 설치된 앱으로 자동 복사되지는 않는다.

`accounts.get`은 선택한 `accountId`의 현재 값을 정확히 돌려준다. 제외되거나 삭제된 현재 기록을 예전 비밀번호로 대체하지 않는다. 실제 사용자 대화에서 받은 값은 원문 ID·hash와 문자열을 대조해 연결하며, 에이전트가 생성한 값은 생성한 것으로 기록한다. 원문·추가 필드와 수정 이력은 전체 기억 백업 ZIP에 포함된다.

계정 보관함 삭제는 전용 계정 복사본과 추적된 파생 기록을 처리한다. 계정을 알려준 일반 대화 전체나 다른 기억을 함께 지우지 않는다. 대화 원문도 지우려면 기억의 원문 관리에서 별도로 삭제한다. 계정 삭제 표식은 기존 외부 정책 폴더 백업에 포함되므로, 재설치 후 그 폴더를 다시 연결하고 예전 전체 백업을 복원할 때 삭제한 계정을 되살리지 않는다. 전체 기억 ZIP은 별도 백업해야 한다.

폰의 `phone_ui_type`은 Android가 실제 접근성 노드로 제공하는 일반 입력칸과 비밀번호 칸에 입력할 수 있다. 앱 자체의 비밀번호·설정 화면·자기 앱 조작 금지를 제거했으며, 권한 탭의 도구별 설정과 실제 OS 권한을 적용한다. 입력 접수와 로그인 성공은 별개다. 로그인 후 화면과 실제 서비스 응답을 확인해야 성공으로 보고한다. 추가 인증이나 Android가 노출하지 않는 입력칸은 그 결과대로 처리한다.

2단계 인증은 유형별로 처리한다. `accounts.totp.get`은 저장한 인증앱 비밀키로 RFC6238 코드를 계산하며, `accounts.totp.type`은 폰의 입력 직전에 코드를 계산해서 모델 응답을 기다리는 동안 코드가 만료되는 문제를 줄인다. 만료까지5초 미만이면 입력하지 않고 다음 유효 시각을 돌려준다. 실제 기기 시간이 틀리거나 서비스가 다른 인증 설정을 사용하면 실패할 수 있으며, 생성한 코드만으로 인증 성공을 주장하지 않는다. SHA1·SHA256·SHA512,6자리·8자리와 설정한 갱신 간격을 지원한다.

계정 편집에는 인증앱 비밀키, 복구 코드, 추가 인증 메모와 고급 설정이 있다. 알 수 없는 추가 JSON 필드는 보존하고, 명시적으로 수정한 항목만 바꾼다. 한 번 발급된 시간제 코드를 영구 비밀번호처럼 저장해서 재사용하지 않는다. 비밀키가 없으면 기존 인증앱 화면, 실제 문자·이메일 또는 알림에서 현재 인증 절차를 확인해야 한다. 문자에는 실제 Android 권한·설치 프로그램의 허용 조건이 적용된다. 이메일은 연결된 앱/웹 화면을 이용하며 이메일 서비스 전용 API 연결을 구현한 것으로 설명하지 않는다.

`accounts.recovery.reserve`는 복구 코드를 반환하기 전에 로컬 사용 예약을 영구 기록한다. 실행기의 요청·행동 ID를 사용하므로 모델이 예전 요청 ID를 임의로 지정해 사용했던 코드를 다시 고르는 방식은 허용하지 않는다. 예약은 서버가 코드를 사용 처리했다는 증거가 아니다. 통신 실패나 재시작으로 결과를 모르더라도 같은 코드를 새로운 인증 시도에 자동 재사용하지 않는다. 계정과 코드의 지문을 이용해 이전 ZIP 복원·계정 수정 뒤에도 예약을 합치며, `accounts.recovery.state`로 남은 코드 수를 확인한다. 복구 코드 원문과 인증앱 비밀키는 전체 기억 ZIP에, 예약 이력은 외부 정책 백업에도 포함된다. 앱 삭제 후에는 최신 외부 정책 폴더와 전체 ZIP을 다시 연결해야 한다. 다른 기기·서비스에서 따로 사용한 코드는 앱이 자동으로 알 수 없다.

외부 정책 폴더 쓰기가 실패하면 복구 코드를 공개하지 않고 예약을 보존한다. 폴더 접근이 회복되면 같은 실행·행동 ID와 인자로 동일 예약의 공개만 재개한다. 대화가 끝난 뒤 새 요청에서 이어가는 경우에는 `accounts.recovery.state`의 `resumeCandidates`를 `accounts.recovery.reserve`의 `resume`에 전달한다. 실행기는 이전 완료 기록·원문 ID와 hash·현재 계정 버전·권한·공개 이력을 다시 확인한다. 폴더 오류가 반복돼도 동일 미공개 예약만 이어받으며 다음 코드를 새로 소모하지 않는다. 계정 정정·코드 목록 변경·원문 제외가 있으면 예전 참조로 재개하지 않는다.

예약과 코드 공개 확정 기록은 별도로 보존한다. 공개 확정 뒤에는 옛 대기 기록이나 ZIP을 복원해 자동으로 다시 공개할 수 없다. 공개 확정 기록을 저장한 뒤 마지막 외부 폴더 확인이 실패하면 `recovery_publication_uncertain`을 반환하고, 코드가 반환되지 않았다는 사실과 자동 재개할 수 없음을 알린다. 이때 서버에서 사용했다고 주장하지 않는다. 이 처리는 코드 공개 전 저장 재개이며 실제 인증 시도를 자동 반복하는 기능이 아니다. 사용자가 명시적으로 계정 원문을 읽는 `accounts.get` 권한은 별도로 유지한다.

승인 알림은 실제 노출된 알림 동작이나 앱 화면을 확인해서 처리한다. 패스키·FIDO의 기기 키, 지문·얼굴·PIN 확인을 일반 문자열 기억으로 대체하지 않는다. Android나 서비스가 직접 사용자 인증을 요구하면 그 단계와 현재 대기 상태를 알려준다. 대기는 작업 상태와 대화에 보관하고 재개할 때 최신 화면·알림을 다시 확인한다. 인증 서비스별 자동 재개 상태기계를 구현한 것은 아니다. CAPTCHA 해결이나 실제 계정의2단계 인증 완료는 이 구현의 합성 테스트로 검증한 결과가 아니다.

내장 Work WebView의 ChatGPT 인증, 외부 브라우저·Instagram 앱의 로그인, Work 임시 클라우드의 브라우저 세션은 각각 별도다. 일반 기억 ZIP에 계정 문자열은 포함되지만, 다른 앱의 쿠키나 Work 임시 클라우드 세션을 복사하지는 않는다. 복원된 계정으로 다시 로그인을 시도할 수 있는 것과 기존 쿠키가 유지되는 것은 다르다.

검증은 합성 계정으로 최신 값·정정·삭제·오래된 ZIP 복원·Unicode·큰 원문·실제 UI 저장을 검사한다. 네이티브 비밀번호 테스트는 실제 접근성 Binder를 통해 로컬 입력칸을 조작한다. 실제 Instagram 계정 로그인, MFA·CAPTCHA와 Galaxy S24 Ultra의 재부팅 후 로그인 유지 성공은 아직 검증하지 않았다. 최종 통과 수와 실행 commit은 배포 검증 기록을 기준으로 한다.

근거:

- [정확한 계정 저장·정정·삭제](../mobile/app/src/main/java/com/hoyoung/assistant/store/AccountStore.java), [계정 실행 도구](../mobile/app/src/main/java/com/hoyoung/assistant/store/AccountTools.java)
- [UI와 계정 탭](../mobile/app/src/main/java/com/hoyoung/assistant/MainActivity.java), [계정 UI 검증](../mobile/app/src/test/java/com/hoyoung/assistant/MainActivityAccountsUiTest.java)
- [입력과 화면 조작](../mobile/app/src/main/java/com/hoyoung/assistant/phone/HoyoungAccessibilityService.java), [네이티브 비밀번호 입력 검증](../mobile/app/src/androidTest/java/com/hoyoung/assistant/NativePasswordEntryDeviceTest.java)
- [삭제·백업 회귀 검증](../mobile/app/src/test/java/com/hoyoung/assistant/store/AccountStoreTest.java), [계정 도구 검증](../mobile/app/src/test/java/com/hoyoung/assistant/store/AccountToolsTest.java)
- [시간제 인증 코드 구현](../mobile/app/src/main/java/com/hoyoung/assistant/store/Totp.java), [MFA 도구](../mobile/app/src/main/java/com/hoyoung/assistant/store/AccountMfaTools.java), [복구 코드 사용 이력](../mobile/app/src/main/java/com/hoyoung/assistant/store/RecoveryCodeLedger.java)
- [RFC6238 원문](https://www.rfc-editor.org/rfc/rfc6238), [RFC4226 원문](https://www.rfc-editor.org/rfc/rfc4226)
- [일반 기억 문맥의 계정 메타데이터 투영](../mobile/app/src/main/java/com/hoyoung/assistant/store/MemoryGateway.java), [원문 보존 검증](../mobile/app/src/test/java/com/hoyoung/assistant/store/MemoryGatewayAccountProjectionTest.java)
