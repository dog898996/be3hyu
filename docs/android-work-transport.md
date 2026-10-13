# Android Work 연결: 현재 구현과 검증 범위

호영이는 폰에 원문·기억·작업 큐를 보관하고 Android WebView에서 ChatGPT Work에 작업을 보낸다. 사설 VM이나 데스크톱 Codex 실행기를 사용하지 않는다. 이 연결은 웹 UI를 관찰하는 자체 어댑터이며 공식 Work API가 아니다. 실제 계정에서 Work·임시채팅·GPT-6.1 Sol·Medium·Fast가 모두 선택됐다는 DOM 증거를 확인하기 전에는 전송하지 않는다.

## 원문과 실행 봉투

응답의 보이는 DOM 텍스트는 `text`에 보존한다. 이것은 서비스 내부의 원본 Markdown, 숨겨진 작업 로그, 바이너리 파일을 복제한 것이 아니다. Markdown 렌더링은 코드 fence와 서식을 제거할 수 있으므로, 보이는 텍스트만으로 인용한 코드를 앱 명령으로 해석하면 안 된다.

폰·앱 작업용 응답은 전체가 단일 JSON object이고 `protocol: "hoyoung.v1"`, 현재 `requestId`를 포함해야 한다. 일반 설명 안의 JSON 예시는 실행 봉투가 아니다. Runtime은 전체 형식과 요청 ID를 다시 검사한다.

- `rawEnvelopeEligible: true`는 단일 응답 본문에 코드·인용·목록·서식·비문자 콘텐츠가 없는 경우만 제공한다. JSON 파싱·요청 권한을 승인했다는 뜻은 아니다.
- 응답 전체가 단일 `pre/code`이고 `language-hoyoung`, `lang-hoyoung` 또는 일치하는 `data-language`로 언어가 확인되면, 코드 내용을 별도 `structuredEnvelope` 문자열로 제공한다. 출처는 `structuredEnvelopeSource: "whole-language-hoyoung-code-block"`다.
- 설명과 코드가 섞인 답변, 두 코드블록, blockquote/list의 코드, 언어 미확인·충돌, 숨겨진 코드 텍스트는 추출하지 않는다. `rawEnvelopeEligible: false`도 함께 전달하여 일반 텍스트 경로로 우회 실행하지 않게 한다.
- 코드 내부의 언어 표시와 복사 버튼은 허용하지만, 미확인 외부 헤더나 다른 콘텐츠를 코드라고 추정하지 않는다. 알 수 없는 실제 UI 변경에서는 원문 출력만 유지한다.
- 구조화 봉투는 250,000 UTF-16 문자까지다. 초과 시 `structuredEnvelopeUnavailable: "structured_envelope_too_large"`와 관찰한 길이를 제공하고 코드를 잘라 실행하지 않는다. 보이는 원문 캡처는 별도 한도를 따른다.

기본 프롬프트는 전체 답변이 단일 hoyoung fenced JSON 코드블록이 되도록 권장한다. 그래야 JSON 문자열의 `**강조**`·백틱·인용구가 Markdown 렌더링으로 변형되지 않고 코드 텍스트에 남는다. 서식 없이 출력한 단독 JSON도 지원하지만, 표시가 변형됐으면 원시 봉투로 사용하지 않는다. 실제 Work의 코드블록 마크업을 아직 캡처·검증하지 않았으므로 모든 표시 형태에 대한 지원을 약속하지 않는다.

## 전송·접수·완료

프롬프트는 250,000 UTF-16 문자까지 전달한다. 초과 요청은 원문을 줄이지 않고 전송 전 오류와 `knownNotSent: true`로 차단한다. 이미 같은 요청 ID의 영수증이나 클릭 불확실성이 있으면 미전송이라고 다시 단정하지 않는다.

한 페이지에서 활성 요청은 하나다. 제출 후 같은 원문의 새로운 사용자 turn을 확인해야 접수로 인정한다. 클릭 예외·접수 확인 유실·문서 이동은 불확실 상태로 남기며 자동 재전송하지 않는다. 새 사용자 입력은 바로 폰에 보존하고 입력 revision을 바꾼다. 제출 직전 native 승인도 이전 revision이면 거부한다.

접수 이후 새 입력이 오면 각 폰·앱 도구를 시작하기 전에 revision을 다시 확인해 이전 응답의 새 실행을 보류한다. 새 사용자 요청을 background continuation보다 먼저 처리하고, 원 요청의 후속 판단에는 최신 사용자 원문 전체를 넣어 취소·정정·충돌을 다시 판단한다. 같은 원 요청의 기존 영수증도 유지해 이미 실행한 효과를 재생하지 않는다. 이는 **폰·앱 도구와 다음 판단의 조정**이며 이미 시작한 클라우드 작업에 Work의 native steer를 전달했다는 뜻은 아니다. 실제 Work steer 지원은 검증하지 않았다.

대화 화면의 **현재 작업 중지**는 해당 원 요청의 새 폰·앱 실행을 보류한다. 제출 준비 중이면 미전송으로 멈추며, 이미 제출했다면 웹 생성 중지 요청을 보내고 결과 관찰을 계속한다. 중지 버튼 호출만으로 클라우드 프로세스·배경 명령의 정지를 확정하지 않는다. 접수 여부가 불명확한 작업은 여전히 자동 반복하지 않는다. 자율 모드 끄기는 반복 판단 타이머를 끄는 별도 동작이다.

실제 Android 요청은 `requiresNativeAuthorization: true`다. Java에서 첨부를 준비한 뒤 권한·지침·연결 상태를 검사하고, JS에서 업로드 준비가 끝난 **실제 클릭 직전** 다시 `dispatch_authorization_requested`를 native에 보낸다. 요청별 무작위 nonce에 대해 native가 현재 Runtime 정책을 재검사하고 `authorizeDispatch(requestId, nonce, allowed)`로 응답한다. 승인은 한 번만 소비하고 2초가 지나거나 현재 요청/단계가 바뀌면 사용하지 않는다. 거부·시간초과에서는 클릭하지 않고 명시적 미전송 상태를 남긴다. 새로운 사용자 turn이 이미 보이면 미전송이라고 단정하지 않는다. 여기서 미전송은 프롬프트 제출을 뜻하며 이미 진행된 첨부 업로드를 되돌렸다는 뜻이 아니다. 기존 fixture의 생략된 flag는 이 과정을 요구하지 않지만 production Java는 항상 true로 설정한다.

응답 완료는 해당 새 assistant turn의 마지막 답변 뒤에 있는 footer, 생성 중지 상태, 안정된 응답을 함께 확인한다. 먼저 나온 footer, 사용자 turn의 footer, idle 페이지는 완료 증거가 아니다. 서비스의 입력·로그인·승인 대기는 완료를 보류하며 승인 버튼을 자동으로 누르지 않는다. 웹 답변 완료는 Android 작업이나 사용자가 맡긴 전체 목표의 완료와 별개다.

한도 소진은 서비스 배너에서만 감지한다. 답변이 인용한 한도 문구는 소진으로 취급하지 않는다. 소진 상태는 자동으로 해제하지 않으며 사용자가 직접 재시도해도 한도가 실제 회복했다고 단정하지 않는다. 이미 클릭한 불확실 요청을 초기화해 반복하지 않는다.

## 큰 응답과 작업 파일

진행 미리보기는 최대 2,000자이며 `previewOnly`·`captureComplete: false`를 표시한다. 최종 답변을 미리보기로 대체하지 않는다. 큰 답변은 Unicode 경계를 보존한 약 100,000자 조각으로 순서대로 전달한다. 최대 100조각·10,000,000 UTF-16 문자이며, 그 이상은 명시적으로 캡처 불확실 상태가 된다.

Android는 조각을 private 파일에 fsync하며 모으고 순서·개수·길이·SHA-256을 확인한 뒤 원문 작업 파일과 완료 결과를 저장한다. 부분 캡처는 완료로 표시하지 않는다. 이 원문은 관찰한 DOM 텍스트이며 생성 파일 자체와는 다르다.

사진·파일은 실제 composer 파일 입력에 넣고 업로드 준비 증거를 확인한 뒤 프롬프트를 제출한다. 앱은 최대 8개, 파일당 10 MiB·합계 20 MiB를 허용한다. 업로드 입력·활성화·준비를 확인할 수 없으면 첨부 없는 텍스트 요청으로 바꾸지 않는다. 실제 계정 업로드는 아직 검증하지 않았다.

접수 이벤트의 `uploadObserved`, `attachmentNames`, `uploadObservationScope`는 composer가 업로드 완료 상태를 표시한 관찰이다. 서버에 저장된 바이너리의 크기·해시를 별도로 대조했다는 증거가 아니다. 작업 후 그 첨부를 처리한 실제 모델 결과도 별도 확인해야 한다.

HTTPS 다운로드는 허용된 ChatGPT 파일 출처·리다이렉트·실제 서버 응답을 검사한다. 로그인 오류 HTML이나 중간에서 끊긴 파일을 완성된 작업 파일로 게시하지 않는다. `blob:` 파일은 현재 자동 회수하지 못하며 사용자의 수동 가져오기가 필요하다.

## 실제 운영에서 남은 조건

정상 WebView User-Agent와 인증 쿠키를 유지한다. 비밀번호·토큰·웹 저장소를 추출하거나 서비스 인증 보호를 우회하지 않는다. Google OAuth 등 일부 로그인은 embedded WebView를 허용하지 않으며 외부 Chrome 로그인도 WebView 쿠키를 자동으로 공유하지 않는다. 이 경우 연결 미완료로 알린다.

Work에 접수된 클라우드 작업은 화면을 닫아도 계속될 수 있지만 폰의 다음 호출과 완료 관찰은 별개다. Android 프로세스·WebView renderer가 죽거나 임시 대화를 떠나면 저장하지 않은 대화를 채팅 목록에서 복구할 수 없다. 로컬 기억은 이전 클라우드의 실행 결과·브라우저 상태·부작용을 재현하지 못한다. 이 경우 자동 재전송을 중지하고 기존 결과를 확인해야 한다. 기록 저장 모드로 자동 전환하지 않는다.

DOM fixture와 Java 파일/전송 무결성 테스트는 실제 계정 로그인, 실제 모델 호출, 클라우드 도구 실행, 사진 업로드, 파일 회수, Galaxy의 24시간 실행을 증명하지 않는다. 실계정과 실기에서 해당 조건을 각각 검증해야 한다. 앱이 별도의 유료 API·서버·크레딧 구매를 하지 않는 것과 서비스 계정의 기존 과금·한도 설정은 구분한다.

관련 자료: [전체 설계](work-mobile-architecture.md), [Android 권한](android-permissions.md), [Work와 Codex](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex), [임시채팅](https://help.openai.com/en/articles/8914046-temporary-chat-in-chatgpt), [클라우드 브라우저](https://help.openai.com/en/articles/20001280-using-cloud-browser-in-chatgpt), [Google OAuth 정책](https://developers.google.com/identity/protocols/oauth2/policies), [WebView renderer 종료](https://developer.android.com/develop/ui/views/layout/webapps/handle-termination).
