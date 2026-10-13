# 호영이 0.1.1 배포 파일

- [Android release APK](hoyoung-0.1.1.apk): 고유 H 아이콘, 기존 요청 권한 37개 유지, 디버깅 비활성화.
- [검증 기록](hoyoung-verification.json), [APK SHA256](hoyoung-0.1.1.apk.sha256), [아이콘 미리보기](hoyoung-icon-preview.png).
- [설치·삼성 자동 차단 설정·연결 절차](../mobile/README.md).

Android 테스트 59개와 Node·브라우저 테스트 219개가 통과했다. 실제 ChatGPT 계정의 Work 도구 실행, 실폰 설치·24시간 실행과 작업 중 steer는 아직 검증하지 않았다.

삼성의 ‘자동 차단 기능으로 설치 차단’은 출처 불명 설치 허용과 별개다. 본인이 설치하려는 APK를 확인한 뒤 보안 위험 자동 차단을 잠시 끄고 설치 후 다시 켠다. 앱 아이콘과 release 서명은 OS 차단을 해제하지 않는다.

Release 개인키와 비밀번호는 공개 저장소에 없다. 공개 인증서만 `mobile/signing`에 보관한다.
