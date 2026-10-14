# 호영 0.3.1 배포 완료

[GitHub Release](https://github.com/dog898996/be3hyu/releases/tag/v0.3.1)에 [안정 서명 APK](https://raw.githubusercontent.com/dog898996/be3hyu/a69e46c00022b8265d6123b8f5e98c9736d25366/release-assets/v0.3.1/hoyoung-0.3.1.apk), [소스 ZIP](https://raw.githubusercontent.com/dog898996/be3hyu/a69e46c00022b8265d6123b8f5e98c9736d25366/release-assets/v0.3.1/hoyoung-0.3.1-source.zip), [체크섬](https://raw.githubusercontent.com/dog898996/be3hyu/a69e46c00022b8265d6123b8f5e98c9736d25366/release-assets/v0.3.1/hoyoung-0.3.1-SHA256SUMS.txt)을 공개했다. 네 공개 파일을 다시 다운로드하여 빌드한 로컬 파일과 동일한 크기·SHA-256임을 확인했다. [공개 다운로드 영수증](benchmarks/hoyoung-v031-public-delivery.json)을 보존한다.

APK 검증 코드 `1606ca6871056b223a749ec2c5d01218ae71e58c`, 문서 포함 소스 ZIP·태그 `232dbad28e51106e964c458c53a8b69be40d9177`, 배포 파일 커밋 `a69e46c00022b8265d6123b8f5e98c9736d25366`를 구분한다. 검증 대상 소스·설정 191개는 문서 추가 커밋에서도 모두 같았다. API 업로드 호스트 인증 문제로 바이너리는 별도 Git 브랜치의 고정 원본 URL로 제공하며 main에는 배포 바이너리를 넣지 않았다. 서명 개인키·실사용 DB·브라우저 쿠키는 공개하지 않았다.

## 적용과 사용

0.3.0 release와 같은 인증서의 versionCode5이므로 앱 데이터와 기억을 유지하며 업데이트한다. debug 앱처럼 다른 서명에서 바꾸는 경우는 기존 설치 안내와 백업 절차를 따른다. 권한40개·모델 설정·기억 검색 구조와 예산은 유지했다.

파일 탭에서 **보존 폴더 선택·다시 연결**로 `Documents/호영` 같은 공유 폴더를 선택한다. 작은 삭제·제외 정책은 제어 변경 시 별도 IO 작업에서 자동 보존한다. 외부 보존 확인 표시 전에 앱 데이터를 지우면 아직 외부에 반영되지 않은 최신 정책을 잃을 수 있다. 전체 대화·정정 이력·첨부는 **보존 폴더에 대화·기억·파일 백업 저장**으로 직접 저장한다. 전체 데이터의 자동 주기 백업은 제공하지 않는다.

재설치·데이터 삭제 뒤 같은 폴더를 먼저 연결하고 ZIP을 복원한다. 최신 정책을 먼저 합치므로 오래된 ZIP이 삭제·제외 결정을 되돌리지 못한다. 기억의 일반 복구 범위는 선택한 ZIP 시점까지이며 이후 대화가 자동 외부 복사되었다고 표시하지 않는다. [구조·범위·시험](reinstallation-memory-preservation.md)을 따른다.

## 검증

최종 전체 unit787개 중770개 통과·선택형17개 제외·실패와 오류0개, debug/release lint 오류0개·경고16개. 새 저장·보존25개와 추가 runtime3개는 재설치 모의·옛 백업·세대 경합·변조·부분 저장·원문 보존과 삭제·작업 중 변경 거절·추가 모델 전송0개·단일 삭제 pass를 확인했다.

[실제 API35 KVM CI](https://github.com/dog898996/be3hyu/actions/runs/38507360114)는17개의 실제 OS 기본 검사와 강제 중지 후 명시적 재열기, SIGKILL·실제 Java 오류 자동 복구, 실제 재부팅 복구를 통과했다. 새 두 검사는 TEST APK의 별도 UID provider를 이용한 실제 SAF 영속 tree 권한·IO·rename·최신 정책+옛 ZIP 및 손상 문서 차단을 확인했다. Namespace 초기화는 격리된 시험 영역의 초기화이며 실제 OS 앱 제거를 수행했다고 주장하지 않는다. [로컬 기록](benchmarks/android-final-v031-local.json)과 [실제 Android 로그 증거](benchmarks/android-final-v031-native.json)를 보존한다.

실제 S24의 picker·응답속도·24시간 연속 실행, 실제 OS 제거·재설치와 실계정 Work 작업은 미측정이다. 추가 모델 호출과 정상 검색 경로의 provider IO를 넣지 않았지만 실제 폰의 성능 영향이 정확히0ms라고 보장하지 않는다. 이전5년 답변 채점을 이번 코드에서 새로 실행했다는 주장도 하지 않는다. 추가 유료 API·호스팅·CI는 사용하지 않았다.
