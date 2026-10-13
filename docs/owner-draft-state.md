# 전송 중 화면 재생성과 임시 입력 보존

`OwnerDraftStore`는 글·첨부 선택·입력 식별자·revision을 하나의 입력 묶음으로 저장한다. Activity 안의 mutable `JSONArray`가 저장된 묶음을 바꾸지 않도록 `Snapshot.attachmentsJson`은 불변 문자열이며 `attachments()`는 호출마다 복사본을 만든다.

첨부 선택은 현재 Main이 쓰는 상대 파일 ID 문자열과 기존 metadata JSONObject를 모두 허용하고 배열의 순서를 유지한다. 문자열은 private project 경로의 길이·부분·깊이 규칙을 확인한다. 실제 파일 존재 여부, 고정 원본 생성, SHA·크기 검증은 Runtime의 `freezeAttachments`가 전송 전에 수행한다.

## Main과 Runtime이 사용하는 순서

1. Activity를 만들거나 다시 만들 때 `read()`로 저장된 입력 묶음을 가져온다. 정상적인 기존 `ui.draft`와 `ui.attachments`는 한 번만 이전하며, 화면을 돌리거나 프로세스를 다시 열어도 같은 `clientInputId`를 유지한다.
2. 사용자가 실제로 글 또는 첨부 선택을 바꿀 때만 `edit(text, attachments)`를 호출한다. 동일한 정상 입력을 다시 넘겨도 식별자는 바뀌지 않는다. `A → B → A`처럼 내용을 되돌린 실제 편집은 새로운 식별자와 더 큰 revision을 갖는다. `onPause`에서 과거 Activity의 입력을 다시 저장하면 안 된다.
3. 보내기를 누르면 그 순간의 불변 Snapshot을 Runtime에 전달한다. Runtime은 원문·고정된 첨부·요청 fingerprint를 검증하고 요청 및 사용자 원문을 기록한 **동일한 `HoyoungStore.withTransaction` 안에서** `consume(clientInputId, revision)`을 호출한다.
4. consume은 현재 식별자 **그리고** revision이 일치할 때만 글과 첨부를 함께 비운다. 빈 묶음도 새로운 식별자와 revision을 받는다. 준비 중 새 글을 쓰거나 첨부를 바꿨다면 이전 전송의 consume은 현재 묶음을 그대로 반환한다. callback이 Activity 종료 때문에 실행되지 않아도 성공한 enqueue와 입력 비우기는 이미 함께 저장돼 있다.

Main은 callback에서 글과 첨부를 각각 문자열 비교로 지우면 안 된다. 저장된 최신 Snapshot을 가져오되, 현재 화면의 편집 묶음이 캡처한 전송 묶음과 여전히 같은 경우에만 화면에 적용해야 한다. 중복 enqueue 자체의 fingerprint 검증과 한 번만 사용자 원문을 생성하는 책임은 Runtime에 있다. 이 helper는 전송이나 외부 효과를 실행하지 않는다.

## 실패와 손상 처리

각 read/edit/consume은 Store의 transaction을 사용하므로 바깥 enqueue transaction이 실패하면 중첩 consume과 legacy mirror도 같이 되돌아간다. 설정 쓰기는 실패를 덮는 `insert` 반환값에 의존하지 않고 SQL 오류를 호출자에게 전달한다.

원문은 Java UTF-16 code unit을 직접 인코딩한 Base64와 profile을 포함한 SHA-256으로 보존한다. 잘못된 surrogate를 UTF-8 replacement 문자로 바꾸지 않는다. `ui.draftState`에 하나의 encoded tuple을 저장하고, 큰 기존 입력은 한 설정 값의 크기 한도 안에서 여러 부분으로 나눠 같은 transaction에 기록한다. 불필요해진 이전 부분은 transaction 안에서 제거한다. 정상 편집 이력 전체를 매번 쌓지는 않는다.

legacy 첨부, tuple, hash 또는 큰 tuple의 일부가 손상됐으면 `read()`는 손상 원본을 먼저 `app.owner_draft.recovery`의 `system` source들로 나눠 저장한다. 각 source에는 setting 이름, 정확한 UTF-16 원문, SHA-256, 순서·전체 부분 수가 있다. `recoveryKey`의 manifest가 해당 source id를 연결한다. 이 기록은 local app이 복구를 관찰했다는 자료이며 사용자 승인 사실을 주장하지 않는다. 복구 자료 기록이 실패하면 원래 상태를 덮지 않는다.

복구 Snapshot은 가능한 legacy 글과 정상 첨부를 표시하거나 잘못된 첨부를 `[]`로 표시하지만 `recoveryRequired=true`와 설명을 함께 반환한다. Main은 이를 사용자에게 알리고 전송을 막아야 한다. consume은 복구 묶음을 비우지 않는다. 실제 사용자가 글이나 첨부를 수정하면 edit가 새 정상 묶음을 만들며, 별도로 보존한 손상 원본은 삭제하지 않는다. 복구가 표시됐다고 손상된 첨부를 완전히 복원했다는 뜻은 아니다.

## 검증 범위

`OwnerDraftStoreTest`는 Robolectric Android 28의 실제 SQLite로 기존 입력 이전·database reopen·경쟁 read/consume·double callback·`A → B → A`·전송 준비 중 글/첨부 편집·중첩 transaction rollback·두 번째 mirror write 실패·손상 원본의 exact hash·잘린 큰 tuple·복구 source 기록 실패를 검증한다. 새 helper의 CAS와 저장 계약 검증이며 실제 Activity rotation, WebView wire 전송 또는 서버의 중복 효과까지 검증한 결과는 아니다. Main과 Runtime의 통합 테스트가 그 별도 경로를 확인해야 한다.

`RuntimeOwnerInputIdentityTest`는 새 네 인자 receive와 helper를 실제 SQLite·고정 원본 파일·전송을 기록만 하는 FakeTransport로 함께 검증한다. 6개 경쟁 receive가 사용자 원문·요청·전송 각 1개가 되고, completed/uncertain 입력의 재수신은 상태·결과·원문을 초기화하지 않는다. 같은 식별자의 다른 원문, 첨부 bytes·순서·수량, 가짜 SHA·크기, 사진용 생성 안내문과 실제 빈 원문의 차이를 확인한다. 준비 중 `A → B → A`, enqueue+consume 뒤 database reopen, consume 실패에 따른 요청·원문·fingerprint의 공동 rollback을 검증한다. 현재 식별자를 다른 revision(legacy 경로의 -1 포함)이나 다른 raw tuple로 보내 현재 입력을 망가뜨리는 경우도 거절하며, 다른 과거 식별자의 전송은 현재 입력을 지우지 않는다. 이는 앱 내부 계약 검증이며 FakeTransport의 전송 횟수가 실제 서버 효과 횟수를 증명하지 않는다.
