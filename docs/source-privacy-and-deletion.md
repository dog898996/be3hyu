# 원문 제외와 로컬 원문 삭제

검색 제외와 바이트 삭제는 별도 작업이다. `SourcePrivacyLedger`의 `retrieval_exclude`는 원문을 보존한 채 모델의 원문 조회와 파생 기억 사용을 막는다. `SourcePayloadDeletion`은 사용자가 명시적으로 선택한 원문에 한해 관리하는 로컬 저장소의 실제 정리를 시도한다. 삭제 API는 모델 도구 목록에 등록하지 않는다.

## 검색 제외의 식별과 지속성

사용자 대화는 `message + id + canonical record hash`로 식별한다. 같은 ID가 다른 데이터베이스 세대에서 재사용되어도 내용의 hash가 다르면 제외하지 않는다. 휴대폰 관찰은 `device_event + canonical record hash`로도 확인한다. 관찰 hash는 category, source, 실제 payload, observed_at으로 계산하므로 같은 관찰이 스풀이나 이전 백업에서 다른 SQLite ID로 다시 적재되어도 제외된다. 나중에 새로 발생한 관찰은 별도 관찰이다.

`Snapshot.hasExcludedMessageId(id)`는 hash를 기록하지 않은 옛 네이티브 출처 연결을 위한 O(1) 보조 확인이다. 검증된 제외 identity로 만든 불변 집합만 읽으며, 정책 파일 형식이나 fingerprint를 바꾸지 않는다. 정확한 hash가 있는 새 원문·새 출처 연결에는 `allowedSource(type, id, hash)`를 사용한다. 같은 ID의 다른 hash를 사용자가 새로 제공했다는 이유만으로 차단하지 않는다.

파생 기억의 모든 출처를 확인하며 한 출처라도 제외되면 그 파생 내용을 모델 근거로 사용할 수 없다. `answerSources:<assistantMessageId>`는 다른 질문에 대한 답변에도 과거 원문이 들어갈 수 있는 관계를 보존한다. 자기 알림의 원문 부모와 원래 요청의 부모도 함께 확인해야 한다. 검색 제외를 물리적 삭제나 제공자 서버 삭제로 보고하지 않는다.

정책의 실제 운영 파일은 앱 내부의 `privacy-ledger.json`과 `privacy-ledger.anchor.json`에 기록되며 프로젝트·원문·휴대폰 기록처럼 통째로 교체하지 않는다. 0.3.1의 V3 ZIP은 별도 `owner-policy/controls.json`에 이식 가능한 정책 snapshot을 포함하고, 복원 전에 현재 정책과 합친다. 원자적 저장과 fsync 후 새 세대를 게시한다. 정책이 확립된 뒤 파일이 없어지거나 잘못된 내용으로 바뀌면 빈 정책으로 처리하지 않고 조회를 차단한다. 제어 동작은 현재 정책을 다시 읽으며, 한 번의 모델 근거 준비에는 불변 snapshot을 사용한다.

## 삭제 순서와 확인 범위

`SourcePayloadDeletion(Context, HoyoungStore).deleteSource(type, id, hash, reason)`는 백그라운드 IO 작업용 API다. 호출자는 명시적 사용자 제어임을 확인하고 전송·수집·색인 작업을 정지하여 같은 저장소 세대에서 실행해야 한다. `InternalFiles.withExclusiveStorageMaintenance(callback)`은 백업 잠금 → 파일 잠금 순서로 백업·복원·일반 파일 변경과 직렬화한다. IO 작업에서 사용하고 Store monitor를 먼저 잡지 않는다. 오래 걸리는 제어 중에는 UI의 해당 저장소 폴링도 중지해야 한다. 삭제 전에 현재 SQL 내용으로 canonical hash를 다시 검증한다.

1. 복원 중인 세대와 다른 세대의 원문을 거절한다.
2. 외부 정책에 검색 제외를 기록하고, 정확한 파일 hash·크기·출처 관계만 포함하는 삭제 의도를 원자적으로 저장한다. 의도 파일은 독립된 HMAC 키로 서명한다. 별도 의도 인벤토리 anchor가 이미 확립된 의도 파일의 누락을 탐지한다.
3. 삭제 원문을 참고한 답변과 그 답변을 다시 참고한 답변을 전체 이력에서 추적한다. 별도로 쓴 이후 사용자 질문은 보존한다. 원문 검토 배치, 목표의 이전 출처 snapshot, 후속 작업과 과거 원시 영수증 보관본의 복사도 추적한다.
4. 모든 기억 revision의 역방향 출처를 확인해 관련 기억·수정 이력·메타데이터·파생 검색 색인을 함께 제거한다. 해당 답변·작업 결과·생성 이벤트·분석 영수증을 정리한다. 선택한 사용자 요청의 내용은 제거하지만, 다른 질문에 속하는 답변만 지울 때 그 다른 질문의 내용은 보존한다.
5. 다른 원문이나 첨부가 사용하는 파일은 보존한다. 일반 파일 목록의 `fileMeta`는 공유 소유권으로 간주하지 않지만, 다른 대화·초안·작업의 실제 첨부 ID는 JSON을 해석하여 확인한다. 문자열의 `/` 이스케이프 때문에 공유 참조가 사라지지 않게 한다.
6. 정확한 hash와 크기를 검증한 관리 원문 파일과 전용 첨부 보관본만 정리한다. 재생성 가능한 파생 색인·첨부 변환 캐시를 비운다. SQLite secure_delete, FTS 정리와 WAL checkpoint가 끝났는지 확인한다.

서명된 의도와 키, `source-deletions.anchor.json`은 일반 백업 복원 밖에 남는다. 완료된 의도도 `reconcileDeletionRequests()`에서 다시 검사한다. **백업을 복원한 뒤 원문이나 UI를 노출하기 전에 재검사를 호출해야 한다.** 의도 파일이나 인증 키가 잘못되었거나 누락되면 재검사를 실패시켜 복원된 원문을 검색에 다시 노출하지 않는다. 각 소스의 tombstone은 검색 제외 정책에도 남는다.

`completed_managed_local`은 추적한 관리 저장 경로의 정리가 완료되었다는 영수증이다. 제공자 서버, 이전에 외부로 내보낸 백업, OS 알림 화면, 다른 앱 문서, 이미 열린 파일 descriptor, 프로세스 메모리, 플래시의 포렌식 복원까지 삭제했다는 뜻은 아니다. 호출자는 준비된 모델 문맥과 화면 캐시도 비우고, 원문을 서버로 새로 보내지 못하게 제어해야 한다. `providerDeletionVerified`는 항상 false다.

## 삭제 대기 상태

- `pending_shared_payload`: 다른 원문·대화가 같은 정확한 파일을 참조한다.
- `pending_working_copy_retained`: 첨부를 만든 프로젝트 작업 파일에 같은 바이트가 남아 있다. 일반 프로젝트 파일을 첨부 삭제만으로 지우지 않는다. `knownWorkingCopyCount`, `retainedWorkingCopies`, `mutableWorkingCopiesDeleted:false`를 함께 반환한다. 이미 다른 내용으로 수정한 작업 파일은 그대로 보존한다.
- `pending_external_archive`: 휴대폰 스풀/HJA 보관본처럼 이 API가 물리적 정리를 확인할 수 없는 복사본이 있다. 관찰 재적재의 다른 ID도 canonical hash로 검색에서 제외한다. 삭제를 완료했다고 말하지 않는다.
- `pending_inflight_request`: 이미 전송되었거나 결과가 불확실한 작업을 로컬 행 삭제로 취소한 척하지 않는다.
- `pending_source_generation_changed`, `pending_source_integrity_changed`, `pending_descendant_generation_changed`: 현재 데이터가 의도에 기록된 세대와 다르다. 새 행과 해당 이벤트를 지우지 않는다.
- `pending_sqlite_cleanup`, `pending_failure`: 원문 정리 또는 SQLite 정리가 끝나지 않았다. 남은 의도로 다음 재검사를 이어간다.

앱 삭제·앱 데이터 초기화는 내부 운영 정책과 독립 의도 파일도 없앤다. 0.3.1에서는 새 ZIP의 이식 가능한 정책으로 백업 시점의 결정을 복원하고, 연결한 공유 폴더의 최신 정책을 먼저 가져오면 이후 삭제·제외도 유지한다. Android가 제거 시 폴더 URI 권한을 지우므로 재설치 후 같은 폴더를 다시 연결해야 한다. 최신 외부 보존 완료 전 앱을 제거했거나, 오래된 ZIP만 있고 이후 정책 기록을 잃은 경우까지 최신 정책을 복구한다고 보장하지 않는다. 외부 ZIP 파일 자체나 제공자 서버의 삭제는 이 기능의 범위 밖이다. 자세한 형식·병합·재시작·검증 범위는 [재설치 기억 보존](reinstallation-memory-preservation.md)에 기록한다.

## 컴포넌트 검증

합성 데이터만 사용한 Android SDK 28 Robolectric SQLite 테스트는 긴 원문 파일, 모든 기억 수정 이력, 260개 답변 이력의 전체 페이지와 전이적 관계, 다른 질문 보존, 검토 배치·후속 결과, 실제 형태의 첨부 업로드 영수증, 공유 파일, SQLite 실패, DB commit 직후 중단·재시작, 옛 백업 복원, 파일 세대 재사용, 의도 변조·누락과 인증 키 누락을 검증한다. 결과와 코드 hash는 `source-privacy-and-deletion-verification.json`에 기록한다. 최종 저장소 회귀 묶음은 58개 모두 통과했으며 그중 원문 삭제 23개와 독점 유지보수 4개는 명시적 native SQLite 모드다. 별도 정책·snapshot을 포함한 이전 체크포인트는 35개 모두 통과했다. Legacy ID 보조 API 추가 뒤 정책·snapshot 15개도 모두 통과했다. 이 API의 컴포넌트 테스트가 전체 앱의 사용자 제어 UI나 실제 휴대폰 HJA 삭제를 구현했다는 증거는 아니다.
