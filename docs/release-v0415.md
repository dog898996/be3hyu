# 호영 0.4.15 — codex-chatgpt-web 비교와 Work 수집 보완

요청한 [miuuyy/codex-chatgpt-web](https://github.com/miuuyy/codex-chatgpt-web/tree/f137c2f84da5df214c1e228d74f5ff43e7641c48)의 현재 커밋을 읽고 호영의 연결·전송·답변 수집 경로와 비교했다. 기존에도 이 프로젝트의 이전 커밋에서 DOM 선택자·대화 ID 대응 방식을 참고했으며, 이번에는 최신 응답 분류와 브라우저 수명 관리까지 확인했다.

## 반영한 변경

1. **전체 리소스 로딩을 기다리던 연결 시작**: 상류는 DOMContentLoaded에 페이지를 사용할 수 있도록 준비한다. 호영도 Android의 `onPageCommitVisible`에서 현재 ChatGPT 문서의 bridge를 한 번 설치한다. 이미지 로딩이 오래 걸려도 실제 UI가 준비되면 검사를 시작하고, 뒤늦은 `onPageFinished`가 이미 진행 중인 연결을 초기화하지 않는다. 모델·로그인·대화 소유 확인은 실제 UI 관찰을 계속 사용한다.
2. **진행 설명과 최종 답변 분리**: 상류의 Activity·streaming-status·cot-v5 경계 처리를 참고해 중간 설명을 최종 답변에 섞거나, 중간 설명의 복사 버튼으로 완료했다고 판단하는 경로를 수정했다. 여러 작업 상태 사이의 실제 답변 부분은 보존한다. Markdown 틀이 없는 일반 글·코드·표·임베드 설명의 수집도 유지한다.
3. **아직 보이지 않는 완료 표시**: opacity가 0인 답변/복사 버튼을 완료 증거로 사용하지 않는다. DOM 변경 없이 CSS만 바뀌어도 다음 관찰에서 반영한다. 별도 탭·화면 밖 배치에 필요한 크기 독립적인 답변 수집은 유지한다.

수정 전 새 Chromium 재현 4개 모두 실패했으며, 수정 후 해당 4개와 추가 Activity 캡처·일반 글 변형까지 6개가 통과했다. 초기 화면의 전체 로딩 전 연결 시작 검사도 수정 전 실패를 확인했다. 코드·출처 고지는 `THIRD_PARTY_NOTICES.txt`, 캡처 출처는 `mobile/tests/fixtures/PROVENANCE.md`에 기록했다.

## 비교했지만 그대로 가져오지 않은 부분

- 상류는 Electron의 영구 브라우저 세션과 여러 작업 탭, Playwright/CDP, 로컬 Codex/MCP 연결을 사용한다. 호영은 Android WebView·폰 제어·자체 기억 DB를 유지한다. Electron 실행기를 Android에 번들하지 않는다.
- 상류의 `/api/auth/session` 서버 조회와 임시채팅 중심 로그인 프로브를 그대로 도입하지 않았다. 호영은 사용자가 선택한 일반 Work 흐름을 유지하며, 이미 보낸 요청의 읽기 전용 답변 복구에는 접힌 계정 메뉴의 표시를 강제하지 않는다.
- 안정적인 대화 ID, 전송 후 새 대화 주소 추적, 원문/해시와 답변 대응, 중복 전송 방지, 교체된 문서 콜백 무효화, 응답 안정화 대기는 이미 구현돼 있다. 이 기능을 새로 만든 것처럼 계산하지 않았다.
- 상류의 숨겨진 데스크톱 탭 크기 보정·전원 차단 방지는 플랫폼 전용이다. Android의 배터리·프로세스 제한이 없어졌다고 간주하지 않는다.

## 검증 범위

관련 JVM 검사 44개가 통과했고 release/debug/Android test APK를 빌드했다. Chromium 전체 Work bridge 회귀와 실제 Android WebView 검사의 최종 수치는 함께 제공하는 검증 기록에 기재한다. 추가 Android 시나리오는 완료 표시의 CSS 변화와, 실제로 지연시킨 이미지 응답이 끝나기 전에 production controller가 연결을 준비하는지를 확인한다.

유료 모델 호출 없이 로컬 fixture와 Android 가상 기기로 검증한다. 실제 사용자 Galaxy(Android 36)·로그인한 최신 ChatGPT 계정 실행을 대신하지 않으며, 이번 Work 한정 검사가 [0.4.13의 다른 기능 검사 제한](release-v0413.md)을 해소했다는 의미도 아니다.
