# 호영 0.4.11 — 불필요한 백업 용량 입력 제거

설정에 노출한 `64`는 한 번의 백업·복원에서 허용할 최대 GiB를 직접 낮추는 입력이었다. 일반 사용에서 별도 조정할 필요가 없어 숫자 입력칸과 관련 설명·설정 저장 검증을 제거했다. 백업·복원 진입 버튼과 실제 여유 공간 확인은 유지한다.

백업 엔진, 기존 용량 정책, 데이터·장기기억 처리, Work 연결 코드는 변경하지 않았다. 0.4.10의 답변 회수·재시도 개선을 포함한다.

기존 설정 UI 검사 **8/8**, 서명 릴리스 빌드·필수 lint·APK 신원·서명·권한·패키징 검사를 통과했다. UI 변경에 관련 없는 전체 시뮬레이션과 브라우저 검사는 반복하지 않았다.

[UI 검사 기록](benchmarks/android-v0411-focused.json) · [APK 검사 기록](benchmarks/android-v0411-apk.json) · [설치 파일](https://github.com/dog898996/be3hyu/releases/tag/v0.4.11)

[APK 직접 다운로드](https://raw.githubusercontent.com/dog898996/be3hyu/cd2458a9a0282cc2007de4720d4f55a6ed70ebb0/release-assets/v0.4.11/hoyoung-v0.4.11.apk) · [공개 다운로드 검사](benchmarks/android-v0411-downloads.json). APK·소스·검증 JSON·체크섬을 인증 없이 내려받아 크기와 SHA-256 일치를 확인했다. 소스 커밋: `7be15b42b96be568c92cf244e56de7f115cf6dd0`.
