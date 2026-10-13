# 호영이 전용 앱에서 Codex CLI를 사용하는 방식

2026-10-13, 설치된 CLI `0.159.2`, 공식 문서와 공개 소스 `f82e36592278a0a782fb0d0fc97e5fa10e832ac2`를 검토했다. 이번 검토에서 실제 모델 turn·로그인·Cloud 작업을 만들지 않았다.

## 개발 범위

호영이는 사용자 한 명이 쓰는 전용 채팅앱이다. 별도 앱 회원가입·로그인 계정을 만들지 않는다. 앱 대화는 사용자 입력이며 Instagram DM 수신·주인/다른 사람 구분·읽음 처리는 현재 범위에서 제외한다. 기존 원문과 레거시 연결 정보는 지우지 않는다. 로컬 전용 모드는 바로 채팅 화면을 열며, 서버를 외부에 공개할 때의 접근 보호는 회원 계정과 다른 항목이다.

Codex가 사용자의 ChatGPT 플랜을 사용하기 위한 인증은 별개다. 이미 연결된 프로필은 캐시한 인증을 재사용하고 토큰을 자동 갱신한다. 매 대화마다 로그인을 만들거나 비밀번호를 전달하지 않는다. 유료 API 인증으로 자동 전환하지 않는다.

## 로컬 기록과 서버 전송

기본 로컬 CLI의 전체 대화·작업 기록은 Codex home에 보관되고 재개할 수 있다. 기본 경로는 `~/.codex`이며 앱은 전용 `/data/codex`를 사용한다.

| 항목 | 역할 |
|---|---|
| `sessions/YYYY/MM/DD/rollout-…jsonl` | 전체 세션의 대화·이벤트 기록 |
| `archived_sessions/` | 보관한 세션 기록 |
| 상태·thread history SQLite | 세션 탐색·재개에 사용하는 인덱스와 기록 |
| `history.jsonl` | 주로 TUI의 이전 입력 재호출; 전체 대화 기록과는 다름 |
| `memories/` | 추출한 기억·요약·검색 근거 |
| `AGENTS.md` | 매 작업에 적용할 고정 지침 |

이 로컬 기록을 일반 ChatGPT 채팅 목록에 저장하는 웹 채팅으로 해석하지 않는다. 명시적 `codex cloud exec`는 서버에 Cloud 작업을 만드는 별도 경로다. CLI/데스크톱/Cloud의 기록과 일반 ChatGPT 기록을 구분한다.

GPT의 추론은 OpenAI에서 수행하므로 입력·필요한 맥락은 서버로 전송된다. 검토한 CLI 요청은 `store:false`를 사용하지만 이것은 서버의 모든 진단·보관이 없다는 보장이 아니다. ChatGPT 로그인은 그 계정·워크스페이스 정책을 따르고 API 인증은 API 조직 정책을 따른다. API의 기본 보관기간을 ChatGPT 플랜 인증에 그대로 대입하지 않는다.

현재 앱은 정상 로컬 세션 기록을 유지하는 설정을 기본으로 한다. 이전에 추가한 `--ephemeral`은 기본에서 제거했다. 명시적 `ephemeral:true`만 로컬 세션 저장을 끈다. 이 옵션은 네트워크 추론·인증을 없애는 기능이 아니다. 구성 확인과 실제 파일 생성 관측은 구분하며 실제 모델 생성으로 디스크 보관을 검증한 것은 아니다.

앱의 잊기 기능이 native 로컬 archive와 제공자 보관까지 자동 삭제한다고 표시하지 않는다. Native 자동 기억 연결 시 삭제 요청을 양쪽 원문·기억에 일관되게 적용해야 한다.

## CLI 실행 형태와 앱 연결

| 방식 | 적합한 용도 |
|---|---|
| `codex` | 사람이 터미널에서 대화·조향·재개하는 TUI |
| `codex exec` / `exec resume` | 자동화에서 한 turn을 실행하고 기록을 재개 |
| `codex app-server` | 전용 앱이 지속 세션·실시간 이벤트·조향·중단을 사용하는 연결 |
| `codex cloud exec --env …` | 선택한 Cloud 환경에 작업 제출 |

전용 앱의 새 native 엔진은 공식 app-server 지속 프로세스를 사용한다. TypeScript SDK는 검토한 버전에서 exec를 turn마다 실행하는 방식이고, 공식 Python SDK는 app-server 지속 연결과 thread/turn 제어를 제공한다. JavaScript 앱에서는 공식 JSON-RPC를 직접 연결했다. Codex 작업 엔진이 기본 도구를 실행하고 앱 기억·설정·브라우저·메시지 등만 동적 도구로 연결한다. 기존 exec·JSON 계획 경로는 별도 엔진으로 보존한다.

기본 연결 순서는 `initialize → initialized → thread/start 또는 thread/resume → turn/start`다. 완료는 시작 요청의 응답이 아니라 `turn/completed`에서 확인한다. 새 지시는 `turn/steer`에 현재 turn ID를 전달하고, 중단은 `turn/interrupt`를 사용한다. 재시작 후 thread를 복구하며 전송 결과가 불명확하면 저장된 기록을 먼저 확인한다. 메시지 ID만으로 서버 idempotency를 보장한다고 가정하지 않는다.

`ASSISTANT_ENGINE=codex-native`로 새 엔진을 선택한다. 지속 프로세스·작업 이벤트·조향·원문 저장·한도 대기를 연결했으며 실제 계정과 배포 VM의 작업 완료는 별도 검증 대상이다. 사진·휴대폰 기기 도구·Android APK는 아직 연결하지 않았다. 기존 exec 연결의 native 기능 제한을 Codex 자체가 지원하지 않는다는 의미로 설명하지 않는다.

작업은 [고정 Cloud VM](cloud-agent-architecture.md)에서 실행한다. 앱의 대화 세션을 바꿔도 worker·작업 디렉터리·브라우저 프로필은 유지한다. 폰의 HTTP 접속 종료를 native turn 중단 신호에 연결하지 않는다. `codex cloud exec`의 environment ID는 이러한 고정 worker ID가 아니다.

## Native Memories

`AGENTS.md`는 기본 지침 로더이므로 앱 설정을 파일에 반영하면 된다. 자동 Memories는 별도로 활성화하고 적격 과거 대화의 유용한 정보를 백그라운드에서 추출한다. 단순히 로컬 기록이 있다는 사실과 그 내용을 자동 기억으로 사용하는 것은 다르다.

검토한 소스에서 exec 세션은 자동 추출 대상에 포함되지 않는다. app-server의 기본 적격 source를 사용하는 지속 대화는 대상이 될 수 있다. 현재 thread는 추출에서 제외되며 idle 시간·남은 사용량·추출량 제한이 있어, 하나의 활성 대화만 영원히 재사용하면 그 대화가 계속 자동 추출된다고 가정할 수 없다.

원문과 반드시 유지할 선호·정정은 자체 영구 근거에 남긴다. Native 기억은 요약·검색 보조로 활용하고 전체 원문의 무손실 회상을 약속하지 않는다. 현재 앱의 native 자동 Memories는 아직 활성 연결 상태가 아니다.

## 한도 관리

한도 대기는 작업·기억·앱의 새 입력을 보존하며 모델 호출 없이 안내해야 한다. 사용량은 app-server의 읽기 전용 `account/read`와 `account/rateLimits/read`로 확인할 수 있다. 이 조회에는 새로운 thread·turn이나 모델 생성이 필요 없다.

`ordinaryUsageAllowed`는 true/false/null이며 null은 알 수 없음이다. 사용률과 리셋 시간이 있어도, 모르는 상태를 복구로 판단하지 않는다. 이미 한도 대기 중이면 인증된 계정의 새 사용량 조회에서 정상 이용이 확인될 때까지 작업을 보존한다. 조회 불가·불명확한 복구 시간은 대기를 유지하고 다시 조회할 시간을 정한다.

새 입력은 앱에 저장하고 정적 한도 안내를 보여준다. 기존 구매 크레딧·자동 충전은 구독 포함 이용량과 별개이며 계정의 추가 결제를 기본 사용으로 보장하지 않는다. 앱에서 크레딧 구매나 유료 API fallback을 자동 수행하지 않는다.

## 검토한 1차 자료

- [인증·캐시·자동 갱신](https://learn.chatgpt.com/docs/auth)
- [CLI 명령·ephemeral](https://learn.chatgpt.com/docs/cli/reference)
- [App-server](https://developers.openai.com/codex/app-server)
- [Memories](https://learn.chatgpt.com/docs/customization/memories?surface=cli)
- [ChatGPT/Codex 기록 구분](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex)
- [Codex 플랜과 데이터 정책](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan)
- [로컬 rollout 저장 소스](https://github.com/openai/codex/blob/f82e36592278a0a782fb0d0fc97e5fa10e832ac2/codex-rs/rollout/src/recorder.rs)
- [전송의 store:false](https://github.com/openai/codex/blob/f82e36592278a0a782fb0d0fc97e5fa10e832ac2/codex-rs/core/src/client.rs)
- [Native 한도 응답 계약](https://github.com/openai/codex/blob/f82e36592278a0a782fb0d0fc97e5fa10e832ac2/codex-rs/app-server-protocol/src/protocol/v2/account.rs)
