# 장기 기록의 전체 이력 검색

원문·출처·정정 이력은 authoritative data이고 FTS와 vector cache는 재구축 가능한 파생 데이터다. 후보 반환은 본문 일부만 포함하며, 사실 확인에는 원문 ID/hash와 추가 range 읽기를 사용한다.

## 복합 식별자와 여러 원문

`RARE-OLD-FACT-0-K831`을 모든 조각의 OR로 풀면 날짜의 `0*`가 백만 건 가까운 무관한 기록을 불러올 수 있다. 한 공백 단어에 포함된 복합 식별자의 조각은 같은 문서에서 함께 일치하게 한다. 공백으로 나뉜 단어는 SQL UNION으로 별도 후보 lane을 모두 유지한다. `Project-Zeta-0 Mira`는 프로젝트 장소와 Mira 관련 원문이 서로 다른 메시지에 있어도 어느 lane을 통째로 버리지 않는다. 원문 날짜 필터와 ID paging은 계속 적용한다.

Android SDK28 native SQLite에서 explicit `AND`/parentheses가 기대한 FTS 연산자로 처리되지 않는 경우를 실제 재현했다. implicit conjunction은 동작하므로 compound lane에는 그 문법을 쓰고, lane 결합은 SQL UNION으로 처리한다. Python SQLite에서만 확인하고 Android에서도 같을 것으로 가정하지 않는다.

메시지 prefix 일치 점수는 word별 FTS posting을 UNION ALL 한 뒤 docid별 합산한다. SQL LIKE substring을 prefix 일치와 같은 점수로 취급하지 않는다. 이 구분은 공개 heldout 원문 검색의 순위 회귀에서 확인했다. 검색 결과가 특정 공백 단어를 모두 포함하지 않는다는 이유로 OR lane을 제거하지 않는다. 매우 흔한 단어의 넓은 검색은 여전히 많은 posting을 다룰 수 있으며 모든 질문의 고정 시간 응답을 보장하지 않는다.

## 폰 이벤트의 파생 색인

`device_event_search`는 payload/category/source 전체를 FTS4로 색인한다. 기존 schema version 2 DB에는 onOpen에서 부가 table/trigger를 비파괴적으로 만든다. 역사 전량을 UI startup에서 동기 색인하는 대신 512개씩 background transaction으로 진행한다. 완료 cursor와 target은 파생 `device_search_state`에 보관한다. 새로운 event는 같은 DB transaction의 trigger에서 색인한다.

bootstrap 중에도 indexed FTS lane과 아직 색인하지 못한 역사 범위의 fallback 조회를 합친다. 미색인 오래된 관찰을 없는 기록으로 처리하지 않는다. 디스크/SQLite 오류로 색인이 멈추면 `deviceSearchIndexStatus()`에 미완료·fallback 상태와 오류 종류를 표시한다. `rebuildDeviceEventSearchBatch(limit)`로 재개할 수 있다. source 원문을 삭제하거나 해시를 바꾸지 않는다.

백업은 authoritative event를 streaming으로 보관하고 파생 table은 제외한다. 복원과 전체 색인 rebuild는 모든 event의 FTS를 다시 만든다. source 존재·hash·관찰 시간의 검증은 FTS와 별개다. 색인이 있다고 해서 관찰 내용이 사실이라고 판단하거나 해당 폰 권한 정책을 우회하지 않는다.

## 검증 범위

SDK28 native 회귀는 날짜 숫자 fan-out을 일으키던 복합 식별자, 여러 메시지의 OR 근거, 10,001개 event의 오래된 희귀 근거, 관찰 날짜 필터, 미완료 색인 중 역사 fallback, batch 재개, FTS가 없던 기존 v2 reopening, 원문/첨부/백업/사진 preview를 확인한다.

10만 합성 raw 메시지의 30개 gold 질문은 Recall@10=1을 유지했다. 공개 LongMemEval cleaned oracle를 변형한 heldout40 원문 검색은 lexical macro source Recall@20=.4625, host ONNX 벡터를 쓴 hybrid=.76625로 기존 결과를 유지했다. 이 수치는 표준 LongMemEval 점수, 자동 사실 추출 성능, 모델 최종 답변 품질, 실제 사용자 폰 latency를 측정한 것이 아니다. 백만 건 결과와 실제 Android device 검증은 별도 보고한다.
