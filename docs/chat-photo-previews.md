# 채팅 사진 미리보기의 작업량과 수명

`ChatAttachmentPreviews`는 채팅 사진을 별도의 낮은 우선순위 작업 스레드에서 읽는다. 명령 전송·일시 정지·파일 내보내기를 처리하는 Activity의 IO 실행기를 공유하지 않는다. 원본 파일이나 첨부의 SHA-256/크기/식별자는 변경하지 않는다.

## 화면에 필요한 사진만 읽기

등록된 사진 수와 실제 decoder 작업 수는 다르다. 창에 붙어 있고, 표시 상태이며, 화면에서 잘린 영역이 실제로 존재하는 `ImageView`만 후보가 된다. 화면 가운데와 가까운 순서로 최대 8개를 선택한다. 한 개의 photo worker와 최대 4개의 대기 작업을 사용하며, 대기열이 가득 차면 다음 완료 또는 스크롤 이벤트에서 다시 시도한다. 화면 밖 사진은 IO를 시작하지 않는다. 이미 읽은 사진이 화면 밖으로 나가면 drawable을 먼저 분리하고 이 helper가 소유한 bitmap을 즉시 재활용한다.

기본 썸네일의 가장 긴 변은 `AttachmentPreview.THUMBNAIL_SIZE`인 512다. 화면에 할당한 썸네일의 실제 bitmap allocation 합계는 12 MiB 이하로 제한한다. 이는 폰 전체의 heap 한도가 아니다. 현재 실행 중인 decoder와 확대 결과, 원본 읽기 버퍼는 별도로 존재한다. 확대는 별도 UI가 소유하며 마지막 요청만 전달한다.

## 재사용·화면 전환·종료

같은 `ImageView`를 다시 등록해도 이전 결과가 새 사진에 적용되지 않는다. 메시지 tree를 교체하거나 탭을 바꾸기 **전에** `clear()`를 호출해야 한다. Activity 종료에서는 `close()`를 호출해야 한다. 두 메서드는 대기 작업을 취소하고 등록 view listener와 helper가 소유한 bitmap을 해제한다. 실행 중인 플랫폼 decoder를 강제로 멈춘다는 보장은 없다. 대신 해당 실행 세대와 binding을 무효화하고, 뒤늦게 도착한 결과를 적용하지 않고 재활용한다. 이미 decoder가 끝났지만 UI callback이 아직 실행되지 않은 bitmap도 `clear()`가 즉시 해제한다.

`loadExpanded(metadata, callback)`의 준비된 결과를 callback으로 넘긴 순간, bitmap 소유권은 호출자에게 이동한다. 호출자는 확대 dialog의 drawable을 해제한 뒤 dialog 종료와 Activity 종료에서 bitmap을 재활용해야 한다. helper의 `clear()`는 이미 전달한 확대 bitmap을 해제하지 않는다. 확대 역시 전송·일시 정지 실행기를 공유하지 않는다.

공개 UI 메서드는 main thread에서 호출한다. scroll/layout observer에서 `updateViewport()`를 호출하면 한 번의 main callback으로 합쳐진다. placeholder는 작은 custom drawable이며 별도 bitmap을 만들지 않는다. 읽기 실패나 allocation 한도 초과는 placeholder로 표시하고 같은 binding에서 무한 재시도하지 않는다. 원본 자료는 그대로 남는다.

## 검증 범위

`ChatAttachmentPreviewsTest`는 Robolectric Android 28의 **native graphics**, 실제 PNG 원본·실제 bitmap·붙어 있는 Android view tree로 다음을 검증한다.

- 사진 80개 중 잘린 화면에 보이는 3개만 읽고, 스크롤 시 이전 bitmap을 해제한다.
- 24개가 동시에 보이는 tree에서도 선택은 8개, 대기열은 4개 이하이며 포화 후 누락된 선택 작업을 재시도한다.
- 부모 bubble이 여전히 있는 detached tree, 같은 view 재등록, decode 중 clear/close가 늦은 결과를 적용하지 않는다.
- 이미 작업이 끝나 UI callback을 기다리는 bitmap도 clear 시 즉시 해제한다.
- 단일 과대 allocation과 여러 bitmap의 합계 한도를 적용한다.
- 확대의 마지막 요청만 전달하고 전달 후 호출자 소유권을 유지한다.
- 종료 시 streaming FileChannel이 interrupt로 실패해 bitmap을 만들지 않는 경우도 worker 완료와 할당 0을 확인한다. 이미 만들어진 실제 PNG bitmap의 늦은 전달은 별도 latch로 확인해 해제한다.

이는 실제 Galaxy S24 Ultra에서의 장기간 배터리·GPU·메모리 측정이나 200 MP 파일 지원의 검증이 아니다. 원본 decoder의 허용 크기와 wire 전송 한도는 `AttachmentPreview` 및 전송 계층이 각각 관리한다. 이 helper는 화면 밖 이미지의 일괄 읽기와 종료 뒤 오래된 결과 적용을 제한한다.
