# 첨부 원본과 도구 결과 보관

첨부의 원본 binary와 그 내용에 대한 모델의 해석은 별개다. 이미지나 파일을 받았다고 해서 OCR·내용 분석·검색 인덱싱까지 완료했다고 표시하지 않는다.

## Immutable 원본

SAF에서 사용자가 선택한 파일은 `Context.filesDir/projects/.attachment-originals/<SHA-256>.<safeext>`에 streaming copy한다. 복사하면서 SHA-256을 계산하고 파일 `fsync`, atomic rename, directory `fsync` 후 실제 저장 byte hash를 다시 확인한다. 같은 내용과 확장자의 payload는 재사용한다. 첨부 이름·ContentResolver가 제공한 MIME·크기·용도는 private sidecar에 보관한다. MIME은 provider의 선언이며 파일 형식을 독립적으로 판별했다는 뜻은 아니다.

`saveFromUri(Uri,String)`의 반환 `id`는 이 immutable ID다. 기존 작업 파일을 첨부할 때 `freezeAttachments(JSONArray)`는 문자열 ID 또는 metadata 객체를 받아 snapshot을 만든다. 이후 원래 작업 파일을 덮어써도 그 메시지에 연결된 binary는 바뀌지 않는다. SHA·size를 명시한 입력이 실제 byte와 다르면 거부한다. 일반 `writeFile`/`files.write`는 reserved prefix와 그 위치를 가리키는 내부 symlink alias를 덮어쓸 수 없다. 일반 파일 삭제 tool은 제공하지 않는다. 폰 OS 밖에서 데이터를 강제로 수정하는 것을 막는 보안 경계는 아니며, read/export/freeze 시 실제 hash가 다르면 원본 확인을 실패로 처리한다.

메타데이터의 핵심 필드는 `originalAssetId`, `sha256`, `sourceName`, `mime`, `size`, `kind`다. `kind: owner_attachment`는 사용자가 첨부한 원본, `kind: tool_result`는 도구 실행 결과다. 같은 binary의 원본 파일은 공유될 수 있고, 메시지·action별 출처와 분석 결과는 각 메시지 metadata/영수증에 별도로 연결한다. 파일 목록에는 payload만 보이고 내부 sidecar는 숨긴다.

SHA 계산과 파일 복사는 크기에 비례한다. import/freeze/archive/read 작업은 UI main thread에서 호출하지 않는다. 파일당 64 MiB를 초과하면 명시적으로 실패하며 기존 원본을 지우거나 잘라 저장하지 않는다.

## 내용 파악 상태

- `raw_saved`: 정확한 원본 binary와 hash를 보관했다. 텍스트를 추출하거나 모델이 이해했음을 의미하지 않는다.
- `provider_analyzed`: 실제 provider 분석 결과를 원본 asset hash와 사용자 메시지/도구 영수증에 연결해 저장한 경우에만 부여할 상태다. 이 저장 계층은 분석 자체를 수행하거나 이 상태를 자동으로 생성하지 않는다.
- `needs_reanalysis`: binary는 있으나 관련 분석이 없거나, 분석의 provenance가 부족하거나, 새 질문에 다시 원본 분석이 필요한 상태다.

이 계층의 snapshot 반환은 항상 `raw_saved`다. 업로드 성공, 파일 이름, MIME, 모델이 파일을 봤다는 추측으로 `provider_analyzed`를 만들지 않는다. 기억 검색이 binary를 문자열로 읽은 결과를 이미지 내용의 원문이라고 주장해서도 안 된다.

## 큰 도구 결과와 추가 읽기

`archiveToolResult(rootRequestId,actionId,text)`는 최대 10,000,000 UTF-16자의 전체 도구 결과를 UTF-8 immutable payload로 보관하며 `kind: tool_result`, 연결 ID, `totalChars`, hash를 반환한다. 그보다 크면 잘라 저장하지 않고 오류를 반환한다. JSON MIME은 호출자가 결과 문자열을 JSON으로 직렬화해 전달한다는 계약이며 이 함수가 JSON schema를 검증하는 것은 아니다. DB 영수증에는 작은 pointer를 저장하고 이 원문을 다시 읽을 수 있다.

`readTextRange(fileId,offsetChars,maxChars)`는 `text`, `offsetChars`, `nextOffsetChars`, `totalChars`, `complete`, `contentHash`를 반환한다. offset은 UTF-16 단위이며 surrogate pair 중간에서 시작할 수 없다. 끝 경계도 pair를 자르지 않는다. 1회 최대 1,000,000자다. range 읽기는 엄격한 UTF-8 decoder를 사용하며 유효하지 않은 byte sequence는 오류로 거부한다. 이미지/임의 binary를 정확한 텍스트로 변환했다고 주장하지 않는다. 기존 direct `readText`의 호환용 뷰와 달리 `files.read`/`readTextRange`는 대체 문자를 만들어 원문인 척하지 않는다. `contentHash`는 실제 binary의 SHA-256이다. 정확한 binary는 export로 복구한다. range 읽기는 전체 file을 순차 hash/decode하므로 작은 window를 반복 요청하는 비용도 파일 크기에 비례한다. tool `files.read`는 기본 20,000자, 최대 24,000자이며 `offsetChars`와 `nextOffsetChars`로 다음 부분을 이어 읽는다. 직접 Java API는 기존 최대 1,000,000자를 유지한다.

## 백업과 복원

원본과 sidecar는 기존 `projects` ZIP namespace에 포함된다. 복원 staging에서 payload 파일명 SHA, 실제 binary SHA/size, sidecar identity, 저장된 attachment metadata reference를 확인한 뒤에만 현재 DB/파일 tree를 교체한다. ZIP checksum을 새로 계산한 변조 archive도 content-addressed 원본이 일치하지 않으면 거부한다. 백업이 없으면 앱 데이터 삭제·제거로 private 원본도 지워진다. SAF에서 외부 저장소로 내보낸 백업이 필요하다.

현재 streaming 백업은 ZIP 압축 bytes와 펼친 payload 합계 각각 4 GiB, 파일당 64 MiB, 전체 ZIP entry 100,000개까지다. 제한을 넘으면 명시적으로 중단하며 오래된 원문을 조용히 제외하지 않는다. 폰 관찰의 immutable segment 보관은 작은 파일 수와 ZIP entry 부담을 줄이는 방식이지 원문 bytes를 줄이거나 백업 크기 한도를 없애는 방식은 아니다. 복원에는 staging과 기존 파일 tree가 동시에 존재할 여유 공간도 필요하다.

합성 회귀는 working file 덮어쓰기 후 원본 유지, prefix/alias 쓰기 차단, 동일 payload 재사용, provider MIME 유지, SHA/size 허위 claim 거부, 동일 길이/mtime 변조 감지, 정상 백업 복원과 checksum을 일관되게 바꾼 원본 변조 거부, 큰 도구 결과 및 emoji range 경계를 확인한다. 실제 기기의 전원 차단·가득 찬 디스크 검증은 별도다.
