# 호영 0.3.0 배포 완료 기록

2026-10-14 02:28 UTC에 [GitHub Release](https://github.com/dog898996/be3hyu/releases/tag/v0.3.0)를 공개했다. [APK](https://raw.githubusercontent.com/dog898996/be3hyu/5872dc8df5d4c75994baffcce44e309852bbb8b8/release-assets/v0.3.0/hoyoung-0.3.0.apk), [문서 포함 소스 ZIP](https://raw.githubusercontent.com/dog898996/be3hyu/5872dc8df5d4c75994baffcce44e309852bbb8b8/release-assets/v0.3.0/hoyoung-0.3.0-source.zip), [SHA-256 목록](https://raw.githubusercontent.com/dog898996/be3hyu/5872dc8df5d4c75994baffcce44e309852bbb8b8/release-assets/v0.3.0/hoyoung-0.3.0-SHA256SUMS.txt)을 실제로 다시 내려받아 네 파일 모두 검증된 로컬 파일과 같은 크기·SHA-256임을 확인했다. [최종 공개 다운로드 영수증](benchmarks/hoyoung-v030-public-delivery.json)에 결과를 보존한다.

GitHub의 배포 자산 업로드 전용 호스트는 이 개발 환경의 인증을 HTTP 401로 거부했다. 정상 인증되는 Git 저장소 경로로 별도 `release-assets/v0.3.0` 브랜치에 파일을 저장했고, Release는 변경되지 않는 커밋 `5872dc8df5d4c75994baffcce44e309852bbb8b8`에 고정된 다운로드를 제공한다. main의 앱 코드에는 배포 바이너리를 추가하지 않았다. 추가 유료 API·호스팅·CI는 사용하지 않았다.

## 코드와 시험의 연결

- APK 검증 코드: `ad854064da708d656924c5eff9bbb349b96ab574`.
- Release 태그와 소스 ZIP: `ccd222a5f567b902b2076087624e5eedc49ea5bb`. 추가된 최종 문서 외 검증 코드·설정 205개 파일은 APK의 캡처와 모두 같다.
- APK: versionName `0.3.0`, versionCode `4`, package `com.hoyoung.assistant`, 요청 권한 40개, 안정 서명, debuggable false.
- APK SHA-256: `ecb6eb3224d770d86d94e7db7eaa487760fcfa40744a54c88fb8650742843d10`.
- 다운로드의 verification JSON은 준비 시점의 스냅샷이다. 공개 완료 여부와 다운로드 대조는 이 최종 영수증을 따른다.

## 확인한 결과

[5년 모의 답변 시험](five-year-memory-final-answer-results.md)은 서로 다른 144개 질문을 원문 방식·writer 방식에서 모두 통과했다. 정정·삭제·권한 철회와 캐시·옛 백업 재생을 포함해 최신 시험의 보호 값 재노출은 0건이었다. writer 답변은 입력과 실제 후속 조회를 대조한 142개 이월 답변 및 새 답변 2개다. 같은 조회 예산의 기준선도 144개를 통과해 답변 정확도 2배 향상은 입증하지 못했다.

[로컬 Android unit 검사](benchmarks/android-final-v030-local.json)는 759개 중 743개 통과·선택형 16개 제외·실패와 오류 0개, lint 오류 0개·경고 16개다. Chromium 브릿지 검사 45개도 통과했다. [실제 API 35 OS CI](https://github.com/dog898996/be3hyu/actions/runs/38499375034)는 기본 15개와 강제 중지 후 명시적 재열기, SIGKILL·Java 오류의 자동 복구, 실제 재부팅의 BootReceiver 복구를 통과했다. 강제 중지 자체를 자동 복구했다고 주장하지 않는다. 원격 unit 검사에서는 선택형 제외가 17개였다.

실제 갤럭시 S24 Ultra의 24시간 연속 실행, 실계정 로그인된 ChatGPT Work의 작업 성공과 실제 클라우드 파일 분석은 아직 검증하지 않았다. 합성 5년 시험은 모든 미래 질문의 무오류 보장이나 Dot보다 뛰어난 실제 성능의 증명이 아니다. [설치·계정 연결과 Android 권한 안내](../mobile/README.md)를 따른다.
