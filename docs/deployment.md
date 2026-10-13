# 상시 실행과 배포

현재 구현은 Node 24 서버, SQLite 장기 기억, Playwright Chromium, 터미널 도구를 하나의 Linux 컨테이너에서 실행한다. **현재 운영 조건은 비용 0원이다.** 유료 Cloudflare Containers 배포는 진행하지 않는다. 무료 후보는 [free-hosting.md](free-hosting.md)에 정리한다. 외부 서버 확보와 사용자 ChatGPT 로그인이 완료되어야 실제 상시 운영을 검증할 수 있다.

## 지속 실행 서버에서 Docker로 운영

Docker와 Compose가 설치된 계속 켜져 있는 Linux 서버에서:

```sh
cp .env.example .env
# .env에 원격 접속용 APP_AUTH_TOKEN과 필요한 설정을 직접 입력
docker compose up -d --build
docker compose logs -f assistant
```

웹 제어판: `http://127.0.0.1:8080`. Compose는 포트를 서버 내부에만 공개한다. 원격 접속에는 기존 HTTPS 역방향 프록시 또는 개인 VPN을 연결한다. 컨테이너는 `restart: unless-stopped`로 프로세스 종료와 호스트 재부팅 후 다시 시작한다. 호스트와 Docker 서비스가 살아 있어야 실행된다. 개발 세션 컨테이너 자체를 상시 운영 서버로 취급하지 않는다.

`assistant-data` 이름의 Docker 볼륨에 `/data`를 저장한다. 대화, 기억, 일정, 설정, 브라우저 프로필, 작업 산출물과 `/data`에 설치한 도구는 컨테이너를 새로 만들어도 유지된다. `docker compose down -v`는 이 볼륨도 삭제하므로 평상시 중지에는 `docker compose down`을 사용한다. 호스트 디스크 장애 대비 외부 백업은 별도로 필요하다.

이미지에는 Node 24, Chromium, Python, pip, Git, curl, jq, Codex CLI 0.159.2가 포함된다. 실행 시 `/data/python-venv`를 만들고 `/data/npm/bin`을 PATH에 넣는다. 따라서 `pip install ...`과 `npm install -g ...`로 설치하는 패키지는 데이터 볼륨에 남는다. 운영 중 `apt`로 변경한 운영체제 패키지는 새 컨테이너에 남지 않으므로 반복해서 필요한 시스템 패키지는 Dockerfile에 추가한다. 모델 기본 경로는 사용자 ChatGPT 계정으로 공식 로그인하는 Codex CLI다. Codex CLI 설치 자체는 로그인이나 모델 이용 권한을 생성하지 않는다.

`PERSIST_CODEX_AUTH=true`인 새 컨테이너에서 Codex 설정 경로가 아직 없으면 해당 경로를 `/data/codex`로 연결하여 공식 로그인 상태와 CLI 설정도 유지한다. 기존 설정 디렉터리가 있으면 보존하고 연결되지 않았음을 알린다. 실행 환경의 기존 Pro 인증을 이미지로 가져오는 과정은 없다. Oracle 설치 경로와 새 로그인 절차는 [oracle-free.md](oracle-free.md)에 있다.

## Cloudflare Containers 구성 — 현재 비용 조건 때문에 실행하지 않음

Cloudflare 일반 Worker만으로는 이 프로그램의 Python, 쉘, 로컬 Chromium을 실행할 수 없다. Containers에는 유료 Workers 플랜과 별도 실행 비용이 필요한다. 실제 계정 OAuth 인증은 완료했으나 무료 플랜의 Containers 요청은 유료 플랜 필요 오류, R2 요청은 미활성화 오류(10042)를 반환했다. 계정을 유료 전환하거나 유료 자원을 생성하지 않았다. [공식 Containers 가격](https://developers.cloudflare.com/containers/pricing/).

이 디렉터리의 Cloudflare 코드는 별도 운영 선택을 위한 준비된 구성이다. 컨테이너 디스크는 임시 디스크이므로 Worker의 `SNAPSHOTS` R2 바인딩과 인증된 HTTP 경로로 `/data`를 복구하고 저장한다. 앱 SQLite와 Durable Object SQLite는 서로 다른 저장소다. [컨테이너 수명과 임시 디스크](https://developers.cloudflare.com/containers/concepts/architecture/).

원격 환경의 계정 인증 명령은 Wrangler 4.149.0에서 지원하는 `npx wrangler login --device --browser=false`이다. 비밀번호를 소스에 넣지 않는다. **아래 설정은 현재 비용 0원 요구로 실행하지 않는다.** 기존 유료 환경을 사용하는 별도 상황에서는 R2 버킷 `autonomous-assistant-data`, 비밀 `APP_AUTH_TOKEN`, 비밀이 아닌 변수 `PUBLIC_BASE_URL=https://<worker>.<subdomain>.workers.dev`가 필요하다. S3 API 키를 추가 생성할 필요 없이 Worker R2 바인딩으로 백업한다. `npm run check`는 업로드 없는 Worker dry-run이다. [공식 배포 절차](https://developers.cloudflare.com/containers/get-started/).

Worker의 `/internal/snapshot` 경로는 `APP_AUTH_TOKEN` Bearer 인증 뒤 R2를 직접 읽고 쓴다. 컨테이너 시작 전에 복원 요청을 처리할 수 있으므로 시작 순환 의존이 없다. 컨테이너에는 `BACKUP_ENDPOINT`와 `BACKUP_HTTP_TOKEN`이 전달된다. 직접 S3 저장을 쓰는 경우는 기존 `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` 설정을 유지한다.

분당 Cron과 모든 요청은 동일한 `personal-assistant` 인스턴스를 사용한다. Cron은 앱 상태를 확인하고 인스턴스를 깨운다. 유휴 타이머도 계속 연장한다. 플랫폼 장애와 재시작 때까지 무중단을 보장하는 구조는 아니다. [활동 타이머](https://developers.cloudflare.com/containers/api/container-class/#renewactivitytimeout).

## 기억과 브라우저 데이터 백업

`deploy/run.mjs`는 시작할 때 빈 `/data`에 최신 R2 스냅샷을 복구한 다음 앱을 실행한다. 기존 로컬 데이터가 있으면 원격의 오래된 스냅샷으로 덮어쓰지 않는다. 기본 300초마다 스냅샷을 저장하고 정상 종료 때 앱이 멈춘 뒤 최종 스냅샷을 저장한다.

앱 SQLite와 확장자가 `.sqlite`, `.sqlite3`, `.db`인 데이터베이스는 Node SQLite의 online backup으로 저장하여 커밋된 WAL 데이터까지 포함한다. 활성 WAL·SHM 파일은 복사하지 않는다. 직접 S3 저장은 `S3_PREFIX/latest.tar.gz` 하나로 갱신한다. Native R2 HTTP 저장은 아카이브를 8MiB 조각으로 보내고, 모두 업로드한 뒤 `latest.json`을 원자적으로 갱신한다. 큰 설치 디렉터리를 한 HTTP 요청에 넣지 않는다. 복원 시 조각 크기와 SHA-256을 확인한 후에만 압축을 푼다. 최신과 이전 두 개, 총 세 개의 완성된 스냅샷을 유지한다. 갱신 뒤 더 오래된 세대는 삭제하며, 앱 전용 prefix 안의 중단된 업로드는 24시간이 지난 뒤 정리한다. 정리가 실패하면 다음 성공한 갱신에서 재시도하므로 그동안 추가 임시 저장 용량이 생길 수 있다. 오래 걸린 복원 중 이전 세대가 정리되면 최신 manifest로 최대 두 번 다시 시작한다. 매번 전체 `/data` 아카이브를 만드는 방식이라 데이터 크기와 빈도가 저장소 요청·전송량을 결정한다.

브라우저 프로필과 일반 파일은 실행 중 파일 복사이므로 여러 파일 사이의 일관성이 보장되지 않는다. 로그인 프로필을 확실하게 옮길 때는 브라우저를 닫은 뒤 정상 종료 스냅샷을 사용한다. 실행 중 브라우저의 임시 잠금 파일은 스냅샷에서 제외한다. 강제 종료 시 마지막 성공한 스냅샷 이후의 변경은 사라질 수 있다. 기본 설정에서 최대 약 5분이며 저장 실패가 이어지면 그보다 길어진다. Docker 영구 볼륨이 유지되는 재시작은 이 R2 복원 간격에 의존하지 않는다.

수동 명령:

```sh
npm run backup
npm run restore
# 원격 저장소 없이 SQLite와 파일의 로컬 스냅샷 생성
node deploy/r2-backup.mjs snapshot /tmp/assistant-data.tar.gz
```

이 프로그램의 데이터 백업은 저장한 API 키, 로그인 세션, 설정도 포함할 수 있으므로 버킷은 개인 전용으로 유지한다. 백업 위치와 키를 다른 사용자와 공유하는 저장소로 설정하지 않는다.

## 관리형 개발 클라우드의 프록시 CA

이 실행 환경에서 Docker가 HTTPS 프록시를 사용할 때는 빌드 단계에 제공된 CA를 전달한다. CA는 BuildKit 비밀 마운트로만 사용하며 이미지에 복사하지 않는다.

```sh
docker build \
  --secret id=proxy_ca,src="$CODEX_PROXY_CERT" \
  --secret id=system_ca,src=/etc/ssl/certs/ca-certificates.crt \
  -t autonomous-assistant:dev .
```

Wrangler의 자동 이미지 빌드는 위 비밀 마운트를 자동 전달하지 않는다. 이런 프록시 환경에서 실제 배포할 때는 위와 같이 이미지를 먼저 만들고 `wrangler containers push autonomous-assistant:dev`로 올린 뒤, 출력된 Cloudflare registry 이미지 URI를 `wrangler.jsonc`의 `containers[0].image`에 넣어 배포한다. 일반 서버에서는 `npm run deploy`의 기본 Dockerfile 빌드를 쓴다.

개발 클라우드 안에서 이미지를 실행하고 HTTPS 네트워크를 쓰려면 런타임에도 CA 파일을 읽기 전용으로 마운트하고 Node에는 `NODE_EXTRA_CA_CERTS`를 지정한다. 브라우저도 같은 CA를 믿어야 하므로 `CHROMIUM_PROXY_CA_FILE`에 마운트 경로를 지정하면 시작 스크립트가 Chromium의 NSS 인증서 저장소에 가져온다. Python HTTPS에는 환경의 통합 CA 번들을 `SSL_CERT_FILE` 또는 `REQUESTS_CA_BUNDLE`로 전달한다. 인증서 검증을 끄는 방식으로 해결하지 않는다.

## 확인된 범위

Cloudflare Worker 빌드·설정 dry-run, SQLite 활성 WAL 스냅샷 복원, S3 호환 HTTP 모형 서버의 업로드·복원·빈 버킷 시작·기존 로컬 데이터 보존을 검증했다. 실제 Docker 이미지도 빌드하여 인증된 관리 API, 스페이스 원문 41개 메시지 가져오기, 인증서 검증을 켠 Chromium HTTPS 탐색, 컨테이너 재시작 후 대화 데이터 보존을 확인했다. 테스트 컨테이너는 실제 모델 상태를 `login_required`로 보고했다.

Cloudflare 계정 OAuth 인증은 실제 완료했다. Native R2 HTTP 조각 업로드·복원·크기/SHA-256 검증·업로드 중단 시 최신 스냅샷 유지·세 개 세대 보존·24시간 방치 업로드 정리·복원 도중 이전 세대 삭제 시 최신 복원 재시도는 모형 서버 테스트로 검증했다. 실제 외부 컨테이너 배포·실제 R2 업로드·사용자 ChatGPT 로그인 이후의 모델 응답은 미검증이며, 무료 계정 조건 때문에 유료 배포를 진행하지 않았다. 도입 검토 자료는 Cloudflare 공식 문서와 [공식 moltworker 소스](https://github.com/cloudflare/moltworker)를 사용했으며, moltworker 코드의 실험적 성격과 R2 backup/restore 방식도 확인했다. moltworker/OpenClaw 자체를 현재 앱에 설치하거나 연동한 것은 아니다.
