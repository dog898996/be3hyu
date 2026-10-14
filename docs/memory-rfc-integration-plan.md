# 세션·기억 RFC v1.2의 Android 적용 계획

상태: 최초 코드 추적, Android 적용 계약, 구현된 일부 경계의 검증 기록. RFC 전체 완료 보고서가 아니다. 최초 검토 기준은 `f2e67c6` 이후 작업 트리이며, 이후 `MemoryGateway`, 기억 검토, 활성 목표·작업 영수증의 source privacy 경계와 명시적 원문 제어를 구현했다. 계정·외부 서비스·추가 모델 연결·권한 설정은 이 작업에서 변경하지 않았다.

## 구현된 native 경계와 검증 범위

`MemoryGateway`는 하나의 실제 `ReadSnapshot.database`에서 verbatim·semantic·episodic·procedural·active 질의와 source hydration을 실행한다. 계층 receipt에는 실제 SQL/bind digest·row 수·query ID·scope와 조회 실패가 포함된다. `canonical_query_scope_at_snapshot`은 질의 범위의 완성도이며, 모델이 전체 과거를 읽었다거나 증거의 의미를 정확히 사용했다는 증명은 아니다. optional hybrid 후보는 snapshot 전에 만들고 snapshot 안에서 ID·hash·revision을 검증한다. 현재 사용자 입력, 최신 사용자 정정, 전체 native 활성 제약은 필수다. 동일 snapshot의 FTS에서 제한된 후보 ID를 읽고, canonical literal과 실제 capped term rarity를 검증된 hybrid 후보와 함께 정렬한다. 오래된 원문과 device 근거를 같은 순위에서 패킹하고 최근 원문은 뒤에 둔다. FTS는 후보 인덱스이며 canonical 원문을 대체하지 않는다. 예산에 담지 못한 optional 항목 수를 남기며 필수 제약 초과는 명시 차단한다.

준비된 문맥은 실제 제어 내용과 선택 source·memory revision·원문 범위 hash에 결합된다. dispatch admission 유효기간은 10분이며, 긴 작업의 출력은 이 단순 준비 TTL과 구분한다. 현재·과거 메타데이터의 숫자/ISO `validFrom`·`validTo` 경계, 현재 제어·입력·권한·privacy·source 변경은 출력 재검사에서도 적용된다. 감사 로그 추가만으로 문맥을 무효화하지 않는다. device 원문과 해당 파생 메모는 실제 source visibility predicate를 통과해야 한다.

`MemoryReviewCoordinator`는 source privacy 원장을 고정한 transaction 범위에서 incremental·revisit·원문·neighbor·asset 경로를 검사하고 반환 직전 fingerprint를 재검사한다. 캐시 배치는 `assertBatchAllowed`를 통과해야 한다. 제외된 연속 owner source는 native 정책 receipt(`reviewed:false`, `payloadRead:false`)로 처리한다. 그 앞에 아직 ACK하지 않은 허용 source가 있으면 먼저 그 범위를 검토한다. 처리 cursor와 모델 ACK 수를 구분해, 제외를 읽기/추출 성공으로 보고하지 않는다. 원장이 손상되면 빈 정책으로 바꾸지 않는다. 원문 physical deletion의 전체 범위는 별도 저장소 구현·검증 결과를 따른다.

| 직접 실행한 검증 | 결과와 한계 |
| --- | --- |
| SDK 35 standalone `javac`, native gate/review 소스 | 최종 캡처 소스로 통과. Gradle·APK·실제 Galaxy·제공자 연결 시험을 대신하지 않는다. |
| `MemoryGatewayTest`, SDK 28 실제 native SQLite | 26/26 통과. 다섯 실제 empty의 정상 답변, 각 계층 근거, 현재 입력 비중복, 출처 변조, revision/control/권한/privacy 변경, TTL/ISO 기한, 필수 overflow, invalidated/superseded, 과거 근거 우선 패킹, device replay·known parent copy를 확인했다. |
| Gateway read snapshot 도중 concurrent WAL writer | 실제 gate를 verbatim와 semantic 사이에서 멈춘 뒤 writer가 memory·지침을 commit했다. 준비 결과는 이전 cut만 포함했고 이후 freshness는 차단했다. API 28 증거이며 API 35 runtime의 동등 시험은 별도다. |
| `MemoryReviewCoordinatorTest` + `MemoryReviewPrivacyTest` | 기존 44개 + 새 native SQLite 14개, 58/58 통과. prefix/hole/partial/revisit/cache/neighbor/asset/손상 원장/삭제된 raw file/과거 DB restore를 확인했다. |
| review transaction 도중 source exclusion barrier | hydrated 결과를 반환하기 전 정책을 바꿨을 때 old 결과를 차단하고 진행 쓰기를 rollback했다. 이미 외부 제공자에 전달된 과거 내용의 제거는 보장 범위가 아니다. |
| 보존된 전체 소스 캡처의 재실행 | 304/304 통과: Gateway 26+indexed/scope 6, 검토 44+privacy 14, 열거 5+visibility 3, 답변 lineage 6+변조 2, 목표 24+privacy 4, 영수증 18+archive privacy 2+hot guard 5, owner 원문 제어 6, 실제 Runtime privacy 5, legacy foreign receipt 1, SourceGraph 19, 기존 runtime fixture 61, reply notification 44, source startup readiness 9. 157개는 NATIVE SQLite 명시, 147개는 기존 테스트의 기본 SQLite 설정이다. 이 캡처와 후속 consumer 수정의 소스 hash를 분리해 보존한다. |
| 실제 consumer의 후속 수정·회귀 | 최신 Runtime·Retriever·Notifications를 별도 캡처해 68/68 통과: 새로운 native consumer 5, 기존 Runtime 51, Runtime privacy 5, 목표 보고 integration 7. 제외 원장이 비어 있어도 device 파생 메모·원문 읽기·알림에 실제 scope를 적용하고, malformed/non-array/trailing continuation metadata는 wire 전에 차단한다. 위치 권한 부족의 정확한 native 진단은 전달하되 좌표·추가 private text·성공한 위치 결과·다른 phone 도구에 같은 예외를 적용하지 않는다. 10개는 NATIVE SQLite 명시, 58개는 기존 기본 설정이다. |
| 큰 canonical 원문 fixture의 첫 준비 | 20,001개 메시지에서 hybrid 없이 오래된 긴 원문의 tail을 회수했다. 동일 final run의 후보는 64개, 준비 시간은 38ms였다. 특정 fixture 결과이며 실제 Galaxy 지연 보장은 아니다. 작은 entity의 원문 14개와 다른 entity의 noisy hybrid 앞에 있는 owner pending/device confirmed 근거도 제한 예산에서 보존했다. |
| 실제 grant·최종 packet marker·provider 보류 | READ_CONTACTS 실제 grant→deny 시 응답과 메모 승격 전에 provider 출력을 보류했다. 실제 최종 text/model/attachment packet 등록 및 one-use 인가로 source-bound dispatch marker를 만들었고, 이후 제외된 source를 쓴 보류 응답의 200k 초과 사설 원문 파일과 request 결과를 명시적 owner 삭제로 정리했다. 독립된 사용자 질문은 남기며 provider 삭제를 주장하지 않는다. |
| snapshot-local graph memo | 동일 native SQLite view에서 10,001개 허용 node와 10,002개 제외/파생 node를 검증했다. 반복 root fixture의 SQL 조회는 독립 검사 128,256회에서 공유 view 2,130회로 줄었고, 같은 final run 시간은 약 2.78초와 0.081초였다. transaction 밖 영구 verdict 캐시는 없다. |


답변 ancestry는 recursive Java 호출과 128-hop 차단 대신 반복 DFS와 DAG 중복 제거를 사용한다. 무관한 tombstone 때문에 130개 정상 연결이 누락되는 문제를 실제 회귀 시험에서 발견하고 수정했다. `GoalLedger`의 progress는 native `goalSources` 및 불변 `goalSourceHistory`에 연결된다. 제외·손상·누락된 근거는 private progress 대신 source가 차단된 opaque 상태로 표시하고 다른 정상 목표는 계속 사용할 수 있다. 작업 영수증의 이전 raw 보관본은 같은 transaction에서 native `answerSources`를 연결한 뒤 공개한다. 알려진 legacy receipt linkage도 검사한다. 임시 `ReadContext`는 하나의 실제 read snapshot과 opening thread에만 존재하며 각 dispatch와 freshness에서 새로 만든다. device ancestor를 참조한 답변·목표·영수증도 현재 source visibility를 따라야 하고, implicit phone scope를 끄면 그 파생 문맥도 제외된다. 잘 알려진 native structured `original`/`sourceRefs`와 legacy raw receipt의 foreign source도 검사한다. 다만 100k 이상의 연결 그래프 성능과 모든 미추적 복사본의 발견은 이 시험의 완전성 주장에 포함하지 않는다.

명시적 owner 원문 제어는 활성 작업과 충돌하지 않는 유지보수 경계에서만 수행한다. 최신 사용자 원문이 제외·삭제되면 과거 발언을 대신 승격하지 않고 `startsNewWork:false`인 native 제어 영수증으로 owner epoch를 단조 증가시킨다. 오래된 백업 복원 후 삭제 요청을 재적용하고 epoch를 복구한 뒤 검색을 허용한다. 원문 row가 이미 지워진 중단 지점도 durable exact-hash 삭제 intent로 재시도한다. `retrieval_excluded`와 검증된 managed local deletion, provider 삭제·외부 보관본·공유 작업 파일의 미확인을 구분한다.

FTS 후보 제한과 예산 때문에 반환되지 않은 근거가 존재할 수 있다. 기간별 합계·횟수·평균은 top-k 문맥을 전체 열거로 간주하지 않으며 paged source enumeration을 사용한다. 또한 inference completion 때마다 다음 due 경계가 이미 지난 상태에서는 strict priority가 review를 지연시킬 수 있다. 장기 과부하 상황의 공정한 검토 진행은 별도 기기 검증 과제로 남긴다.

원문 추출 품질, 검색 전체 corpus 완전성, 최종 blind reader 답변 정확도와 Android 장기 실행은 위 단위 시험에서 측정한 결과가 아니다. 최종 payload의 native 등록·one-use 인가·marker lineage는 확인했지만 실제 제공자 내부 입력 bytes의 독립 검증은 별도다. 최종 standalone runner는 이전 desktop JVM C2 compiler crash를 피하기 위해 모든 suite에 동일한 C1-only 설정을 사용했다. 예전 JVM crash 로그를 테스트 실패나 제품 ART crash와 섞어 보고하지 않는다. 아래 5년 simulation 및 runtime 검증의 별도 산출물을 확인해야 한다.

## 입력과 적용 범위

사용자가 제공한 「세션과 기억처리」 RFC v1.2를 1–1,034행 전체 읽었다. 원문은 99,691바이트이며 SHA-256은 `4b778bdb7d71abc838bafcffa05bf83c2b105ad4e2837ce620cb32857ef21194`다. 원문 파일은 저장소에 복제하지 않는다. 인증정보 관련 단어는 설계 설명과 예제 변수 이름이며 실제 키 형식이나 비공개 키 본문은 발견하지 않았다. 아래 절 번호는 이 원문을 가리킨다.

최신 사용자 지시를 적용한다. 현재 제품은 단일 소유자의 Android 앱이 원문·기억·작업을 SQLite와 사설 파일에 보관하고, ChatGPT Work 임시 실행 환경에 작업을 전달하는 구조다. RFC의 Linux VM·Codex CLI·PostgreSQL·다중 사용자 배치는 현재 구현 조건이 아니다. 이를 도입하기 위해 서버나 별도 유료 API를 만들지 않는다. 모델 내부 요청마다 전처리할 수 있다는 보장도 하지 않는다.

목표는 오래된 원문을 요약으로 대체하지 않고, 5년 누적 뒤에도 필요한 과거 발언·현재 상태·정정·선호를 근거와 함께 회수하는 것이다. 정확도를 우선하며 더 긴 준비 시간은 허용하되, Android UI 스레드에서 대량 조회를 수행하거나 오류를 숨기지 않는다. 기존의 작은 시험에서 나온 100%를 제품 전체 정확도로 일반화하지 않는다.

## ADR: 기존 저장소를 유지하고 native dispatch 경계를 강화한다

| 결정 | 근거와 검증 조건 |
| --- | --- |
| SQLite와 사설 원문 파일을 유지한다 | 기존 migration, 원문 범위 읽기, typed provenance, 순차 백업을 재사용한다. PostgreSQL 예제 DDL을 Android에 그대로 복제하지 않는다. |
| 다섯 계층은 논리적 조회 경로로 구현한다 | 물리 DB나 모델 호출을 다섯 개로 만들 필요는 없다. 계층별 실제 질의·결과·범위·오류는 독립적으로 확인해야 한다. |
| native `MemoryGateway`가 읽기와 패킷 준비를 담당한다 | 게이트는 모델 호출, 새 추출 잡 생성, 외부 행동을 하지 않는다. 기존 Retriever는 후보 생성·순위화에 사용한다. |
| 최종 전달 패킷을 native에서 고정한다 | WorkWebController가 첨부 전달표를 덧붙인 뒤의 실제 text·선택 모델·첨부 표현까지 포함해 digest를 계산한다. 앞 단계의 prompt 길이만 기록해서는 부족하다. |
| 짧은 읽기와 최종 버전 검사를 분리한다 | 모델 실행 동안 DB transaction을 유지하지 않는다. source revision, 제어 epoch, 입력, 기한, 작업 fence를 전송·출력·native 행동 직전에 확인한다. |
| 로컬 임베딩과 ANN은 선택적인 후보 검색이다 | 장애나 비활성화 시 canonical lexical/SQL 경로를 이용한다. ANN shortlist와 전체 범위 조회의 보장 수준을 구분한다. |
| 시간 범위 종합에는 별도 열거 경로를 둔다 | top-k 검색으로 전체 5년/지난달을 읽었다고 표시하지 않는다. 원문 pagination과 coverage manifest가 필요하다. |
| 실제 Work 내부 추론과 cloud 도구는 별도의 보장 범위다 | 앱이 제출하는 요청은 전수 gate 대상이다. 이미 승인된 Work turn 내부 요청이나 내부 도구를 native 앱이 모두 중간 차단할 수 있다고 주장하지 않는다. |

새 `MemoryGateway`와 `DispatchGateway`는 앱 내부 이름이다. ChatGPT Work의 공식 내장 API가 존재한다는 뜻이 아니다. 현재 웹 연결의 지원·정책·DOM 관찰 한계는 [모델 기억 평가](model-memory-evaluation.md)와 기존 연결 문서에 계속 남긴다.

## 최초 검토 시 재사용 지점과 확인된 차이

아래 표는 구현 전의 gap 기록이다. 상단의 구현·검증 기록과 최종 runtime/저장소 결과를 함께 읽는다.

| 구현 | 존재하는 기능 | RFC와의 차이 |
| --- | --- | --- |
| `AssistantRuntime.receive` + `store.withTransaction` | 사용자 입력, request, source message, 입력 identity를 같은 transaction에 저장한 뒤 반환한다 | 전역 change sequence와 privacy/correction/permission epoch는 없다. 메시지 ID, device ID, review cursor를 하나의 순서로 간주하면 안 된다. |
| `HoyoungStore.addMessage/readOriginalText/openOriginalReader` | 긴 원문을 사설 파일로 보존하며 raw/record hash와 UTF-16 범위 읽기를 제공한다 | 메시지 자체의 편집 revision·삭제 원장·보관 정책은 별도 설계가 필요하다. hash를 숫자 revision으로 바꾸거나 이전 파일을 조용히 수정해서는 안 된다. |
| `upsertMemoryWithSources`, `memory_revisions`, `memory_metadata_revisions`, `memory_sources` | 파생 기억의 revision, typed source ID/hash, 메타데이터 이력을 보존한다 | 고정 네 계층의 분류·상태, invalidated/superseded, 정확한 source revision/locator의 정규화와 역방향 삭제 전파가 부족하다. |
| `MemoryRetriever.search` | FTS/확장어/원문/메모/시간/선택적 벡터·ANN을 결합한다. 한계와 epistemic 상태를 기록한다 | 다섯 계층별 query receipt가 없다. 선택 메모의 원문 hydration은 앞 3개 참조만 읽으며 잘못된 source를 경고로 남긴 메모가 패킹될 수 있다. 강한 gate에서는 필요한 evidence의 유효성을 별도로 검사해야 한다. |
| `MemoryHistory.select`, `MemoryPolicy.temporal/conflicts` | 최신 revision 개수만 자르기 전에 전체 revision timeline에서 as-of 후보를 찾고 충돌을 보존한다 | 해당 key 자체가 후보에서 발견돼야 한다. 모든 과거 기억의 검색 완전성·명시 정정의 즉시 무효화를 보장하지 않는다. |
| `StructuredMemorySearch` | 최신 canonical vector를 재검증하는 제한된 rerank가 있다 | 256개 후보/128개 기존 후보 제한이다. 전체 structured memory의 신경망 검색이 아니며, source 연결은 원문 전체 단위다. |
| `MemoryReviewCoordinator` | 정확한 원문 범위·해시·cursor CAS·재검토 세대·첨부 전달 관찰을 확인한다 | 검토 acknowledgement는 추출 정확도나 5계층 retrieval coverage가 아니다. `batchId`는 범위 digest이며 전체 전송 payload digest가 아니다. 읽기 중 일부 asset 상태를 수정하므로 그대로 read-only gateway에서 호출하면 안 된다. |
| `GoalLedger` | 별도 활성 목표 장부·대기 상태·출처·보고 보관함·revision을 관리한다 | `context`는 예산에 맞게 목표를 제외할 수 있다. 필수 취소/금지/기한과 관련 blocked 상태를 optional context 취급하면 안 된다. |
| `AssistantRuntime.buildPrompt/finishPreparation` | 현재 입력, 최신 사용자 원문, 설정·OS grant·goal/input fingerprint를 확인한다 | memory 검색은 권한/남은 문자 예산에 따라 생략 가능하다. procedural/episodic 독립 조회, source freshness fence, final payload digest가 없다. |
| `WorkWebController.send/authorizePreparedDispatch` | 첨부 준비 뒤 native 정책을 다시 확인하고 실제 submit 직전 native 승인을 요청한다 | core prompt 뒤 첨부표가 추가된다. 현재 승인 계약은 requestId/nonce/allowed이며 memory receipt·최종 bytes digest에 결합된 permit이 아니다. |
| `HoyoungStore.ReadSnapshot` + `StreamingBackup` | 별도 read-only 연결로 일관된 백업을 읽는다 | 현재 Retriever/GoalLedger/buildPrompt는 이 연결을 쓰지 않는다. helper만 감싸도 내부 `getReadableDatabase()` 호출은 기존 연결을 사용하므로 공유 snapshot이 되지 않는다. |

현재 source-backed 원문 hash와 정확한 인용 검증은 보존 가치가 있다. 인용 문자열이 일치한다는 이유로 파생 메모의 모든 의미가 입증됐다고 바꾸지 않는다. AI 생성 요약·provider image analysis·native 반환 상태와 사용자 직접 발언도 계속 분리한다.

## 최소 구현 계약

### 1. Scope, 제어 상태와 읽기 인터페이스

native가 설치/소유자 namespace, 논리 conversation, root run, 개별 dispatch, purpose, authoritative input ID/revision을 결정한다. 모델이나 WebView가 보내는 user ID/receipt/권한 주장으로 이를 변경하지 않는다. 단일 사용자 DB를 다중 사용자 격리 검증으로 보고하지 않는다.

저장소에 단조 증가하는 canonical change sequence와 별도의 privacy/correction/permission/control epoch를 추가한다. 메시지 ID와 device event ID는 타입을 유지한다. audit/progress/permit consumed 기록은 준비한 패킷 자체를 무조건 무효화하는 canonical 내용 변경으로 취급하지 않는다. 편집·삭제·정정·권한 변경·goal 변경은 같은 commit에서 관련 epoch/버전을 갱신한다. SQL 직접 갱신을 포함한 관리 대상 쓰기 경로를 조사하고 누락을 시험한다.

snapshot-bound `StoreReader`를 추가해 모든 계층 질의와 source hydration에 같은 `SQLiteDatabase` 연결을 전달한다. `getMessage/getMemory/listMessages/GoalLedger.context`가 다시 live helper 연결로 돌아가면 안 된다. 파일은 snapshot에 기록된 불변 ID·byte count·SHA를 사용하고 현재 소스와 admission 시 재검증한다. 파일 보존·복원·삭제와 DB의 세대 계약도 적용한다.

### 2. 실제 다섯 계층 조회

| 계층 | native 조회 대상과 필수 의미 |
| --- | --- |
| verbatim | 현재 입력 전체, 최근 대화, 관련 과거 메시지·device 원문·첨부 source. 현재 입력은 순위/예산에서 탈락시키지 않는다. |
| semantic | 주체·fact key·상태·유효 시간·scope를 제한한 사실/선호 후보 및 연결된 원문. 사용자 주장과 독립 확인 사실을 구분한다. |
| episodic | 사건/결정/변화/관계/실행 결과의 관련 기록과 원문. 발생 시간과 저장 시간을 분리하고 요청 시점의 revision을 찾는다. |
| procedural | 실제 저장된 사용자 스타일/작업 방식 메모와 버전이 있는 effective instructions/settings. 선호를 외부 실행 grant로 승격하지 않는다. |
| active | 목표·진행 요청·기한·취소·권한 제약·미확정 결과를 canonical 장부에서 조회한다. 필수 제약은 관련도 점수와 별도로 주입한다. |

기존 `kind=fact|preference|project|task|summary|...`를 고정 계층 이름으로 단순 문자열 치환하지 않는다. `metadata.layer`와 주체·행위·유효 시간·assertion type을 검증하고 legacy 자료는 분류 불명 상태를 보존한다. 같은 원문이 여러 계층의 근거가 될 수 있어도 독립 증거 수를 늘리지 않는다.

각 계층의 SQL 후보 생성부터 현재 보관/삭제/권한/source scope를 적용한다. device visibility는 기존 `PhoneTools.canReadStoredSource` 의미를 재사용하되 조회 후보의 SQL scope/분리 index로 옮긴다. 허용되지 않은 vector 후보를 모델 재랭커로 넘긴 뒤 지우는 방식은 사용하지 않는다. 자체 native 코드가 로컬 후보를 읽었다는 것과 모델에 노출됐다는 것도 구분한다.

### 3. ReadReceipt와 PreparedContext

신뢰된 reader가 질의를 실제 완료한 뒤만 receipt를 만든다. 필드는 layer, success/empty/error, query ID/fingerprint, scope/purpose/input, snapshot/cutoff, epoch, index generation, contiguous processed range와 holes, delta/fallback 범위, projection ready/pending, 결과 수, source/memory revision/locator, 선택·제외 사유, 조회 시각과 valid-until이다.

`empty`는 지정한 질의 범위의 정상 조회에서 관련 결과가 없었다는 뜻이다. 파생 데이터가 비어 있거나 index가 아직 tail을 처리하지 않았다는 이유만으로 complete를 발급하지 않는다. 다섯 receipt를 같은 조회 결과에 이름만 바꿔 붙이는 구현은 불합격이다. 명시적으로 해당 계층 재사용을 금지한 사용자의 설정은 SQL에 적용한 `excluded_by_owner_policy` scope를 남기되, 인가된 결과를 몰래 전달하거나 실제 검색한 것으로 위장하지 않는다.

PreparedContext는 현재 입력·필수 active 제약·선택 evidence와 정확한 source 버전·다섯 receipt·정책/런타임 capability 버전을 가진 불변 객체다. 현재 예산은 UTF-16 문자 제한이며 모델 token 측정치가 아니다. 중요한 부정·조건·단위·날짜를 자르지 않는다. 필수 부분이 예산에 못 들어가면 bounded 추가 조회/턴 또는 정적 blocked 상태를 표시한다.

source hydration은 선택된 증거를 실제 원문 범위로 읽고 hash/locator/revision과 현재 허용 상태를 확인한다. 메모에 참조가 100개 있다는 것과 100개 원문을 검증했다는 것은 다르다. 특정 claim이 필요로 하는 supporting evidence를 빠뜨린 경우 해당 claim을 주입하지 않거나 추가 hydration한다. 잘못된 메모를 `unknown` 표시만 붙여 확인된 근거로 계속 사용하지 않는다.

### 4. Snapshot과 projection lag

현재 `ReadSnapshot`은 API 35 이상에서 `beginTransactionReadOnly`, 이전 API에서 별도 read-only 연결의 SAVEPOINT를 사용한다. backup에 대한 API 28 native-SQLite 동시 writer 시험은 존재하지만 API 35 다섯 계층 조회나 모델 전송에 대한 증거는 아니다.

권고 경로는 짧은 canonical snapshot에서 다섯 질의·watermark·원문 범위·필수 active 상태를 읽고 불변 후보를 만들기다. snapshot을 닫은 뒤 로컬 ranking/packing을 수행하고, admission에서 epoch·입력·선택 dependency revision을 다시 확인한다. 해당 과정 중 canonical 변경이 있었다면 bounded 재준비한다. read-only 경로가 대상 Android에서 동작하지 않으면 먼저 그 엔진에서 시험한 epoch 전후 검증+재시도 방식으로 대체하고 ADR과 capability에 한계를 남긴다.

snapshot 안에서 live 연결 다섯 개를 병렬 호출하지 않는다. read-only transaction 안에서 `MemoryReviewCoordinator.prepareBatch/assetCoverage`처럼 쓰기가 있는 함수를 호출하지 않는다. 그 파생 유지보수는 별도 작업이며 동일한 snapshot reader에는 읽기 전용 DTO만 제공한다.

FTS message/memory 투영은 현재 same-transaction 갱신 경로가 있다. device FTS bootstrap은 `indexed_through/target_id`와 canonical tail fallback이 있다. semantic progress는 source/type/model/hash/offset별 처리 상태다. 각 투영의 의미를 유지하며 단순 `MAX(processed_seq)`로 모든 원본이 처리됐다고 바꾸지 않는다. 멀티 스트림을 단일 change sequence로 옮기면 source stream→change event 연결과 hole 검사를 제공한다.

추출되지 않은 허용 원문은 canonical delta로 보충한다. `projection_state=pending`을 모델 입력에 표시하고 structured memory가 완성됐다는 표현을 하지 않는다. 큰 tail을 임의로 잘라 complete로 만들지 않는다. 한도를 넘으면 원본 동기 보충/범위 제한 또는 coverage-incomplete 차단을 사용한다. source exclusion/deletion 때문에 처리하지 않는 구간은 삭제·정책 원장과 연결하며 조용히 hole로 건너뛰지 않는다.

`complete_to_cutoff`는 해당 scope의 canonical 상태와 누락 없는 투영/보충 경로가 준비됐다는 뜻이며 모든 과거 발언을 모델이 읽었다는 뜻이 아니다. 정상 factual lookup에서 top-k 결과를 패킹한 사실만으로 전체 원문을 매번 열거하거나 호출을 모두 차단하지 않는다. optional semantic index가 미완료여도 같은 계층의 검증된 SQL/lexical+delta 경로로 진행할 수 있다. 전체 기간 질의는 별도 coverage manifest를 충족하거나 실제 미처리 범위를 표시해야 한다.

### 5. 최종 payload와 전수 dispatch

`DispatchGateway`는 첨부 준비 후 text·instructions·모델 선택·asset representation manifest를 포함한 실제 native 요청을 한 번 직렬화하고 digest를 계산한다. JSON key 순서, UTF-8 encoding, 숫자/문자열 규칙과 null 처리를 명시한다. 모델에 전달할 text와 첨부 SHA·byte count가 모두 manifest와 일치해야 한다. 이후 텍스트나 첨부표를 변경하면 새 digest와 admission이 필요하다.

native만 생성 가능한 일회용 permit을 dispatch ID, purpose, payload digest, source versions, epochs와 run generation/fence에 결합한다. permit은 모델 프롬프트에 넣지 않는다. 현재 WebView의 nonce 요청은 이 등록된 permit과 정확한 prepared request에 결합해 검사해야 한다. 같은 request ID라도 다른 prompt, 다른 모델, 변경된 첨부, 재사용 permit, 다른 nonce 세대면 허용하지 않는다.

일반 user, autonomy, memory-review, tool-continuation, 수동 retry/재생성, 추출·요약·검증·모델 rerank에 대한 모든 앱 제출은 이 경로를 통과한다. 기존 `Transport.send`를 직접 호출하는 우회 경로와 fallback을 실제 테스트한다. healthcheck·로컬 SQL·정적 오류 안내는 모델 dispatch로 세지 않는다. 제공자 내부 요청의 bytes를 관찰하지 못하면 native→bridge 전달의 증거와 제공자 입력 확인을 구분한다.

### 6. 출력·행동·제어·삭제

응답 저장/사용자 표시/알림은 `OutputGateway`에서 request와 run generation, 최신 privacy/correction/permission epoch를 확인한 뒤 release한다. 현재 `processCompletion`은 새 입력 뒤에도 이전 visible 답을 먼저 저장할 수 있으므로 출력 fence를 추가해야 한다. 이미 전송/표시한 bytes를 되돌려 전송되지 않았다고 보고하지 않는다.

native 도구는 기존 owner-input/pause/grant/receipt 검사를 유지하고, selected address/recipient 같은 기억 dependency revision과 run fence까지 실제 실행 직전에 확인한다. Work 안에서 이미 시작한 cloud 행동의 중지 여부는 native 도구 중지와 분리한다. 외부 결과가 불명확하면 `unknown_effect`와 reconciliation을 유지한다.

stop/revoke/forget는 검색·모델에 의존하지 않는 native 제어 경로다. 삭제 완료와 실행 중지/요청 접수는 구분한다. durable DB가 장애일 때 저장 성공·최종 삭제 완료를 말하지 않는다. 유지보수 job의 자기 재귀와 AI 요약을 사용자 사실로 재추출하는 경로도 차단한다.

부분 기억 금지, retrieval 제외, 원본 삭제와 이후 저장 금지를 분리한다. 삭제 원장은 source→evidence→memory→profile/episode/checkpoint→index/cache→첨부/원문/관리 history로 전파하며, 최신 삭제 원장을 합치기 전 오래된 backup을 검색 서비스에 노출하지 않는다. 실제 원문 편집 revision·부분 삭제·역방향 lineage·backup tombstone 정책을 구현하기 전에는 RFC 삭제 계약 완료로 표시할 수 없다.

## RFC 절별 추적

| RFC | Android에서 적용할 내용·현재 상태 |
| --- | --- |
| 0, 1, 2 | 원문과 실행 상태 분리, 단일 대화·durable 저장·복원은 기존 경로 재사용. VM/서버 스택은 현재 범위에서 대체한다. |
| 0-A-1~3 | 모든 purpose의 실제 five-layer read, trusted receipts, current-input 포함과 final packet digest는 신규 작업이다. |
| 0-A-4~7 | 공유 snapshot/delta/epoch/permit/실제 adapter 입력 검증과 availability 회귀를 추가한다. 참고 TS validator 부착만으로 완료하지 않는다. |
| 3 | logical conversation과 Work request/root/tool round를 구분한다. internal thread 세대·rotation은 actual Work capability 범위만 기록한다. |
| 4, 4-1 | SQLite 비파괴 migration으로 source revision·change sequence·control epochs·lineage·permits·deletion ledger를 추가한다. 현존 source hash/history를 보존한다. |
| 5 | semantic/episodic/procedural/active와 assertion/modality/validity를 명시한다. assistant summary를 owner fact로 승격하지 않는다. |
| 6 | 기존 hybrid/원문/시간/alias 경로 재사용. layer scope, relation evidence, range-aggregate pagination과 current delta를 보강한다. |
| 7 | immutable packet, 실제 전달 digest, 시간/문자/token 예산 구분, tainted context와 output fence를 추가한다. |
| 8 | 기존 heartbeat/goal/queue/pause/receipt를 재사용. inference 기회와 실제 판단·coalesced/blocked/missed 이유를 나누고 run fence를 추가한다. 주기는 현재 사용자 설정을 우선한다. |
| 9 | 현재 Work 임시 환경과 native 승인 경계의 capability를 명시한다. CLI/app-server 옵션·API를 새로 있다고 가정하지 않는다. 유료 fallback 금지는 유지한다. |
| 10 | 기존 review source-range CAS·asset provenance를 재사용. stale-source 후보 commit, layer 분류, 정정/시간 변화/meaning 검증을 보강한다. |
| 11 | extraction/retrieval/answer/availability/cost/latency를 독립 평가한다. 5년 fixture와 frozen baseline 비교를 추가한다. |
| 12 | 원본 정책·epoch·tombstone·파생 삭제·암호화 backup·권한 scope를 실제 native 저장 경로에서 검증한다. 다중 사용자 RLS는 현재 완료 항목이 아니다. |
| 13 | 원문 조회·현재 기억·정정/삭제·중지·작업 상태와 검색 한계를 UI에 제공한다. 사설 정보가 알림에 자동 노출되지 않도록 표시 정책을 구분한다. |
| 14 | 기존 uncertainty/backup/restart 경로 재사용. partial failure, late output, 최신 deletion ledger 적용과 gate 재구성을 검증한다. |
| 15 | 최초 실제 사용 dispatch부터 gate 적용. 기존 pure/synthetic 시험을 Phase 0 live Work 또는 장기 기기 시험으로 바꾸어 보고하지 않는다. |
| 16 | 아래 G/F/S/M/T/A/D/Q 수용 matrix로 배포 차단과 품질 실패를 구분한다. |
| 17, 18 | 코드·migration·API/native protocol·검증 자료·남은 capability gap을 산출하고 허위 완성/비용/완전기억 주장을 하지 않는다. |
| 19, 20 | RFC의 공식 근거와 설계 감사는 요구의 배경이다. 현재 Android 실제 구현·DB 시험을 대신하지 않는다. |
| 21 | TS 302 합성 receipt 입력 결과는 원문 참고 자료다. Android real reads, snapshot, actual payload, 품질을 별도로 확인한다. |

## 수용 시험과 증거

아래 항목은 RFC 전체 수용 matrix다. 상단의 직접 실행한 native 시험은 일부 경계를 검증하며, 이 matrix 전체가 통과했다는 뜻은 아니다. 기존 시험 결과는 각 기존 artifact의 source/실행 범위에만 적용한다.

| RFC ID | 실제 시험 조건 |
| --- | --- |
| G01–G02 | 한 layer 누락·중복·scope 불일치·각 layer 오류·error→empty 변조는 native send 0회. 정상 무관 질의의 다섯 empty는 전송 가능해야 한다. |
| G03–G04 | user/autonomy/review/continuation/retry/모델 유지보수의 실제 진입 경로를 돌리고 ungated direct send/위조 WebView permit이 거절되는지 확인한다. 제공자 내부 훅은 별도 미확인 항목이다. |
| G05–G06 | 첨부표 덧붙임, 질문/모델/파일 변경, 다른 dispatch receipt, permit 재사용을 거절한다. fake Transport와 실제 native bridge가 받은 최종 문자열/첨부 SHA를 승인 packet과 비교한다. |
| F01–F02 | 추출/index worker를 멈추고 current correction을 저장한다. 중간 source hole과 부분 offset을 만들고 delta/fallback 또는 명시 blocked를 확인한다. |
| F03–F05 | API 28/35/현재 지원 Android의 실제 SQLite에서 각 layer 사이 writer를 멈춰 조정한다. 혼합 snapshot 차단, 기한 경과, correction/privacy/grant 변경과 stale cache를 검사한다. |
| S01–S05 | message 없는 event/attachment evidence, 단일 owner namespace 위조, 편집 중 stale extraction, client identity 재전송, 디스크/DB 장애와 durable ACK를 확인한다. |
| M01–M04 | 원문에만 한 번 등장한 사실, 인용/농담/조건/부정/수치, invalidated vs superseded, AI 자기 재추출을 독립 claim rubric으로 확인한다. |
| M05–M09 | 각 layer에만 답을 둔 cases, 인사에 무관한 민감 정보, 한국어 alias/오타, 전체 범위 enumeration, 관련/무관 counterfactual 변경을 검색과 실제 답변 단계에서 각각 평가한다. |
| T01–T05 | 단일 UI 대화에서 100회 실행 세대 변경, 오염된 context 재사용, 예산 경계, 취소 뒤 late response, context 점유와 누적 token 통계 구분을 확인한다. 실제 제공자 history 제거가 관찰 불가하면 그 부분은 미검증으로 기록한다. |
| A01–A06 | old run generation/fence의 late 응답/native 행동, 외부 outcome 불명, 승인 뒤 revoke, 무사건 inference tick, busy/reboot coalescing, quota/auth 차단과 paid fallback 부재를 확인한다. |
| D01–D04 | source 삭제 뒤 파생/캐시/원문/첨부/관리 history, 옛 backup과 최신 ledger, DB 장애 중 제어, reconnect/cache/알림의 새로운 노출을 시험한다. |
| Q01–Q02 | usable reply/blocked rate/empty 성공을 compliance와 함께 확인한다. actual Work 계정/모델/입출력 연결은 fake transport 시험과 구분해 실행 자료를 남긴다. |

기존 `LongTermStorageTest.longExportDoesNotHoldStoreMonitorAndMaintainsOneDatabaseCut`은 API 28 native SQLite backup 중 writer가 진행되고 복원본에는 새 message/setting이 없는 것을 확인한다. 이를 API 35 inference snapshot 증거로 확대하지 않는다. 기존 730일 retrieval 시험, 365회 시뮬레이션과 선택된 blind reader는 [기억 수용 기준](memory-acceptance.md), [365회 benchmark](longitudinal-memory-product-benchmark.md), [모델 평가](model-memory-evaluation.md)에 정의된 제한을 유지한다.

기존 개발 모델 probe는 한국어 합성 12사례에서 writer 메모 16개와 인용 22/22 일치, writer rubric 10 pass/2 partial, reader 11 pass/1 partial을 기록했다. 모델 추출·답변 자동 정확도와 실제 retrieval 지표는 그 artifact에서 `null`이다. 365회 선택 문맥 reader의 24/24 수동 판정·23/23 인용도 실제 5년 자동 추출 시험은 아니다. 새 평가의 baseline에는 고정한 원문·현재 코드와 실제 검색 결과를 사용한다.

## 5년 누적 최종 simulation

최종 simulation을 통과하기 전 완료로 보고하지 않는다. 단, 실제 5년이 지났거나 모든 자유 질문에 완벽히 답한다는 뜻으로 표시하지 않는다.

1. 질문/gold, 원문 fixture, writer 입력, retrieval 요청, reader 입력을 각각 분리한다. fixture seed·기간·SHA·source revision·baseline 구현 SHA와 모델/프롬프트를 고정한다. raw history와 정답표를 reader에게 따로 제공하지 않는다.
2. 정확히 5 calendar years의 사건/대화와 오래된 raw-only 희귀 값, 새 별명, 반복 정정, 과거 valid-time, 출처 여러 개가 필요한 관계, 첨부 범위, 취소/기한/권한 철회, 없는 사실을 포함한다. 규모는 독립적으로 1k/10k/100k/1m 방해 기록을 늘려 측정한다. 날짜 5년과 row 수 1m를 서로 대신하는 지표로 쓰지 않는다.
3. 기존 production baseline과 새 gateway/retrieval 경로에 동일 질문·corpus·예산을 적용한다. 소스만 찾는 retrieval 시험과 실제 writer가 만든 기억의 추출 시험, blind reader의 최종 답변 시험을 나눈다. 사람 손으로 만든 완벽한 메모를 자동 추출 성공으로 집계하지 않는다.
4. 원문 원형/해시/유실, exact quote, current/past/invalidated 구분, 금지 제약, unsupported 개인 사실, 현재 source 권한, reply 가용성과 최종 actual native payload를 검증한다. 각 layer만 정답 근거를 가진 질문도 포함한다.
5. 정정·삭제·재색인·process crash·복원·old worker·busy tick을 주입하고 아직 완료되지 않은 일을 성공으로 바꾸지 않는지 확인한다. 72시간 기기 무인 시험은 synthetic 5년 시험과 별도로 필요하다.
6. 결과에 extraction precision/recall, fractional source recall, any-hit/all-hit, evidence precision, temporal/correction accuracy, answer rubric, false recall/abstention, availability/blocked rate, p50/p95/p99·RAM·DB/file 크기와 실패 사례를 함께 남긴다. 쿼리를 거부해서 recall 평가의 분모를 줄이지 않는다.

개선 목표의 분모를 명시한다. 동일 baseline에서 `error_new <= error_baseline / 2`를 만족하면 해당 지표의 오류를 절반 이하로 줄인 것으로 보고할 수 있다. baseline 오류가 0이면 비율 개선은 N/A이며 유지/더 어려운 고정 holdout 결과를 보고한다. Recall 95%→100%를 두 배 정확도라고 부르지 않는다. 작은 selected fixture의 통과율을 실제 5년 개인화의 전체 정확도나 경쟁 제품 우위로 표시하지 않는다.

## 구현 순서

1. 부팅·복원의 데이터 세대 정합성부터 검증하고 원문/첨부와 SQLite의 정상 상태를 확보한다.
2. 비파괴 migration, single-owner scope, typed source versions와 control epochs를 추가한다. snapshot-bound reader와 실제 다섯 layer query/receipt의 DB 시험을 통과시킨다.
3. 기존 prompt preparation과 최종 attachment plan을 immutable packet으로 연결하고 DispatchGateway를 모든 앱 목적에 적용한다. 출력과 native action freshness/fence도 함께 연결한다.
4. projection delta·history/기간 enumeration·정정/삭제 lineage와 복원 ledger를 보강한다. 실패 시 canonical fallback과 정상 empty 경로를 유지한다.
5. 고정 baseline 대비 5년 simulation과 별도 실제 Work/Android 시험을 수행한다. 달성한 수치·미실행 항목·실패를 기록한 뒤만 해당 범위를 완료로 표시한다.
