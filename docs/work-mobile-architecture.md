# 호영이 Android 앱과 Work Cloud 설계

호영이는 사설 VM과 상시 PC 없이 폰 앱에서 Work Cloud에 작업을 맡기는 방향을 검토한다. 추가 서버 비용은 0원이며 클라우드 컴퓨터의 영구 유지는 요구하지 않는다. 기억, 작업 원본과 결과, 대기 작업은 폰이 보관하고 새로운 클라우드 작업에 필요한 자료를 전달한다. Work 임시채팅 연결과 Android 상시 호출은 실제 계정과 기기 검증이 필요한 구현 조건이다.

현재 `mobile`에 Android 앱의 대화·사진 첨부, Work DOM 브릿지, 내부 원문·기억·파일 저장, 백업·복원, 자율 호출 서비스와 휴대폰 도구를 구현했다. 데스크톱 실행기를 사용하지 않는다. APK 빌드와 자동 테스트는 실제 계정의 Work 임시채팅 지원이나 24시간 기기 실행을 대신하지 않는다. [앱 설치와 검증](../mobile/README.md)

## 실행과 기억의 역할

폰은 대화 UI, 개인 기억 DB, 입력·작업 큐, 호출 일정, 완료 결과 저장과 알림을 맡는다. Work Cloud는 해당 계정에서 제공하는 클라우드 브라우저와 코드·셸 도구로 작업한다. 사설 VM은 별도로 확보·관리하는 컴퓨터이며 이 설계에는 포함하지 않는다. Codex Cloud와 Work Cloud는 서로 다른 제품 경로이므로 임의로 같은 연결 방법을 적용하지 않는다.

새 임시채팅에 모든 과거 대화를 매번 넣지 않는다. 대화 원문은 폰에 보존하고 현재 목표, 관련 기억의 출처, 최근 대화, 미완료 작업과 필요한 프로젝트 파일을 검색해서 전달한다. 답변과 실제 작업 결과를 분리하여 저장하며 요약은 원문을 대체하지 않는다.

작업 파일도 기억과 독립적으로 보존한다. 코드 생성·설치·수정 결과는 폰으로 회수하고 다음 환경에는 필요한 프로젝트 원본과 의존성 명세를 다시 전달한다. 대화 기억만 저장했다고 클라우드의 모든 프로그램이나 Windows 전용 앱을 사용할 수 있다고 가정하지 않는다.

## 참고 브릿지의 지원 범위

[참고 저장소](https://github.com/miuuyy/codex-chatgpt-web)는 버전 6.1.8, 커밋 `0b6087c5307c7a58adda9c420358f3f70d2035f5` 기준 데스크톱 Electron·Bun·Playwright 브릿지다. 배포 대상은 macOS, Windows, Linux이며 Android용으로 이식된 상태가 아니다.

임시채팅 URL은 이미 기본값이다. 현재 설정에 Work 선택은 없으며 일반 Chat 모델 선택을 검증한다. 루트 화면의 Work 전환을 새로 연결할 가능성은 있지만 실제 Work 임시채팅 지원을 증명하지 않는다. [채팅 URL](https://github.com/miuuyy/codex-chatgpt-web/blob/0b6087c5307c7a58adda9c420358f3f70d2035f5/src/chatgpt-session.ts#L4), [모델 선택](https://github.com/miuuyy/codex-chatgpt-web/blob/0b6087c5307c7a58adda9c420358f3f70d2035f5/src/adapters/chatgpt-web/model-selection.ts#L69)

Full harness의 터미널·파일 도구는 바깥 Codex 실행기에 전달되어 그 기기에서 실행된다. 이것을 Work의 클라우드 컴퓨터로 오인하지 않는다. Browser-only는 제공되는 ChatGPT 자체 도구를 사용할 수 있으므로, 폰 앱이 Work의 호스팅된 도구를 사용하는 별도 어댑터를 검토할 수 있다. 브라우저 요청 처리 자체에 Codex CLI가 반드시 필요한 것은 아니다. [도구 실행 위치](https://github.com/miuuyy/codex-chatgpt-web/blob/0b6087c5307c7a58adda9c420358f3f70d2035f5/src/adapters/chatgpt-web/prompt.ts#L518), [Responses 수신](https://github.com/miuuyy/codex-chatgpt-web/blob/0b6087c5307c7a58adda9c420358f3f70d2035f5/src/server.ts#L1234)

## Work 작업과 임시채팅

Work의 클라우드 브라우저는 기기 화면을 닫은 뒤에도 접수된 작업을 진행할 수 있다. 사용자가 필요한 정보, 로그인, 확인을 제공해야 하면 대기한다. 이미 시작한 클라우드 작업의 계속 실행과 폰이 다음 작업을 자동으로 시작하는 기능은 별개다. [공식 클라우드 브라우저](https://help.openai.com/en/articles/20001280-using-cloud-browser-in-chatgpt)

Work와 Codex는 사용 구조를 공유하고 Work 모델에는 GPT-6.1 Sol이 포함되지만, 실제 계정의 모델·medium·Fast 선택을 확인해야 한다. 일반 Chat용 브릿지의 모델 목록이 Work 지원을 대신하지 않는다. [Work와 Codex](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex)

임시채팅은 저장하지 않으면 채팅 목록에 나타나지 않고 떠난 뒤 목록에서 재개할 수 없다. OpenAI가 최대 30일 사본을 보관할 수 있으며 임시 상태에서는 기존 기억을 사용할 수 있어도 새 기억을 생성·수정하지 않는다. 앱의 기억 보존과 모델에 필요한 맥락 전달이 필요하다. Work와 임시채팅 조합의 실제 지원, 작업 완료 감지와 파일 회수는 별도 계정 검증 조건이다. [임시채팅](https://help.openai.com/en/articles/8914046-temporary-chat-in-chatgpt)

## Android 저장과 호출

대화·기억·작업 저널은 앱 내부 `filesDir`와 SQLite 또는 Room에 저장한다. `cacheDir`에는 재생성 가능한 임시 자료만 둔다. `Android/data/<패키지>/files`도 영속용 외부 앱 전용 저장소지만 경로 이름 자체가 보존을 보장하는 것은 아니다. 일반 캐시 삭제와 앱 데이터 삭제·제거는 구분하며 사용자에게 내보내기와 복원을 제공한다. [앱 전용 저장소](https://developer.android.com/training/data-storage/app-specific), [Room](https://developer.android.com/training/data-storage/room)

WorkManager의 반복 최소 간격은 15분이고 실제 실행은 지연될 수 있다. 매분 모드는 사용자가 시작하는 foreground service와 알림, 적절한 서비스 유형, 배터리 최적화 예외를 검토한다. Android 15 이상의 dataSync·mediaProcessing 서비스는 24시간당 6시간 제한이 있으며 모든 유형에 같은 제한이 있는 것은 아니다. Galaxy S24 Ultra의 화면 꺼짐·절전·재부팅·통신 재연결에서 장시간 검증해야 한다. [반복 작업](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby), [서비스 시간 제한](https://developer.android.com/develop/background-work/services/fgs/timeout)

한 번에 활성 작업 하나를 관리하고 새 메시지는 저장한 뒤 조향하거나 다음 요청에 포함한다. 한도 소진 시 모델 호출을 중지하고 앱이 저장된 메시지와 함께 한도 안내를 표시한다. 연결 유실이나 답변 유실에서 외부 행동을 무조건 재전송하지 않는다. 중단 후 누락된 매분 판단을 모두 반복하지 않고 현재 상태를 한 번 재평가한다.

## 구현 전 확인 조건

- 실제 계정에서 Work와 임시채팅을 동시에 선택할 수 있는지 확인한다.
- Android에서 로그인·전송·완료 감지·결과 회수를 수행하는 브라우저 연결을 검증한다. 데스크톱 패키지를 그대로 실행하는 것으로 대체하지 않는다.
- Work가 실제 클라우드 코드·브라우저 도구를 사용하는지 확인하고 일반 Chat이나 로컬 도구 실행으로 조용히 바꾸지 않는다.
- 사용자가 지정한 기억 파일의 본문을 확보한다. 공유 링크는 현재 읽기 도구에서 로그인 페이지로 이동했고, 접근 가능한 개발 Space의 두 Page에도 파일 참조가 없었다.
- 실기 백그라운드 호출과 한도·중단·불명확한 작업의 복구를 검증한다.

공식 웹 예약 작업은 분 단위 반복을 설명하지만 임시채팅에서 생성·재개할 수 있는지와 폰의 최신 기억을 공급하는 경로가 검증되지 않았다. 이를 이미 해결된 24시간 앱 연결로 취급하지 않는다. [채팅 내 예약 작업](https://learn.chatgpt.com/docs/automations#schedule-a-task-inside-a-chat)
