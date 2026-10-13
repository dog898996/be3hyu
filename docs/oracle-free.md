# OCI Always Free에서 호영이 상시 운영

이 문서는 이미 확보한 Oracle Cloud VM에 호영이를 설치하는 절차다. VM을 만들거나 결제 계정을 전환하는 스크립트는 포함하지 않는다. 준비된 배포 파일은 [install.sh](../deploy/oracle/install.sh), [compose.yaml](../deploy/oracle/compose.yaml), [Caddyfile.template](../deploy/oracle/Caddyfile.template)이다. 무료 정책과 재고·계정 인증·회수 조건은 [free-hosting.md](free-hosting.md)를 먼저 확인한다.

**현재 검증 범위:** 일반 x64 Docker 실행과 데이터 보존은 검증했다. OCI VM 확보, 실제 ARM64 이미지 빌드·실행, 외부 HTTPS 발급, 사용자 ChatGPT 로그인 뒤 모델 응답까지 완료했다는 뜻은 아니다. 이 절차로 설치한 운영 서버에서 아래 확인을 끝내야 한다.

## 1. 무료 VM과 네트워크 준비

가입 화면에 `South Korea Central (Seoul)`이 제공되면 한국 운영의 우선 후보로 삼는다. 현행 공식 Always Free 문서는 A1 생성에서 **South Korea North (Chuncheon)을 제외**하므로 춘천을 선택하지 않는다. 홈 리전은 가입 후 변경할 수 없다. 서울을 포함한 어떤 리전도 현재 A1 재고를 인증 없이 확인하거나 보장할 수 없다. 서울이 가입 선택지에 없으면 실제 표시되는 다른 후보를 확인한다. [공식 가입 조건](https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm), [현재 A1 무료 조건](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm).

사용자의 실제 가입 화면에는 도쿄(`Japan East / ap-tokyo-1`)와 오사카(`Japan Central / ap-osaka-1`)가 제공된다. 둘 다 현행 A1 무료 조건의 제외 지역이 아니다. 한국에서 사용하는 경우 지리적 근접성을 기준으로 오사카를 기본 후보로 추천했으며, 지연시간이나 A1 재고 우위를 측정한 결과는 아니다. 실제 홈 리전은 사용자의 선택을 확인한다. [공식 리전 목록](https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm).

기존 OCI Free Tier 계정의 **홈 리전**에서 `Always Free eligible`로 표시되는 `VM.Standard.A1.Flex`를 사용한다. 운영체제는 Ubuntu 24.04 ARM64, 시작 구성은 **2 OCPU·RAM 6GB·부팅 디스크 100GB**다. 이 VM은 게스트 ARM CPU 2개를 제공한다. RAM은 필요하면 12GB까지 배분할 수 있지만, 같은 계정의 다른 A1 인스턴스까지 합산해 현재 무료 한도 **2 OCPU·12GB**를 넘기지 않는다. 부팅·블록 볼륨도 계정 합계 **200GB 이하**여야 한다. 기존 자원이 있으면 새 VM 크기를 그만큼 줄인다. [공식 무료 자원 표](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm).

체험 크레딧으로 큰 VM을 만드는 방식이나 Pay As You Go 전환을 사용하지 않는다. A1 재고가 없으면 다른 유료 모양으로 바꾸지 않고 중단한다. 무료 VM은 재고 부족과 유휴 자원 회수 조건이 있으므로 24시간 실행 구조를 설치해도 플랫폼의 상시 가용성을 보장하지 않는다.

네트워크는 공용 서브넷, VM 공용 IPv4, **Internet Gateway**와 인터넷 기본 경로를 사용한다. 이 배포에는 NAT Gateway나 Load Balancer가 필요하지 않다. 별도 유료 DNS 존도 만들지 않는다. OCI의 해당 무료 조건과 계정 자원 사용량은 콘솔에서 확인한다.

OCI Security List 또는 NSG와 VM 안의 방화벽에서 다음 접속이 가능해야 한다.

| 포트 | 용도 | 설정 |
|---|---|---|
| TCP 22 | SSH 관리 | 기존 허용 규칙을 유지한다. 가능하면 관리자의 실제 접속 주소로 제한한다 |
| TCP 80 | 인증서 발급·HTTP→HTTPS 이동 | 인터넷에서 VM으로 허용 |
| TCP 443 | 관리 UI HTTPS | 인터넷에서 VM으로 허용 |

외부로는 패키지 다운로드, HTTPS 모델 접속, DNS 조회가 가능해야 한다. 설치 스크립트는 OCI 규칙이나 호스트 방화벽을 자동으로 바꾸지 않는다. 기존 SSH 규칙을 삭제하거나 `iptables -F`로 방화벽을 비우지 않는다. 현재 호스트가 UFW를 사용한다면 기존 SSH 규칙을 확인한 뒤 80·443만 추가한다. OCI 기본 이미지의 다른 방화벽 관리 방식을 UFW로 임의 교체하지 않는다.

## 2. 주소 선택과 소스 준비

이미 소유한 도메인이 있으면 A 레코드를 VM 공용 IPv4로 지정한다. 도메인을 사지 않고 시작하려면 `hoyoung.PUBLIC_IP.sslip.io` 형식을 사용할 수 있다. 예를 들어 VM IP가 `203.0.113.10`이라면 이름은 `hoyoung.203.0.113.10.sslip.io`다. 예제 IP는 실제 서버 주소로 바꾼다.

sslip.io는 IP를 이름에서 읽는 외부 무료 DNS 서비스다. 호영이 배포가 그 서비스의 가용성을 보장하지 않으며, VM의 IP가 바뀌면 이름도 바꿔야 한다. 공용 DNS에서 이름이 올바른 VM IP로 해석되고 80·443이 실제 도달해야 Caddy가 HTTPS 인증서를 발급할 수 있다.

VM에 이 저장소의 소스 체크아웃을 먼저 준비한다. 아래 예시는 `/home/ubuntu/autonomous-assistant`에 소스가 이미 있다는 전제다. 개발 환경의 `.env`, `.data`, `/data`, 로그인 프로필이나 기존 Codex 인증 파일을 소스와 함께 옮기지 않는다. VM에서 사용자 계정으로 새 로그인한다.

```sh
ssh ubuntu@PUBLIC_IP
cd /home/ubuntu/autonomous-assistant
test -f Dockerfile
test -f deploy/oracle/install.sh
uname -m
```

A1 호스트의 `uname -m`은 `aarch64`여야 한다. 설치 시 Docker가 이 호스트에서 직접 이미지를 빌드하므로 x64 이미지 전송이나 에뮬레이션을 전제로 하지 않는다.

## 3. 설치

명시적으로 이름과 소스 경로를 지정한다. 이미 소유한 도메인을 사용하면 `--hostname` 값만 그 이름으로 바꾼다. `--email`은 인증서 계정용 선택 사항이며, 추가하지 않아도 설치할 수 있다.

```sh
sudo bash deploy/oracle/install.sh \
  --hostname hoyoung.PUBLIC_IP.sslip.io \
  --source /home/ubuntu/autonomous-assistant \
  --install-dir /opt/hoyoung
```

```sh
# 인증서 계정 이메일을 함께 지정하는 예시
sudo bash deploy/oracle/install.sh \
  --hostname assistant.example.com \
  --email owner@example.com \
  --source /home/ubuntu/autonomous-assistant \
  --install-dir /opt/hoyoung
```

스크립트는 root 권한이 필요하다. `--source`를 생략하면 설치 스크립트가 포함된 저장소 위치, `--install-dir`를 생략하면 `/opt/hoyoung`을 사용한다. `--hostname`을 생략하면 VM 공용 IPv4를 감지해 `hoyoung.공용IPv4.sslip.io`를 사용한다. 자동 감지가 맞지 않으면 `--public-ip 실제공용IPv4`로 지정하거나 `--hostname`을 직접 입력한다. `--prompt-token`은 소유자가 비공개 터미널에서 새 관리 토큰을 직접 지정할 때 사용하는 선택 사항이다. 기본은 첫 설치 시 새 토큰 생성, 재설치 시 기존 토큰 유지다. Ubuntu용 **Docker 공식 apt 저장소**에서 Docker Engine과 Compose 플러그인을 설치하고, 호스트 부팅 시 Docker를 실행하도록 설정한 뒤 소스를 로컬 이미지로 빌드한다. 설치 전 `--help`로 현재 인자를 확인할 수 있다. 대상 VM에서 변경 없이 OS·CPU·메모리·소스 경로를 확인하려면 `sudo bash deploy/oracle/install.sh --check --source /home/ubuntu/autonomous-assistant`를 실행한다. 계정 전체 무료 한도 확인은 콘솔에서 별도로 수행한다.

설치 전에 실행 모듈·UI·두 초기 대화 자료가 모두 있는지 확인한다. 서비스 시작 뒤에는 로컬 `/api/health`를 최대 120초 동안 확인하고, HTTP 200·`ok: true`·`service: "autonomous-assistant"`가 함께 확인돼야 완료를 출력한다. 실패하면 설치 스크립트도 실패로 끝난다. 이 확인은 앱 시작 상태를 뜻하며, 공용 HTTPS·모델 로그인·DM 연결은 아래 절차대로 따로 확인한다.

기본 설치 위치는 다음과 같다.

| 위치 | 내용 |
|---|---|
| `/opt/hoyoung/source` | 빌드에 사용하는 소스 복사본 |
| `/opt/hoyoung/compose.yaml` | 운영 Compose 설정 |
| `/opt/hoyoung/Caddyfile` | 이름을 반영한 HTTPS 프록시 설정 |
| `/opt/hoyoung/.env` | 관리 토큰과 선택한 환경 설정, 권한 0600 |
| `/opt/hoyoung/.install-status.json` | 설치·로컬 서버 준비 확인 상태, 권한 0600 |
| `/opt/hoyoung/data` | 앱 `/data`의 호스트 저장 위치 |
| `/opt/hoyoung/caddy-data`, `/opt/hoyoung/caddy-config` | Caddy 인증서·계정·설정 저장 위치 |

앱의 `/data`를 `/opt/hoyoung/data`에 연결하므로 컨테이너 교체 뒤에도 기억·설정·일정·브라우저 프로필·작업 파일을 유지한다. Caddy의 인증서와 계정 데이터도 호스트에 영구 저장한다. 앱과 Caddy는 `restart: unless-stopped`로 실행하며 `hoyoung-assistant.service`도 호스트 부팅 때 Compose를 시작한다. 서버 전원과 Docker 서비스가 살아 있어야 데몬이 동작한다.

관리 토큰은 설치 시 생성해 `/opt/hoyoung/.env`의 `APP_AUTH_TOKEN`으로 저장한다. 이 파일 권한은 **0600**이며 설치 로그에 토큰을 출력하지 않는다. 관리 UI의 소유자 연결 화면에서 이 토큰을 입력한다. 토큰은 비공개 SSH 세션에서 소유자가 `.env`를 열어 확인하고, 채팅·스크린샷·배포 로그에 복사하지 않는다. `docker compose config`의 전체 출력을 공유하면 환경값이 노출될 수 있으므로 검증에는 `config --quiet`를 사용한다.

설치 디렉터리는 root 전용 권한 0700이다. **아래 로그인·운영·백업·중지 명령은 SSH에서 `sudo -i`로 root 관리 셸에 들어간 뒤 실행한다.** 일반 `ubuntu` 계정은 먼저 `cd /opt/hoyoung`을 할 수 없다. 작업을 마치면 `exit`으로 root 셸을 닫는다.

```sh
sudo -i
cd /opt/hoyoung
```

## 4. ChatGPT 계정 새 로그인

무료 VM은 모델 이용 권한을 만들어 주지 않는다. 기본 모델 연결은 설치된 **Codex CLI와 사용자의 ChatGPT 계정 로그인**이다. OpenAI API 키나 API 과금 공급자를 넣지 않는다. 기존 Pro 계정의 인증 파일을 개발 환경에서 추출하거나 복사하지 않고, 이 서버에서 정상 로그인 절차를 시작한다.

관리 UI에서 토큰으로 연결한 뒤 연결 설정의 **ChatGPT 구독 계정 연결** 버튼을 누른다. 표시된 공식 OpenAI 로그인 주소를 사용자 브라우저에서 열고 기기 코드를 입력한다. 비밀번호는 OpenAI 페이지에서 입력하며, 연결 화면에서 실제 완료 상태를 확인한다. SSH에서 같은 절차를 진행하려면 아래 CLI를 사용한다.

```sh
cd /opt/hoyoung
sudo docker compose exec assistant codex login --device-auth
```

CLI가 표시하는 공식 인증 페이지를 사용자 브라우저에서 열고 기기 코드를 입력해 로그인한다. 코드는 임시 로그인 정보이므로 공개하지 않는다. 완료 후 다음 명령으로 상태를 확인한다.

```sh
sudo docker compose exec assistant codex login status
```

`Logged in using ChatGPT` 상태를 확인한다. 계정이나 조직이 기기 인증을 비활성화한 경우 표시되는 안내에 따라 지원되는 로그인 방식을 사용하며, API 키를 대신 넣어 과금 경로로 바꾸지 않는다.

이미지의 `PERSIST_CODEX_AUTH=true` 설정은 새 컨테이너에서 `/root/.codex`가 없을 때 `/data/codex`로 연결해 새 인증과 설정을 보존한다. 기존 Codex 홈이 있으면 빈 디렉터리를 포함해 그대로 유지한다. 기존 홈을 쓰는 별도 설치에서는 실제 인증 저장 위치가 영구 디스크인지 따로 확인해야 한다. 실제 모델과 추론 강도·속도 설정은 계정의 모델 목록과 유효 설정으로 확인한다. 요청한 모델이 그 계정에서 확인되지 않으면 준비 완료로 표시할 수 없다. 로그인 성공만으로 모든 모델 선택이나 무제한 이용이 보장되는 것은 아니다.

관리 UI를 `https://설정한이름/`에서 열고 관리 토큰으로 연결한다. UI에서 모델 상태가 준비됨으로 바뀌는지 확인하고 짧은 대화를 보낸다. 사용량 제한 상태는 UI에서 확인하며, 이용 제한을 우회하는 별도 계정이나 유료 API 전환을 자동으로 수행하지 않는다.

## 5. DM과 개인 맞춤 설정

UI의 설정에서 이름·맞춤 지침·판단 간격·기능별 권한·에이전트의 설정 변경 권한을 편집한다. 새 설치는 허용 중심 권한과 에이전트 설정 변경을 사용하되, 이미 저장한 사용자 설정은 설치 재실행으로 초기화하지 않는다. 대화 중 설정 변경도 저장된 설정에 반영할 수 있다.

인스타 DM은 별도 연결이 필요하다. UI의 인스타 로그인 화면에서 해당 계정으로 직접 로그인하고, 주인 사용자명과 `https://www.instagram.com/direct/t/대화ID/` 형식의 주인 DM 주소를 지정한 뒤 DM 채널을 켠다. 로그인·2단계 인증·플랫폼 이용 제한은 실제 화면에서 처리한다. 미연결 상태를 DM 송수신 완료로 간주하지 않는다.

자동 읽음은 기본 활성화되며 UI에서 바꿀 수 있다. 새 메시지를 받으면 독립적인 페이지에서 대화방을 열고 서버의 읽음 기록을 확인한다. 상대에게 표시되려면 Instagram 자체의 읽음 확인도 켜져 있어야 한다. Meta 공식 안내는 계정 설정의 `Messages and story replies → Show read receipts`와 대화별 설정을 설명한다. 현재 계정에서 실제 제공되는 설정을 확인하고, 주인 계정에서 새 DM의 읽음 표시까지 관측한다. 서버의 내부 읽음 기록만으로 상대 화면 표시를 이미 확인했다고 주장하지 않는다. [Meta 공식 읽음 설정 안내](https://about.fb.com/news/2024/03/instagram-dm-updates/).

브라우저는 ChatGPT·Instagram·일반 작업 프로필을 분리하며 파일은 `data/`에 보존한다. 장기 기억과 작업 상태도 같은 디스크에 남는다. Python 패키지는 `/data/python-venv`, 전역 npm 패키지는 `/data/npm`에 설치되므로 이미지 교체 후 유지된다. `apt`로 컨테이너의 시스템 패키지만 바꾼 내용은 이미지 재빌드 시 사라진다. 계속 필요한 시스템 패키지는 소스 Dockerfile에 추가하고 다시 배포한다.

## 6. 설치 뒤 확인과 운영

```sh
cd /opt/hoyoung
sudo docker compose config --quiet
sudo docker compose ps
sudo docker compose exec assistant node -e \
  "fetch('http://127.0.0.1:8080/api/health').then(async r=>{console.log(await r.text());process.exit(r.ok?0:1)})"
curl --fail --silent --show-error https://설정한이름/api/health
```

`/api/health`의 `ok: true`는 서버 프로세스 확인이다. 모델 로그인·DM 연결·실제 작업 성공은 각각 UI에서 확인한다. HTTPS 확인 시 인증서 검증을 끄는 `curl -k`를 쓰지 않는다.

다음으로 UI에서 짧은 대화와 기억을 남기고, 재시작 후 다시 확인한다.

```sh
sudo docker compose restart assistant
sudo docker compose ps
```

대화·기억·설정과 Codex 로그인 상태가 유지되는지 확인한다. 앱이 만든 무해한 작업 파일과 실제 필요한 패키지가 `data/`에 남는지도 확인한다. 동시 브라우저 작업 수와 빌드 부하는 작업 내용에 따라 RAM 사용량이 달라지므로 실제 워크로드로 확인한다. 6GB에서 부족하면 먼저 계정 합산 무료 한도 안에서 RAM을 배분한다.

```sh
# 비밀 값을 출력하지 않는 로그만 확인하고 공유 전 내용을 점검한다.
sudo docker compose logs --tail=100 assistant caddy
sudo docker stats --no-stream
df -h /opt/hoyoung
```

재부팅 후에도 동작하는지 확인하려면 진행 중 작업을 마친 뒤 호스트를 재부팅하고 다시 SSH로 접속해 `docker compose ps`, HTTPS 상태, 모델 상태를 확인한다. OCI가 인스턴스를 회수하거나 네트워크가 끊긴 경우 Docker 재시작 정책만으로 복구할 수 없다.

소스를 수정했으면 같은 설치 명령을 다시 실행해 이미지를 빌드한다. 기존 `.env`와 `data/`는 유지해야 하며, 변경 전 이 디렉터리를 백업한다. 설치 위치·Compose 프로젝트를 임의로 바꿔 기존 데이터와 다른 빈 서버를 시작하지 않는다.

## 7. 선택 사항: 서버 밖 백업

VM 안의 `data/`는 컨테이너 재시작용 영구 저장소다. VM 디스크 삭제·계정 중단에 대비하려면 다른 장소에 별도 백업한다. OCI Always Free Object Storage는 계정 총 **20GB·월 50,000 요청** 조건을 확인한 뒤 선택할 수 있다. 자동 생성이나 유료 활성화는 설치에 포함하지 않는다. [백업 구현](deployment.md#기억과-브라우저-데이터-백업)과 [OCI 무료 조건](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)을 확인한다.

이미 확보한 비공개 S3 호환 버킷을 사용한다면 `.env`에 아래 값을 직접 지정한다. OCI에서는 리전·네임스페이스에 맞는 S3 호환 endpoint와 지원되는 Customer Secret Key가 필요하다. IAM 정책, 버킷, 키는 별도 운영 설정이다.

```dotenv
S3_ENDPOINT=https://YOUR_NAMESPACE.compat.objectstorage.YOUR_REGION.oraclecloud.com
S3_BUCKET=hoyoung-private-backup
S3_REGION=YOUR_REGION
S3_ACCESS_KEY_ID=YOUR_PRIVATE_ACCESS_KEY
S3_SECRET_ACCESS_KEY=YOUR_PRIVATE_SECRET_KEY
S3_PREFIX=hoyoung
BACKUP_INTERVAL_SECONDS=86400
REQUIRE_BACKUP=false
```

이 구현은 매번 `/data` 전체 아카이브를 만든다. 큰 설치 디렉터리까지 5분마다 전송하지 말고 **하루 한 번(86400초)**부터 시작한다. 필요하면 한 시간(3600초)으로 조정하되 아카이브 크기·요청 수·보관 공간을 먼저 계산한다. 같은 원격 최신 파일을 갱신하는 방식도 압축 크기가 20GB를 넘으면 무료 조건을 벗어난다. 버킷 버전 관리나 추가 백업 세대의 저장 공간도 합산한다. TLS 데이터와 `.env`는 `/data` 아카이브에 포함되지 않으므로 별도로 비공개 보관한다.

`.env` 변경 뒤 서비스를 다시 생성한다.

```sh
cd /opt/hoyoung
sudo docker compose up -d assistant
# 최초 수동 업로드를 별도로 확인
sudo docker compose exec assistant npm run backup
```

실제 버킷 접근과 업로드 결과를 확인하기 전에는 서버 밖 백업이 된 것으로 간주하지 않는다. 대화·기억·로그인 세션·도구 설치가 백업에 포함될 수 있다. 복원은 기존 로컬 데이터를 자동으로 덮어쓰지 않으며, 빈 새 데이터 위치에 수행하는 절차를 먼저 별도 환경에서 확인한다. 브라우저 프로필의 일관된 이전은 브라우저와 앱을 정상 종료한 뒤 백업한다.

## 8. 중지·삭제

```sh
cd /opt/hoyoung
# 일시 중지: 다음 호스트 부팅 때는 자동 시작
sudo systemctl stop hoyoung-assistant.service
# 부팅 때도 자동 시작하지 않도록 유지하고 싶을 때
sudo systemctl disable --now hoyoung-assistant.service
# 컨테이너와 프로젝트 네트워크 제거: 호스트 데이터는 유지
sudo docker compose down
```

`/opt/hoyoung/data`, `.env`, Caddy TLS 디렉터리를 남기면 이후 같은 위치에서 `sudo systemctl enable --now hoyoung-assistant.service`로 다시 시작할 수 있다. Compose 컨테이너만 내리고 systemd 서비스를 켜두면 다음 부팅 때 다시 생성되므로, 장기 중지에는 서비스도 비활성화한다. 서버를 완전히 폐기할 때도 먼저 필요한 데이터를 외부에 보관한다. 이 문서의 중지 명령은 VM·부팅 볼륨·Object Storage를 삭제하지 않는다. OCI 자원 정리는 콘솔에서 해당 자원을 확인한 뒤 별도로 처리한다. 무료 자원의 재확보를 보장할 수 없으므로 단순 중지를 위해 VM을 삭제하지 않는다.

## 9. SSH 없이 첫 부팅에서 설치하기: cloud-init

OCI 콘솔의 초기화 스크립트로 첫 설치를 실행하는 대안이다. 이 방법은 섹션 2~3의 SSH 소스 복사·설치를 대신하며, VM이 직접 HTTPS로 소스를 내려받아 기존 `install.sh`를 실행한다. 생성기는 [cloud-init.mjs](../deploy/oracle/cloud-init.mjs)다. 관리형 개발 환경에서 외부 TCP·SSH 접속이 제공되지 않고 HTTP·HTTPS만 가능할 때도 초기 설치를 준비할 수 있다. 이 환경에서 SSH나 임의 원격 명령 실행이 가능하다고 확인한 것은 아니다.

2026-10-12 현재 오사카 `ap-osaka-1`의 실제 콘솔 로그인과 공식 API 키 등록을 완료했다. 초기 API 조사에서 기존 VM·디스크·백업·객체 버킷이 없고 무료 디스크 200GB가 남아 있음을 확인했다. 06:56 UTC 재조회에서도 A1 2 CPU/6GB 및 1 CPU/6·3·2GB, AMD E2.1.Micro가 모두 `OUT_OF_HOST_CAPACITY`였다. 이후 설치 소스용 비공개 버킷·한 객체·읽기 전용 PAR와 VCN·Internet Gateway·경로·공용 서브넷을 실제 준비했다. A1 1 OCPU·6GB·부팅 100GB의 첫 실제 생성 요청은 용량 부족으로 거절됐다. [실제 생성 재시도 방식](free-hosting.md#빈자리-확보를-위한-실제-생성-재시도)을 별도로 준비했으며 VM과 운영 배포는 아직 완료되지 않았다.

### 업로드 전에 확인할 조건

아직 아무 파일도 OCI에 업로드하지 않은 상태에서 계정과 자원을 읽기 전용으로 확인한다. 비밀번호 인증이 성공해도 최초 필수 MFA를 완료하기 전에는 VM·버킷·PAR·API 키를 만들지 않는다. MFA는 소유자의 휴대전화 등 실제 등록 수단에서 완료한다. 이 절차는 MFA나 계정 인증을 우회하지 않는다.

- 테넌시는 Always Free를 이용할 수 있는 상태이며 **Pay As You Go로 전환하지 않는다**. 체험 크레딧이 아니라 계속 무료인 자원 표시와 조건을 기준으로 확인한다.
- 선택한 홈 리전에 A1 모양이 제공되는지 확인한다. 실제 재고 부족이면 유료 모양으로 바꾸지 않는다. 새 VM 기본값은 Ubuntu 24.04 ARM64·2 OCPU·RAM 6GB·부팅 100GB다. 기존 VM까지 계정 합계 A1 **2 OCPU·RAM 12GB 이하**, 부팅·블록 디스크 **200GB 이하**로 유지한다.
- 소스 업로드에 쓸 Object Storage는 **비공개 버킷**이며, 다른 저장 데이터·버전·백업까지 계정 무료 저장 공간 **20GB**, 요청 **월 50,000회** 안에 들어간다. 자동 버전 보관이나 반복 업로드가 추가 용량을 쓰는지 확인한다.
- 연결은 공용 IPv4와 Internet Gateway를 사용한다. 유료 NAT Gateway·Load Balancer·DNS 서비스는 이 경로에 추가하지 않는다.
- 업로드한 **소스 아카이브 한 객체**만 읽는 PAR(Pre-Authenticated Request)을 사용한다. 버킷 전체·쓰기를 허용하지 않는다. 만료는 설치에 필요한 짧은 기간, 예를 들어 **24시간**, 여유가 필요해도 **7일** 정도로 정한다. 이 기간은 운영 선택이며 OCI가 요구하는 고정 만료 기간은 아니다.

PAR은 URL을 가진 사람이 지정된 객체를 읽을 수 있게 한다. URL과 생성된 user data를 비밀 파일로 다룬다. OCI는 PAR 만료를 요구하지만 최대 기간을 강제하지 않으므로 짧은 만료를 직접 지정해야 한다. [공식 PAR 범위와 만료 조건](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/usingpreauthenticatedrequests.htm).

### 비밀이 없는 소스 아카이브 준비

아래는 Node 24와 저장소가 있는 로컬 작업 환경에서 준비하는 명령이다. 실행 자체가 OCI 자원을 만들거나 업로드하지는 않는다. 산출물은 저장소 밖 `/var/tmp/hoyoung-boot`에 둔다.

```sh
umask 077
mkdir -p /var/tmp/hoyoung-boot
chmod 700 /var/tmp/hoyoung-boot
# 현재 디렉터리는 이 저장소의 루트
tar -czf /var/tmp/hoyoung-boot/source.tar.gz \
  Dockerfile .dockerignore package.json package-lock.json src public \
  deploy/run.mjs deploy/codex-storage.mjs deploy/r2-backup.mjs \
  deploy/snapshot-format.mjs deploy/oracle \
  docs/source-transcript.txt docs/latest-requirements.json
chmod 600 /var/tmp/hoyoung-boot/source.tar.gz
tar -tzf /var/tmp/hoyoung-boot/source.tar.gz
sha256sum /var/tmp/hoyoung-boot/source.tar.gz
```

허용 목록을 사용해 `.env`, `.data`, `data`, `.codex`, 로그인 프로필, 사용자 키, `node_modules`, 개발 캐시를 넣지 않는다. 현재 소스의 `src`, `public`, `deploy/oracle` 아래에도 비밀 파일을 따로 저장하지 않았는지 확인한다. `docs/source-transcript.txt`와 **`docs/latest-requirements.json`을 모두 포함**해야 원래 스페이스 대화와 후속 이름·모델·권한·DM 동작 요구가 새 서버에 전달된다. 이 자료 자체도 개인 대화가 포함된 비공개 소스다. 설치 소스를 바꾸면 아카이브와 SHA-256도 다시 만든다.

기존 준비 아카이브를 쓰는 경우 생성기는 저장소 파일이 타르 루트에 있거나, 하나의 상위 디렉터리 안에 있는 구조를 지원한다. 절대 경로·`..` 경로·링크 등 안전하지 않은 항목은 추출 전에 거부한다. 단순히 해시가 맞는다는 사실이 아카이브 내용 검토를 대신하지는 않는다.

준비된 소스 아카이브는 약 168KB로 user data에 그대로 넣을 크기가 아니다. OCI의 공식 인스턴스 생성 문서는 user data와 다른 metadata를 합쳐 **32,000바이트**로 제한하며 콘솔이 user data를 Base64로 인코딩한다. 생성기는 원문 user data를 **16,000바이트 이하**로 제한해 인코딩과 추가 metadata의 여유를 남긴다. [공식 초기화 스크립트와 metadata 제한](https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/launchinginstance.htm).

### 한 객체 업로드와 cloud-init 파일 생성

무료 자원·MFA 확인을 마친 뒤, OCI 콘솔에서 비공개 버킷에 `source.tar.gz` 한 객체를 업로드한다. 객체의 PAR을 만들 때 대상은 해당 객체, 권한은 **Permit object reads**로 고르고 짧은 만료를 지정한다. 이 설치에는 별도 API 키를 만들 필요가 없다. PAR은 처음 표시될 때 안전하게 보관하며 URL을 채팅·Git·일반 작업 로그에 붙이지 않는다.

생성된 **객체의 HTTPS PAR URL 전체**를 `/var/tmp/hoyoung-boot/source.par-url`에 한 줄로 저장하고 권한을 0600으로 설정한다. URL을 명령행 인자로 넣거나 출력하는 대신 개인 편집기에서 파일을 만든다. 생성기의 `--source-url-file`은 이 파일을 읽는다.

```sh
chmod 600 /var/tmp/hoyoung-boot/source.par-url
source_archive_sha256=$(sha256sum /var/tmp/hoyoung-boot/source.tar.gz | cut -d ' ' -f 1)
node deploy/oracle/cloud-init.mjs \
  --source-url-file /var/tmp/hoyoung-boot/source.par-url \
  --source-sha256 "$source_archive_sha256" \
  --out /var/tmp/hoyoung-boot/cloud-init.yaml
```

`--source-sha256`는 필수이며 64자리 16진수여야 한다. 기본 설치 위치는 `/opt/hoyoung`이다. 이름을 생략하면 VM의 공용 IPv4로 `hoyoung.공용IPv4.sslip.io`를 사용한다. 이미 소유한 도메인을 사용할 때는 `--hostname assistant.example.com`, 알고 있는 공용 IP를 명시할 때는 `--public-ip 실제공용IPv4`를 추가한다. 값은 서로 다른 운영 상황에 맞게 선택한다.

관리 토큰을 따로 지정하지 않으면 생성기가 새 토큰을 저장소 밖 **`cloud-init.yaml.admin-token`** 파일에 권한 0600으로 보관한다. 기존에 준비한 토큰을 사용할 때는 토큰 한 줄만 들어 있는 비공개 파일을 `--admin-token-file 경로`로 지정한다. 이 파일은 `.env` 전체가 아니라 토큰 값만 담으며, 32자 이상의 영문·숫자·밑줄·하이픈을 사용한다. 입력 PAR·토큰 파일도 저장소 밖에 있어야 하고 그룹·다른 사용자의 권한이 없어야 한다. 생성된 cloud-init 파일도 권한 0600이며 URL과 토큰을 출력하지 않는다. 소유자는 이 토큰 파일을 비공개로 보관하고 나중에 관리 UI에 입력한다. 기존 산출물을 덮어쓰지 않으므로 다시 생성할 때는 새 `--out` 경로를 사용한다.

생성기는 `#cloud-config` 형식으로 VM에 다운로드·SHA-256 확인·안전한 타르 추출·기존 설치 스크립트 실행을 지시한다. 실제 소스를 업로드하거나 VM을 만드는 기능은 없다. 해시가 다르거나 소스 구조가 잘못되면 설치를 계속하지 않는다.

### VM 생성 시 적용과 완료 확인

OCI 콘솔에서 위 무료 VM 조건으로 새 인스턴스를 준비하고 **Initialization script** 항목에 생성된 `cloud-init.yaml` 파일을 업로드한다. 콘솔이 인코딩하므로 미리 Base64로 바꾸지 않는다. 기존 SSH 복구용 공개키와 22번 접속 규칙은 유지한다. 80·443번과 DNS·외부 HTTPS 경로도 섹션 1처럼 준비한다. cloud-init도 기존 호스트·OCI 방화벽을 임의로 비우지 않는다.

user data에는 설치용 PAR과 관리 토큰이 포함된다. 허용된 인스턴스 metadata 접근이나 VM의 cloud-init 캐시에서도 이 정보가 남을 수 있다. 생성 파일·콘솔 입력·부팅 자료를 소유자만 취급하고 채팅이나 Git에 올리지 않는다. 토큰 파일이나 user data 내용을 `cat`으로 배포 로그에 출력하지 않는다. 부트스트랩은 다운로드 URL을 명령행에 넣지 않고 비공개 curl 설정을 사용하며 설치 로그를 `/var/log/hoyoung-bootstrap.log`에 권한 0600으로 저장한다. 설치 스크립트가 성공하고 부트스트랩의 별도 로컬 서버 확인도 통과해야 `/opt/hoyoung/.bootstrap-success`를 남긴다. 이 파일이나 로그는 원격 접근 권한이 있는 경우에만 직접 확인할 수 있다. 설치를 확인한 뒤 PAR을 삭제하거나 만료시키면 새 다운로드 권한을 닫을 수 있다. 관리 토큰 변경은 새 값을 서버 `.env`와 UI에 반영하는 별도 운영 작업이다.

VM이 실행됨으로 표시되는 것만으로 설치 완료는 아니다. 첫 부팅의 Docker 설치와 ARM 이미지 빌드를 기다리고, 공용 IPv4에 해당하는 HTTPS 주소에서 다음을 확인한다.

```sh
curl --fail --silent --show-error https://hoyoung.PUBLIC_IP.sslip.io/api/health
```

TLS 검증이 성공하고 서버 health가 응답하면 비공개 토큰으로 UI에 연결한다. 이어 섹션 4~6의 공식 ChatGPT 기기 로그인, 실제 모델 준비 상태, 인스타 DM, 대화·기억·작업 보존을 각각 확인한다. HTTPS가 뜨지 않으면 OCI의 부팅 상태·콘솔·네트워크·DNS를 확인한다. 원격 SSH 접속이나 설치 로그 읽기가 되지 않는 환경에서는 그 범위를 명확히 남기고, 실패 상태를 완료라고 보고하지 않는다.

cloud-init은 첫 부팅용이다. VM 재부팅 후 앱 시작은 `hoyoung-assistant.service`와 Docker의 재시작 정책이 맡는다. 소스 갱신을 위해 같은 user data가 매번 실행된다고 가정하지 않는다. 실제 OCI VM과 ARM·HTTPS·모델 로그인 검증이 끝나기 전에는 이 경로 역시 준비된 배포 절차다.
