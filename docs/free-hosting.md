# 비용 0원 상시 운영 후보 검토

검토일: 2026-10-12. Oracle 가입·본인 인증·공식 API 인증은 완료했다. 무료 범위의 실제 VM 생성 요청은 용량 부족으로 거절됐다. 설치 소스용 비공개 버킷과 기본 네트워크를 준비했으며, 확보된 VM은 아직 없다. 결제 계정을 유료로 전환하지 않았다.

## 현재 판단

**OCI Always Free Ampere A1이 전체 기능을 유지할 수 있는 가장 유력한 후보다.** 무료 재고와 본인 인증을 통과해야 하며 상시 가용성까지 보장하지 않는다. 사용자가 이미 계속 켜두는 PC가 있다면 동일한 Docker 구성으로 서버 대여료 없이 운영할 수 있다. 이 경우 전기·인터넷과 PC 유지 비용은 기존 환경에 의존한다.

GCP e2-micro에는 실제 월 전체 무료 VM 시간이 있지만 기본 인터넷 IPv4는 별도 과금되고, 계속 운영하려면 결제 계정이 필요한다. 유료 계정 전환을 포함하지 않는 현재 요구의 기본 배포 대상으로 쓰지 않는다. 무료 체험 크레딧이 끝나면 사라지는 서버를 영구 무료 서버로 소개하지 않는다.

| 후보 | 확인된 자원·조건 | 이 앱의 적합성 |
|---|---|---|
| OCI A1 Always Free | 계정 합계 ARM 2 OCPU·RAM 12GB, 월 1,500 OCPU-hour·9,000 GB-hour. 부팅·블록 볼륨 합계 200GB, 볼륨 백업 5개. 홈 리전에서만 무료 | Node·Python·Chromium·영구 설치 디렉터리를 수용할 후보. 재고·본인 인증·회수 정책 조건 |
| OCI AMD Micro | 최대 2개, 각각 RAM 1GB·1/8 OCPU 기반 | 가벼운 서버에 적합. 개발·여러 실제 브라우저 작업의 여유가 작음 |
| GCP e2-micro | RAM 1GB·지속 CPU 0.25 vCPU, 제한된 미국 3개 리전, 30GB-month pd-standard | 가벼운 프로세스는 가능하나 기본 IPv4와 결제 계정 조건이 비용 0원 요구와 충돌 |
| 기존 사용자 PC | 실제 PC 자원·전원·네트워크에 의존 | 앱 전체 기능 가능. PC 종료 시 작업도 종료 |

OCI 수치는 [현재 Always Free 공식 자원 표](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)를 기준으로 한다. GCP 수치는 [무료 혜택](https://docs.cloud.google.com/free/docs/free-cloud-features)과 [E2 사양](https://docs.cloud.google.com/compute/docs/general-purpose-machines)을 기준으로 한다.

## OCI에서 확인한 제약

가입 화면에 서울(`South Korea Central / ap-seoul-1`)이 제공되면 한국 운영의 우선 후보로 삼는다. 현행 Always Free 문서는 **춘천(`South Korea North / ap-chuncheon-1`)의 A1 생성을 제외**한다. 홈 리전은 가입 후 변경할 수 없고 무료 VM은 홈 리전에서만 만들 수 있다. 가입 화면에 서울이 없으면 실제 선택지를 확인하며, 어느 지역도 A1 잔여 용량을 보장하지 않는다. [공식 A1 무료 조건](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), [홈 리전 가입 안내](https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm), [공식 리전 목록](https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm).

무료 계정을 만들 때 일반적으로 휴대전화와 신용카드 또는 지원되는 직불카드의 본인 인증이 필요하다. 카드에 임시 승인 보류가 생길 수 있으며 FAQ는 보통 3~5일 뒤 해제된다고 설명한다. 가상·일회용·선불카드는 지원하지 않는다. **카드 없이 가입할 수 있다고 확인하지 않았다.** 유료 계정으로 전환하지 않고 Always Free 표시 자원만 사용해야 한다. [공식 Free Tier 설명](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm), [가입·카드·SLA FAQ](https://www.oracle.com/cloud/free/faq/).

재고가 없으면 `out of host capacity`로 VM 생성이 실패할 수 있다. 무료 계정은 가용성 SLA가 없고, 7일 동안 CPU 95백분위·네트워크 사용률·A1 메모리 사용률이 모두 20% 미만이면 유휴 VM을 회수할 수 있다. 단순히 앱 데몬이 켜져 있다는 사실이 회수 방지를 보장하지 않는다. 한달 이상 방치된 계정도 중단 대상일 수 있다. 인위적인 부하나 사용량 조작으로 회수 정책을 회피하는 구성을 제공하지 않는다.

블록 볼륨은 부팅 디스크까지 합산한다. 새 A1 VM 하나에 50GB 부팅 디스크와 최대 150GB 작업 디스크를 배분하는 조합이 가능하지만 합계 무료 한도를 넘기면 안 된다. Object Storage의 별도 무료 혜택은 총 20GB와 월 50,000 요청이다. 저장한 작업과 설치 패키지는 이 한도 안에서 관리해야 한다. 인터넷 송신은 월 첫 10TB가 무료라는 공식 네트워크 가격도 확인했다. [네트워크 가격](https://www.oracle.com/cloud/networking/virtual-cloud-network/pricing/).

오래된 4 OCPU·24GB 가이드를 그대로 적용하지 않는다. [ARM 개요 문서](https://docs.oracle.com/en-us/iaas/Content/Compute/References/arm.htm)는 아직 3,000 OCPU-hour·18,000 GB-hour를 적고 있어 현재 Free Tier 문서와 충돌한다. 최신 무료 자원 표와 2026-06-29 Free Tier 안내의 2 OCPU·12GB를 적용한다. 월 한도 초과 A1은 체험 종료 뒤 비활성화·삭제될 수 있으므로 체험 크레딧으로 큰 VM을 만드는 방식을 선택하지 않는다.

실제 홈 리전 오사카의 Compute Capacity Report에서 A1과 무료 AMD 구성을 조회했고, 현재 모두 `OUT_OF_HOST_CAPACITY`였다. A1 1 OCPU·6GB·부팅 100GB의 실제 생성 요청도 같은 이유로 거절됐다. 이는 현재 생성 가능성을 확인한 결과이며 지역 전체의 물리적 재고 수량을 제공하지 않는다. 2026-08 [Oracle 사용자 포럼의 Phoenix 생성 실패 사례](https://community.oracle.com/CustomerConnect/discussion/974626/unable-to-recreate-an-always-free-a1-instance-with-a-reduced-2-ocpu-12-gb-configuration)와 2026-09 [신규 계정에서 즉시 생성됐다는 사용자 사례](https://www.reddit.com/r/oraclecloud/comments/1wf4qph/is_it_a_coincidence_or_am_i_just_lucky/)도 검토했다. 개인 보고라 전체 성공률로 일반화하지 않는다.

## 빈자리 확보를 위한 실제 생성 재시도

사용자가 제공한 [API 자동 확보 사례](https://snapcode.tistory.com/entry/OCI-무료-서버-잡기-API-스크립트로-A1Flex-인스턴스-자동-선점하기)를 검토해 재고 조회 뒤 중단하던 절차를 실제 생성 요청의 반복으로 바꿨다. 사례의 Bash 코드를 복사하지 않고 공식 OCI Python SDK를 사용한다. 블로그의 과거 4 OCPU·24GB 설명보다 현행 Always Free 공식 표의 계정 합계 2 OCPU·12GB를 우선한다.

이번 목표는 오사카 A1 **1 OCPU·6GB·부팅 100GB·VPU/GB 10** 한 대다. 약 5분 간격에 짧은 지터를 추가하고, 요청 제한은 더 길게 대기한다. 한 대가 수락되면 재시도를 끝낸다. 응답이 불명확하면 동일한 요청 본문·재시도 토큰과 실제 인스턴스 목록을 대조하며, 새 요청 의도를 임의로 만들지 않는다. 모양·디스크·다른 자원까지 무료 한도를 확인한 뒤 요청한다.

개발 환경 종료와 별개로 실행하기 위해 무료 Resource Manager 작업을 사용한다. 공식 [Always Free 자원 표](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)는 동시 작업 2개와 작업당 최대 24시간을 설명한다. 실제 Resource Manager 작업에서 Docker와 Node 24 실행을 확인했다. 재시도 창은 준비 시간을 포함해 23시간이며, 무기한 자동 갱신은 구현하지 않았다. 계속 용량이 없으면 창이 끝날 때 중단한다.

2026-10-12 08:28 UTC, 메인 작업에서 실제 SDK 인증·비공개 체크포인트 저장·전체 두 번째 생성 요청을 확인했다. 응답은 용량 부족이며 `waiting_capacity` 상태로 다음 요청을 대기했다. 이번 창의 종료는 2026-10-13 07:20 UTC(한국시간 16:20)다. 체크포인트는 약 14KB, 설치 소스 객체는 약 193KB로 확인했다. VM을 확보하거나 설치를 완료했다는 뜻은 아니다.

인증은 소유자 전용 Resource Manager ZIP의 구성된 공식 API 키로만 수행한다. API 키는 설치 소스나 VM에 전송하지 않는다. 생성 의도·고정된 요청 본문·앱 복구 자료는 별도 비공개 Object Storage 체크포인트에 보관한다. 원격 잠금과 조건부 저장을 사용하고, 저장 실패나 기존 체크포인트가 있으면 시작을 중단한다. 재개 시 기존 작업의 종료를 확인하고 최신 인증된 체크포인트를 복원해야 한다. 소스·체크포인트의 합계 저장량과 요청 수도 무료 범위 안에서 관리한다.

## ARM 실행 경로

Dockerfile은 `node:24-bookworm-slim`에서 Python·Git·Chromium을 설치하며 CPU 아키텍처를 x64로 고정하지 않는다. A1의 Ubuntu ARM64 호스트에서 직접 `docker compose up -d --build`하면 ARM 이미지를 만든다. [Playwright 공식 지원 OS](https://playwright.dev/docs/intro#system-requirements)는 Debian 12와 Ubuntu의 arm64를 포함한다. [Codex 공식 배포 소스](https://github.com/openai/codex/blob/main/codex-cli/scripts/build_npm_package.py)는 Linux arm64 패키지를 제공하며, 고정 버전 0.159.2의 npm 패키지 메타데이터도 실제 확인했다.

현재 검증한 실제 이미지는 x64다. ARM 빌드나 OCI VM에서 실행했다고 주장하지 않는다. Python/Node 일반 패키지에도 일부 x64 전용 바이너리가 있을 수 있으므로 추가하는 프레임워크별 지원을 확인해야 한다.

외부 계정 확보 뒤에는 [deployment.md](deployment.md)의 일반 Compose 구성을 사용한다. `/data` 볼륨에 기억·설정·일정·프로필·pip/npm 설치를 유지한다. SSH 접근 가능한 VM이면 로컬 웹 접속은 `ssh -L 8080:127.0.0.1:8080 ubuntu@<VM_IP>`로 전달할 수 있다. 새 도메인 구입이나 유료 HTTPS 서비스가 필수 조건은 아니다. 서버 자체 회수나 디스크 삭제에 대비한 별도 백업은 필요하다.

## GCP에서 추가로 확인한 비용

GCP 무료 VM 시간은 `us-west1`, `us-central1`, `us-east1`의 비선점 e2-micro 한 대분을 합산한다. 부팅 디스크도 pd-standard를 선택해야 한다. 외부 IPv4는 월 1시간만 무료이고 이후 시간당 $0.005여서 평균 월 730시간이면 IP만 약 $3.645다. IPv6 주소는 무료지만 IPv4 웹사이트 연결을 Cloud NAT/NAT64로 보완하면 별도 과금 경로가 생긴다. [네트워크 가격](https://cloud.google.com/vpc/network-pricing), [IPv6→IPv4 연결](https://docs.cloud.google.com/vpc/docs/ipv6-to-ipv4-overview).

무료 체험은 90일/$300이고, 계속 사용하는 Always Free에도 유효한 Billing 계정이 필요하다. 결제 검증 시 $0~$1 임시 승인 보류가 생길 수 있다. 무료 혜택 밖 사용은 결제 계정에 청구된다. 2026년 새 Spend Cap은 일부 API·Cloud Run 등에 제공되지만 Compute Engine·VPC에는 적용되지 않는다. VM 운영의 완전한 0원 차단 장치로 취급하지 않는다. [가입 FAQ](https://cloud.google.com/signup-faqs), [현재 Spend Cap 범위](https://docs.cloud.google.com/billing/docs/how-to/budgets-spend-caps).

메모리는 실제 작업에 따라 변한다. 로컬 x64 테스트 컨테이너에서 서버만 대기할 때 약 97MiB, 서로 격리된 Chromium 프로필 세 개가 최소 예제 페이지를 열었을 때 총 약 342MiB를 측정했다. 실제 로그인된 ChatGPT·Instagram, 개발 빌드, 설치 작업을 함께 돌린 측정은 아니다. 따라서 1GB에서 시작 자체가 불가능하다는 주장은 하지 않지만 사용자 요청의 넓은 작업과 오래 쓸수록 늘어나는 데이터에는 여유가 작다는 판단이다.
