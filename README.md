# 호영이 자율 에이전트

현재 방향은 추가 서버 비용 0원, 사설 VM·상시 PC 없이 **Android 전용 앱 → ChatGPT Work 임시채팅**으로 작업하고 기억·파일은 폰에 저장하는 구조다. [Android 앱 설치와 검증](mobile/README.md)을 우선 참고한다. 아래 Node/사설 VM 실행 코드는 기존 구현을 보존한 것으로, 새 모바일 Work 연결의 실계정 검증 완료를 뜻하지 않는다.

모바일의 사진·파일 전송, 실제 모델 선택, 입력·프로세스 복구, 휴대폰 관찰과 장기 개인화를 한 흐름으로 검증하는 기준은 [사용자 흐름 검증](docs/mobile-product-acceptance.md)에 기록한다. 저장·전송·모델 분석·실제 완료를 각각 확인하며 실계정·실폰에서 아직 확인하지 않은 결과를 완료로 표시하지 않는다.

공개 APK와 소스는 [호영 0.3.0 배포](https://github.com/dog898996/be3hyu/releases/tag/v0.3.0)에서 내려받는다. 요청 권한 40개를 유지하며 정정·삭제의 출처 연결, 캐시·옛 백업의 재노출 방지와 [5년 모의 기억 답변 144개](docs/five-year-memory-final-answer-results.md)를 검증했다. [최종 Android 검사](docs/benchmarks/android-final-v030-local.json)는 759개 중 743개 통과·선택형 16개 제외·실패 0개이고, [실제 OS 복구 검사](docs/benchmarks/android-final-ci-ad85406.json)는 강제 중지 후 재열기·프로세스 종료·Java 오류·재부팅 복구를 통과했다. 실제 갤럭시 장기 실행과 실계정 Work 연결은 아직 확인하지 않았다. 전체 Android·Node 소스, 테스트, 배포 도구와 개발 기록을 함께 보관하며 계정 비밀번호와 서명 개인키는 공개 소스에 넣지 않는다.

스페이스의 [자율 비서 개발 대화 원문](https://chatgpt.com/space/page_3dbe1b11044481918d703e85554f9e94) 41개 메시지와 후속 요구사항을 이어받은 구현이다. 호영이는 사용자 한 명이 쓰는 전용 채팅앱이며 별도 앱 회원가입·로그인 계정을 만들지 않는다. Instagram DM 수신·다른 사람 구분·읽음은 현재 대화 수단의 개발 범위에서 제외했다. 기존 기록과 레거시 연결 정보는 유지한다. 비밀번호는 코드와 설정에 포함하지 않는다.

Oracle Always Free ARM 서버 설치와 월 1만~2만원 서버 예산을 검토한 이력은 남겨 둔다. 최신 요청은 추가 비용 0원이고 유료 전환이나 사설 서버 배포를 수행하지 않았다. 구현·검증 범위와 계정 연결 상태는 [인수 기록](docs/handoff.md)에 기록한다.

이전 [사설 VM 작업 구조](docs/cloud-agent-architecture.md)의 native 엔진은 `ASSISTANT_ENGINE=codex-native`로 선택할 수 있다. 현재 모바일 앱은 이 사설 VM이나 CLI 실행기를 요구하지 않는다. 기존 [운영 절차](docs/native-cloud-operations.md)는 사설 VM을 별도로 확보할 경우에만 적용한다.

## 기존 Node 실행

Node 24 이상:

```sh
npm ci
npx playwright install --with-deps chromium
npm start
```

전용 앱 화면은 로컬 http://127.0.0.1:8080 이다. 로컬 전용 모드는 로그인 대화상자 없이 채팅 화면을 연다. 대화·기억·프로젝트·브라우저 프로필은 기본적으로 .data/에 보존한다. 무료 상시 서버 설치는 [Oracle 배포 문서](docs/oracle-free.md)를 따른다. 네트워크에 공개할 때의 APP_AUTH_TOKEN 접근 보호는 앱 회원 계정과 별개다. Cloudflare Containers는 현재 계정에서 유료 플랜이 필요해 사용하지 않는다.

```sh
npm test
docker compose up -d --build
```

## 기존 Node 구현의 동작

- 실행기가 계속 상주하고 60초마다 상황을 판단한다. 작업 완료·새 주인 메시지·설정 변경은 다음 행동을 즉시 깨운다. 단순한 시간별 새 채팅을 만드는 구조가 아니다.
- 긴 터미널 작업·모델 응답과 앱 메시지 수신을 분리한다. 사용자의 새 앱 메시지는 진행 중 작업에 방향 변경을 전달한다. 현재 실행기의 중단·재개 방식과 native `turn/steer` 연결은 구분한다.
- 답변·선톡·한도 안내는 앱의 메시지와 outbox에 원자적으로 보존하고 재시작·재시도 중 중복을 방지한다. 이 영수증은 로컬 앱 저장을 확인하며 휴대폰 push 전달·화면 읽음을 증명하지 않는다. Instagram 연결은 기본으로 꺼져 있고 앱 대화에는 사용하지 않는다.
- 대화 원문과 기억을 별도로 저장한다. 기억에는 출처, 날짜, 사실/추론/불확실/의견, 확신과 버전이 있다. 수정은 예전 기억을 덮어 없애지 않고 이전 버전을 남긴다. 사용자 피드백과 작업 경험도 판단에 사용한다.
- 전용 앱 입력은 단일 사용자의 대화·명령으로 처리한다. 외부 사이트·파일의 내용은 사용자 명령과 구분하며 앱 이용자별 계정이나 Instagram 발신자 확인을 요구하지 않는다.
- 영속 브라우저는 여러 사이트와 탭, 파일 업로드·다운로드·클릭·입력을 지원한다. Bash·Node·Python·Git과 패키지 설치, 파일 작업, 목표·대기 작업, 선톡용 outbox를 제공한다.
- 모든 기능 권한과 에이전트 설정 변경은 기본 허용이다. UI와 주인 대화로 바꿀 수 있다. 맞춤 지침 15개는 src/defaults.mjs에 원문으로 들어 있고 UI에서 수정할 수 있다.
- 모델 한도가 실제로 감지되면 작업과 기억을 보존하고 모델 호출을 대기한다. 앱의 새 입력은 계속 저장하고 모델 호출 없이 한도 소진 안내를 보여준다. 재시작에도 대기를 유지한다. 복구 시간이 지났다는 이유만으로 해제하지 않으며 native 읽기 전용 사용량 조회에서 정상 사용이 확인돼야 재개한다.
- 현재 선호는 GPT-6.1 Sol / 중간 추론(medium) / Fast다. 개발 계정의 실제 모델 목록·유효 설정에서 `gpt-6.1-sol / medium / priority`를 확인했다. 최신 요청은 출처가 있는 기억 버전과 일회성 설정 revision으로 반영하며 이후 UI 수동 설정은 재시작에도 유지한다. 운영 서버에서는 새로 인증한 계정의 조합을 재검증한다.

## 기존 Node 구현의 모델 연결

기본 연결은 공식 Codex CLI의 ChatGPT 플랜 인증이다. 앱 회원가입은 필요 없다. 모델을 쓰기 위한 최초 Codex 구독 연결은 별개이며 공식 기기 인증 후 /data/codex의 인증 캐시를 재사용한다. 유료 API 키로 자동 전환하지 않는다. 기존 구매 크레딧·자동 충전은 계정 정책을 별도로 확인해야 하므로 API fallback이 없다는 사실만으로 추가 비용 0원을 보장하지 않는다.

Codex CLI는 실제 ChatGPT 로그인 상태, 모델 목록과 유효 설정을 확인한 후 사용할 수 있다. 앱 전용 Codex home의 `AGENTS.md`에 현재 맞춤 지침과 영구기억 정책을 반영하며 같은 지침을 입력에 중복 복사하지 않는다. 읽기 전용 샌드박스와 확인된 네이티브 기능 제한을 적용하고 호영이의 도구 실행기는 별도로 권한을 확인한다. 네이티브 도구를 완전히 제거하는 지원 방식은 확인하지 못해 외부인 입력은 이 어댑터로 전달하지 않는다.

CLI에서도 `codex cloud exec`로 구독 Cloud 작업을 보낼 수 있다. 이 명령을 매번 실행하면 새 작업이 생기므로 호영이의 상주 운영 경로에는 사용하지 않는다. 고정 Cloud VM의 native 엔진은 공식 `codex app-server`를 한 번 실행해 유지하고 native thread·turn·기본 도구를 사용한다. 앱 기억·브라우저·설정·메시지·목표는 동적 도구로 연결한다. 실제 계정의 운영 모델 생성은 아직 검증하지 않았다. [CLI 심층 검토](docs/codex-cli.md)와 [전체 기능 검토](docs/codex-native.md)를 참고한다.

선택 가능한 ChatGPT 웹 어댑터는 로그인된 브라우저에서 실제 응답 경계와 JSON 계획을 확인한다. ChatGPT 채팅 목록에 호영이의 대화를 남기지 않도록 매번 새 임시 채팅으로 이동하며, 일반 채팅으로 바뀌면 입력·전송 전에 중단한다. 기존 설정에 일반 URL이나 저장된 대화 주소가 있어도 모델 요청에는 새 임시 채팅을 사용한다. upstream 웹쫀쿠의 /v1/responses Full 모드는 진짜 Codex 실행기 메타데이터와 rollout이 필요하므로 독립 실행기의 일반 API처럼 가장하지 않는다. 공개 브라우저 구현을 참고한 부분의 MIT 고지는 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)에 있다. 현재 앱은 자체 구현이며 OpenClaw를 설치하거나 통합한 상태는 아니다. OpenClaw 자체가 무료여도 컴퓨터와 모델 이용 권한은 별도로 필요하다.

대화 원문과 장기기억은 호영이의 자체 SQLite 저장소에서 보존한다. 기본 CLI의 로컬 세션 기록도 유지하도록 불필요한 `--ephemeral` 기본값을 제거했다. 명시적 `ephemeral:true`만 native 로컬 기록을 끈다. 로컬 CLI 기록과 일반 ChatGPT 채팅 목록은 별개다. GPT 추론에 필요한 입력은 OpenAI로 전송되므로 로컬 보관을 서버 전송·제공자 보관 없음으로 설명하지 않는다. 실제 모델 생성으로 native 파일 보관과 계정 기록 화면을 검증한 것은 아니다.

다른 사이트의 계정 관리가 필요하면 별도 브라우저·도구 작업으로 연결한다. 앱 대화 경로를 Instagram DM에 의존시키지 않는다.

## 기존 Node 구현의 도구 추가

영구 데이터 폴더의 tools/*.mjs에 다음 형태의 모듈을 만들면 다음 판단 단계에서 도구가 등록된다. 비서도 files/terminal 도구로 설치할 수 있다.

```js
export default {
  name: 'format_project',
  description: '프로젝트 코드 포맷 검사',
  capability: 'terminal',
  parameters: {
    type: 'object',
    properties: { cwd: { type: 'string' } },
    required: ['cwd'],
    additionalProperties: false,
  },
  async execute(args, { services, signal }) {
    return services.terminal.execute({
      cwd: args.cwd,
      command: 'npm run format:check',
    }, signal);
  },
};
```

기존 도구 이름 충돌, 잘못된 스키마, 비활성 권한, 외부인 명령, 실행 오류를 구분한다. 도구 파일 변경과 삭제도 반영한다. 작업 분담은 공유 영속 작업 큐를 사용하며, 실제 동시 모델 판단 수는 사용하는 모델 연결부에 영향을 받는다.

## 확인 범위

자동화 테스트는 실제 권한·재시작·중복 실행·조향·한도·기억 증거를 검증한다. 테스트용 모델과 채널은 테스트 파일 안에서만 사용하며, 제품에는 성공을 흉내 내는 모델 응답이나 DM 연결 모드가 없다.

이전 Node 작업에서 Docker 이미지, Chromium HTTPS 접속, 원문 41개 메시지 수입, 재시작 후 기록 보존과 관리 UI를 확인했다. Node native 엔진의 연결·조향·한도·복구는 공식 프로토콜 대역으로 검증한다. 새 Android 앱은 APK 빌드와 인공 데이터의 저장·파일·휴대폰 도구·웹 브릿지 테스트를 수행한다. 실제 계정 Work 도구 실행, 갤럭시 장시간 실행, push 및 OCI ARM 운영은 별도 검증 대상이다.

기존 Node 구현의 장기 기억 검색은 SQLite FTS와 한국어 부분문자열 검색이다. 현재 Android 검색·삭제·검증은 [모바일 안내](mobile/README.md)와 [최종 5년 모의 기억 결과](docs/five-year-memory-final-answer-results.md)를 따른다. 원문은 보존하지만 모든 의미적 연관성을 찾아주는 검색이나 Dot 이상의 개인화 성능을 이미 증명한 것은 아니다. 별도 native archive·모델 제공자의 기록·연결되지 않은 파일·이전 백업의 삭제는 별도 조치가 필요하다.
