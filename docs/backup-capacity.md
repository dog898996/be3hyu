# 사진·장기 원문 백업의 용량과 실패 처리

## 현재 한도

백업 총 한도는 기본 64 GiB이며 `backupMaxGiB` 정수 1~64로 바꾼다. ZIP에 실제 쓴 압축 bytes와 펼친 entry payload 합계에 각각 적용한다. 구버전의 4 GiB 한도는 사진 1,000개가 각 8 MiB인 약 7.81 GiB의 집합도 담을 수 없어 변경했다. 한도를 늘렸다고 폰이나 외부 저장 위치의 공간이 늘어나는 것은 아니다. 더 큰 원본을 자동 삭제·요약·재압축해 맞추지 않는다.

| 데이터 | 개별 한도 | 보관 방식 |
|---|---:|---|
| 작업·다운로드 binary | 512 MiB | 작은 buffer로 임시 파일에 쓰고 성공 후 atomic rename |
| 사용자 첨부 원본 | 64 MiB | 내용 해시를 붙인 불변 원본, 실제 SHA 검증 |
| 큰 대화·도구 결과의 텍스트 원문 | 대화 10,000,000 UTF-16자, text original file 40 MiB | private original 파일과 원문 hash/range |
| 폰 raw journal segment | 32 MiB | 원래 파일명·정확한 bytes·본문/컨테이너 SHA를 보존 |
| ZIP entry 수 | 100,000개 | 중앙 디렉터리 메타데이터는 entry 수에 비례 |

Work 업로드 10 MiB/파일, 전체 20 MiB 및 사진 미리보기 decode 한도는 저장 파일 한도와 별개다. 큰 작업 binary를 모델에 올리거나 문자열·bitmap으로 전부 RAM에 읽는다는 뜻이 아니다. 파일 텍스트 읽기는 기존 bounded range로 제한한다.

NDJSON authoritative table과 checksum index는 작업 파일 64/512 MiB 제한을 재사용하지 않는다. 전체 백업 한도와 row 길이·entry 수로 제한하며 스트리밍한다. authoritative row는 최대 8 MiB, checksum row는 최대 8,192자다. 원문 binary는 byte 단위로 다시 검증한다.

## ZIP64와 저장 위치

최저 지원 API26의 공식 Android 8 libcore [ZipOutputStream](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/java/util/zip/ZipOutputStream.java)와 [ZipInputStream](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/java/util/zip/ZipInputStream.java)은 ZIP64 크기·offset·descriptor를 처리한다. 따라서 4 GiB 이상을 위해 앱 전용 여러 조각 포맷부터 만들 필요는 없다. 작은 강제 ZIP64 fixture의 실제 parsing/restore도 회귀에서 확인한다. 이는 물리 기기에서 64 GiB 전송을 완료했다는 측정이 아니다.

사용자가 선택한 SAF provider의 남은 용량은 일반 OutputStream/InputStream만으로 알아낼 수 없다. FAT32나 provider별 단일 파일 한도가 먼저 쓰기를 거부할 수 있다. 앱은 이를 64 GiB를 쓸 수 있다고 가정하지 않으며 쓰기·flush 오류와 단계를 반환한다. 새 문서로 내보내고 기존 완성 백업을 덮어쓰지 않는다. 실패한 외부 문서는 불완전할 수 있으므로 그것을 완성 백업으로 표시하지 않는다. caller의 provider stream close도 UI 성공 안내 전에 완료해야 한다.

다중 볼륨 포맷은 제한된 저장 위치가 필요할 때의 후속 선택지다. 현재 구현은 무료 계정에 64 GiB의 별도 저장 공간을 제공하지 않는다. 사용자에게 이미 있는 저장 위치의 실제 용량을 사용한다.

## 사전 점검과 복원 공간

`backupPreflight(store[,policy])`는 IO worker에서 실행한다. 실제 private filesystem의 usable space, 적용 한도, 현재 file lengths·DB/WAL 기반의 보수적인 예상치를 반환한다. `expandedEstimateExact:false`, `providerCapacityKnown:false`를 명시한다. 동시에 생긴 파일과 JSON/page overhead 때문에 estimate를 실제 완성 크기로 주장하지 않는다.

기본 private filesystem reserve는 256 MiB다. 실제 usable space를 다시 읽으며 entry/단계 경계, 16 MiB stream 구간과 DB/index batch에서 확인한다. SQLite copy는 512행씩 진행하고 원문·메모리 index 작업에도 점검을 넣는다. 복원 namespace 교체 직전에는 staged DB와 text originals를 기반으로 commit/WAL 공간을 보수적으로 계산한다. 이 추정과 periodic check가 다른 process의 동시 공간 사용이나 OS 쓰기 실패를 완전히 없애는 것은 아니다.

복원 peak에는 기존 앱 데이터, 펼친 원문·사진, 추출 NDJSON, staged SQLite, live DB/WAL 및 재구축 파생 색인이 포함된다. 로컬에 ZIP을 저장했다면 그 사본의 공간도 별도다. 본문을 읽은 DB transaction은 검증이 끝난 table copy와 함께 유지되어 실패 시 rollback한다. directory swap은 기존 commit token journal로 되돌리거나 완료한다. 복구 자체가 실패하면 양쪽 generation과 journal/staging을 보존하며 무작정 임시 파일을 지우지 않는다. `restoreRecoveryPending()`이 true면 정상 복원이 완료됐다고 표시하면 안 된다.

사진을 오래 전송하는 export는 authoritative table streaming이 끝나면 SQLite read snapshot을 닫는다. immutable original/media streaming 동안 수시간 read view를 유지해 WAL checkpoint를 계속 막지 않는다. 관련 [SQLite WAL 동작](https://sqlite.org/wal.html#checkpointing)을 따른다.

## 오류와 검증 범위

`BackupFailure`는 `code`, `phase`, `relativePath`, `processedBytes`, `availableBytes`, `requiredBytes`, `complete:false`를 반환한다. 설정 오류, 압축/펼친 bytes 한도, 개별 파일 한도, private 공간 부족, provider write/flush/read 오류, archive 검증과 DB/복구 실패를 구분한다. provider가 마지막 쓰기의 일부를 받았을 수 있으므로 오류 시 processed count를 저장 위치의 확정 파일 크기로 주장하지 않는다.

DB commit token을 검증할 수 있으면 `restoreCommitted`도 반환한다. `true`인 정리 실패는 복원 DB가 이미 반영된 상태이며, 이전 데이터가 그대로라고 안내하거나 확인 없이 복원을 반복하면 안 된다. 복구 journal이 남은 경우에는 `restoreRecoveryPending()`을 확인해 정상 실행 재개를 막는다.

합성 테스트는 작은 설정 한도, 압축률이 높은 과대 entry, 사전·extract 중 공간 부족, SQL 일부 copy 뒤 공간 부족, 모든 원문·DB·namespace·autonomy/token rollback, provider write/flush 실패, 작은 ZIP64 fixture, slow media 중 WAL checkpoint, 그리고 64 MiB+17 bytes sparse binary의 실제 stream/backup/restore SHA를 확인한다. GB짜리 byte 배열을 만들지 않는다. 실제 S24 Ultra의 64 GiB provider 전송, 전원 차단과 물리 디스크 고갈은 별도 검증이다.
