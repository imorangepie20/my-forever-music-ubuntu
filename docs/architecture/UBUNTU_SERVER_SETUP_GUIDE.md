# Ubuntu Server Setup Guide

작성일: `2026-04-29`
최종 점검: `2026-05-19`

이 문서는 `my-forever-music`을 Ubuntu 서버에 처음 올릴 때, 서버 설치 직후부터 개발 환경을 갖추기까지의 실제 작업 순서를 정리한 문서입니다.

현재 전략은 `MacBook 로컬에서 구현/시험 -> Ubuntu 이전` 순서입니다. 따라서 이 문서는 현재 단계의 주 개발 문서가 아니라, 로컬 시험 서비스가 안정화된 뒤 서버로 옮길 때 사용하는 이전 가이드입니다.

이 가이드는 `2026-04-29` 기준의 공식 문서를 참고해 작성했습니다.

> 현재 레포 기준으로 이 문서의 핵심 실행 경로는 `database` 프로필 + API `8081` 포트입니다. `infra/nginx/ubuntu.server.dev.conf`도 `127.0.0.1:8081`로 프록시하므로, API를 `8080`으로 띄우면 Nginx 뒤 확인이 실패합니다.

## 1. 목표 상태

최종적으로 아래 구성을 목표로 합니다.

- Ubuntu 서버: `24.04 LTS` 권장
- 웹 프론트엔드: `Vite dev server` on `5173`
- 메인 API: `Spring Boot` on `8081`
- AI 서비스: `FastAPI` on `8000`
- DB: `PostgreSQL` on Docker
- 캐시: `Redis` on Docker
- 리버스 프록시: 호스트 설치 `Nginx`

현재 레포 상태를 기준으로, 가장 현실적인 초기 개발 방식은 아래입니다.

- 호스트에서 `web`, `api` 실행
- Docker는 `PostgreSQL`, `Redis`만 사용
- Nginx는 호스트에 직접 설치해서 `/`, `/api`, `/docs`, `/actuator`, `/ai`를 프록시
- Spotify OAuth callback 테스트를 위해 이후 `443` HTTPS reverse proxy도 연결

## 2. 서버 OS 선택

권장 OS는 `Ubuntu 24.04 LTS` 입니다.

이유:

- Docker 공식 문서에서 지원 대상
- Nginx 공식 패키지 지원 대상
- Python `3.12` 계열을 기본 패키지로 가져가기 쉬움
- 장기 유지보수에 유리

Ubuntu `22.04 LTS`도 가능하지만, 현재 프로젝트가 `Python 3.12`를 목표로 잡고 있으므로 `24.04 LTS`가 더 편합니다.

## 3. 최초 접속 후 기본 패키지 설치

관리자 권한이 있는 사용자로 접속한 뒤 아래를 먼저 실행합니다.

```bash
sudo apt update
sudo apt upgrade -y
sudo apt install -y \
  git curl wget unzip zip jq ca-certificates gnupg gpg \
  build-essential pkg-config make \
  python3 python3-venv python3-pip \
  tmux
```

선택 사항:

- 시간대를 한국 기준으로 맞추려면:

```bash
sudo timedatectl set-timezone Asia/Seoul
timedatectl
```

## 4. 방화벽 기본 설정

Ubuntu 서버에서 `ufw`를 사용한다면 최소한 아래 포트를 엽니다.

```bash
sudo apt install -y ufw
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

주의:

- Docker 공식 문서는 Docker가 포트를 노출할 때 `ufw` 규칙을 우회할 수 있다고 안내합니다.
- 따라서 DB/Redis 포트는 외부에 직접 노출하지 않는 구성을 우선 권장합니다.

## 4-1. SSH 서버 설정

Ubuntu 원격 운영용 SSH는 레포 스크립트로 설정합니다.

상세 설계 문서는 [UBUNTU_SSH_SERVER_DESIGN.html](UBUNTU_SSH_SERVER_DESIGN.html)을 기준으로 봅니다.

```bash
cd /srv/my-forever-music
./infra/scripts/setup-ubuntu-ssh-server.sh
```

기본값은 아래처럼 잠금 위험이 낮은 설정입니다.

- `openssh-server` 설치/호스트 키 생성
- SSH 포트 `22`
- 공개키 로그인 허용
- 비밀번호 로그인 유지
- root 로그인은 `prohibit-password`
- `ufw`가 설치되어 있으면 SSH 포트 허용 규칙 추가
- `sshd -t` 검증 후 `ssh.socket` 또는 `ssh.service` 재시작

공개키를 서버 계정에 등록하려면:

```bash
./infra/scripts/setup-ubuntu-ssh-server.sh \
  --authorized-key-file ~/.ssh/id_ed25519.pub
```

비밀번호 로그인을 끄려면 먼저 다른 터미널에서 공개키 접속이 되는지 확인한 뒤 실행합니다. 스크립트는 대상 사용자의 `authorized_keys`가 비어 있으면 비밀번호 로그인을 끄지 않습니다.

```bash
./infra/scripts/setup-ubuntu-ssh-server.sh --disable-password
```

현재 상태만 확인하려면:

```bash
./infra/scripts/setup-ubuntu-ssh-server.sh --check-only
```

## 5. Docker Engine 설치

공식 Docker Engine Ubuntu 설치 가이드를 기준으로 설치합니다.

```bash
sudo apt remove $(dpkg --get-selections docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc | cut -f1) || true

sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo systemctl status docker --no-pager
sudo docker run hello-world
```

현재 사용자에게 Docker 권한을 주려면:

```bash
sudo usermod -aG docker "$USER"
newgrp docker
docker ps
```

## 6. Nginx 설치

공식 Nginx Ubuntu 패키지 가이드를 기준으로 stable 저장소를 추가합니다.

```bash
sudo apt install -y curl gnupg2 ca-certificates lsb-release ubuntu-keyring

curl https://nginx.org/keys/nginx_signing.key | gpg --dearmor \
  | sudo tee /usr/share/keyrings/nginx-archive-keyring.gpg >/dev/null

gpg --dry-run --quiet --no-keyring --import --import-options import-show \
  /usr/share/keyrings/nginx-archive-keyring.gpg

echo "deb [signed-by=/usr/share/keyrings/nginx-archive-keyring.gpg] \
https://nginx.org/packages/ubuntu $(lsb_release -cs) nginx" \
  | sudo tee /etc/apt/sources.list.d/nginx.list

echo -e "Package: *\nPin: origin nginx.org\nPin: release o=nginx\nPin-Priority: 900\n" \
  | sudo tee /etc/apt/preferences.d/99nginx

sudo apt update
sudo apt install -y nginx
sudo systemctl enable --now nginx
sudo systemctl status nginx --no-pager
```

## 7. Java 21 설치

Spring Boot API용으로 `Eclipse Temurin 21`을 설치합니다.

현재 프로젝트는 `Spring Boot 3.5.x + Java 21`을 기준으로 잡고 있습니다.

```bash
sudo apt install -y wget apt-transport-https gpg

wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | gpg --dearmor \
  | sudo tee /etc/apt/trusted.gpg.d/adoptium.gpg >/dev/null

echo "deb https://packages.adoptium.net/artifactory/deb \
$(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list

sudo apt update
sudo apt install -y temurin-21-jdk
java -version
javac -version
```

## 7-1. JAVA_HOME 설정

Temurin 패키지를 설치하면 보통 `java`와 `javac` 실행은 바로 되지만, `JAVA_HOME`은 명시적으로 잡아두는 편이 안전합니다.

현재 설치된 Java 경로 확인:

```bash
readlink -f "$(which java)"
```

`JAVA_HOME` 계산:

```bash
JAVA_BIN=$(readlink -f "$(which java)")
JAVA_HOME=$(dirname "$(dirname "$JAVA_BIN")")
echo "$JAVA_HOME"
```

시스템 전체에 적용:

```bash
echo "export JAVA_HOME=$JAVA_HOME" | sudo tee /etc/profile.d/java.sh
echo 'export PATH=$JAVA_HOME/bin:$PATH' | sudo tee -a /etc/profile.d/java.sh
source /etc/profile.d/java.sh
echo "$JAVA_HOME"
java -version
```

## 7-2. 여러 Java 버전이 있을 때

서버에 JDK가 여러 개 설치되어 있으면 `update-alternatives`로 기본 버전을 선택합니다.

```bash
sudo update-alternatives --config java
sudo update-alternatives --config javac
```

선택 후 다시 확인:

```bash
java -version
javac -version
echo "$JAVA_HOME"
```

## 8. Gradle 준비

`services/api`는 Gradle 프로젝트입니다.

현재 레포에는 `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`가 모두 들어 있습니다.

따라서 Ubuntu 서버에서 별도 system Gradle을 먼저 설치할 필요 없이 wrapper를 우선 사용합니다.

확인:

```bash
cd /srv/my-forever-music/services/api
./gradlew --version
ls -l gradle/wrapper/gradle-wrapper.jar
```

만약 `Permission denied`가 나면 `chmod +x ./gradlew`를 한 번 실행한 뒤 다시 시도합니다.

## 9. Node.js LTS 설치

`2026-04-29` 기준 Node.js `v24.14.1` LTS를 기준으로 안내합니다.  
공식 다운로드 아카이브 기준 `linux-x64`와 `linux-arm64` 바이너리가 제공됩니다.

`amd64/x86_64` 서버 예시:

```bash
cd /tmp
wget https://nodejs.org/dist/v24.14.1/node-v24.14.1-linux-x64.tar.xz
sudo tar -xJf node-v24.14.1-linux-x64.tar.xz -C /opt
sudo ln -sfn /opt/node-v24.14.1-linux-x64 /opt/node
echo 'export PATH=/opt/node/bin:${PATH}' | sudo tee /etc/profile.d/node.sh
source /etc/profile.d/node.sh
node -v
npm -v
```

`arm64` 서버라면 파일명을 `node-v24.14.1-linux-arm64.tar.xz`로 바꾸면 됩니다.

## 10. pnpm 활성화

Node 공식 문서의 `Corepack` 기능을 사용해 `pnpm`을 활성화합니다.

```bash
corepack enable
corepack install --global pnpm@*
pnpm -v
```

## 11. 프로젝트 클론

원하는 작업 디렉토리에 레포를 가져옵니다.

```bash
sudo mkdir -p /srv
sudo chown "$USER":"$USER" /srv
cd /srv
git clone <YOUR_REPOSITORY_URL> my-forever-music
cd my-forever-music
```

이미 로컬에서 작업한 내용을 서버로 옮기는 경우에는 Git remote 또는 `rsync` 기준으로 가져오면 됩니다.

## 12. DB/Redis 개발용 컨테이너 실행

현재 Ubuntu 개발 기준 Compose 템플릿은 `PostgreSQL`과 `Redis`만 올리도록 사용합니다.

두 서비스 포트는 기본적으로 `127.0.0.1`에만 바인딩되도록 작성되어 있습니다.

환경 변수 파일 준비:

```bash
cd /srv/my-forever-music
cp infra/docker/env.ubuntu.example infra/docker/.env.ubuntu-dev
```

컨테이너 실행:

```bash
docker compose \
  --env-file infra/docker/.env.ubuntu-dev \
  -f infra/docker/docker-compose.ubuntu-dev.yml \
  up -d
```

확인:

```bash
docker compose -f infra/docker/docker-compose.ubuntu-dev.yml ps
```

## 13. Spring Boot API 실행

Ubuntu 개발 환경에서는 DB를 붙인 실제 흐름을 확인해야 하므로 `database` 프로필로 실행합니다. 이 프로필의 기본 API 포트는 `8081`이며, Nginx 개발용 설정도 같은 포트를 바라봅니다.

```bash
cd /srv/my-forever-music/services/api
SPRING_PROFILES_ACTIVE=database \
API_PORT=8081 \
DB_HOST=127.0.0.1 \
DB_PORT=5432 \
DB_NAME=my_forever_music \
DB_USERNAME=postgres \
DB_PASSWORD=postgres \
AI_SERVICE_BASE_URL=http://127.0.0.1:8000 \
./gradlew bootRun
```

첫 실행 전 확인할 것:

- PostgreSQL 컨테이너가 올라와 있는지
- `infra/docker/.env.ubuntu-dev`의 DB 값과 위 실행 환경 변수가 맞는지
- Flyway migration은 현재 `V1`부터 `V43`까지 존재하므로, 오래된 DB 볼륨을 재사용한다면 migration 실패 로그를 먼저 확인할 것

헬스체크:

```bash
curl http://127.0.0.1:8081/actuator/health
curl http://127.0.0.1:8081/api/v1/system/info
```

주의:

- `./gradlew bootRun`만 실행하면 기본 `local` 프로필로 뜰 수 있습니다. 빠른 API 확인에는 괜찮지만, Ubuntu 이전 검증에서는 DB/Flyway/JPA가 붙는 `database` 프로필을 기준으로 봅니다.
- `8080`으로 확인하면 현재 Ubuntu Nginx 개발 설정과 맞지 않습니다.
- 로그에 `No active profile set, falling back to 1 default profile: "local"`이 보이면 이 가이드의 Ubuntu DB 검증 경로로 뜬 것이 아닙니다. 위 실행 예시처럼 `SPRING_PROFILES_ACTIVE=database`를 명시해 다시 실행합니다.

## 14. 웹앱 실행

```bash
cd /srv/my-forever-music/apps/web
pnpm install
pnpm dev -- --host 0.0.0.0 --port 5173
```

직접 확인:

```bash
curl -I http://127.0.0.1:5173
```

## 15. AI 서비스 실행

현재 `services/ai`에는 최소 FastAPI 스캐폴드가 들어가 있습니다.

포함된 기본 엔드포인트:

- `/`
- `/health`
- `/docs`
- `/openapi.json`

직접 포트로 실행:

```bash
cd /srv/my-forever-music/services/ai
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Nginx에서 `/ai/` prefix로 공개할 때는 `AI_ROOT_PATH=/ai`를 같이 주는 것을 권장합니다.

```bash
cd /srv/my-forever-music/services/ai
source .venv/bin/activate
AI_ROOT_PATH=/ai uvicorn app.main:app --host 0.0.0.0 --port 8000
```

직접 확인:

```bash
curl http://127.0.0.1:8000/health
curl -I http://127.0.0.1:8000/docs
```

## 16. Nginx 개발용 프록시 연결

Ubuntu 서버에서 호스트 프로세스로 `web`, `api`, `ai`를 띄우는 개발 환경에는 아래 설정 파일을 사용합니다.

- [ubuntu.server.dev.conf](/Users/woosungjo/music-space/my-forever-music/infra/nginx/ubuntu.server.dev.conf)

적용:

```bash
cd /srv/my-forever-music
sudo cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak.$(date +%F-%H%M%S)
sudo cp infra/nginx/ubuntu.server.dev.conf /etc/nginx/nginx.conf
sudo nginx -t
sudo systemctl restart nginx
sudo systemctl status nginx --no-pager
```

## 17. 최종 확인

브라우저 또는 curl 기준으로 아래를 확인합니다.

```bash
curl http://127.0.0.1/
curl http://127.0.0.1/api/v1/system/info
curl http://127.0.0.1/actuator/health
curl http://127.0.0.1/ai/health
curl -X POST http://127.0.0.1/ai/v1/recommendations/preview -H 'Content-Type: application/json' -d '{"mode":"discovery","limit":2}'
curl -I http://127.0.0.1/docs
curl -I http://127.0.0.1/ai/docs
```

`GMS` preview는 AI 서비스뿐 아니라 PMS/EMS 데이터 상태의 영향을 받으므로, 서버 최초 설치 smoke test에서는 위 헬스체크와 문서 경로를 먼저 통과시킵니다. 실제 사용자 데이터 또는 EMS pool이 준비된 뒤 아래처럼 별도로 확인합니다.

```bash
curl -X POST http://127.0.0.1/api/v1/gms/recommendations/preview \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"user-id-from-login","mood":"upbeat","limit":2,"seed_track_ids":["track-alpha"]}'
```

브라우저에서는 아래 순서로 체크하면 됩니다.

1. `http://SERVER_IP/`
2. `http://SERVER_IP/api/v1/system/info`
3. `http://SERVER_IP/actuator/health`
4. `http://SERVER_IP/ai/health`
5. `POST http://SERVER_IP/ai/v1/recommendations/preview`
6. 실제 사용자 데이터 준비 후 `POST http://SERVER_IP/api/v1/gms/recommendations/preview`
7. `http://SERVER_IP/docs`
8. `http://SERVER_IP/ai/docs`

## 18. systemd 장기 실행 고정

위 단계의 `web`/`api`/`ai` 직접 실행이 통과하면, 다음에는 터미널 세션에 묶이지 않도록 systemd로 고정합니다.

현재 레포에는 Ubuntu 개발 스택용 systemd 템플릿과 설치 스크립트가 포함되어 있습니다.

- [infra/systemd](/Users/woosungjo/music-space/my-forever-music/infra/systemd)
- [install-ubuntu-systemd-stack.sh](/Users/woosungjo/music-space/my-forever-music/infra/scripts/install-ubuntu-systemd-stack.sh)
- [check-ubuntu-stack.sh](/Users/woosungjo/music-space/my-forever-music/infra/scripts/check-ubuntu-stack.sh)

설치:

```bash
cd /srv/my-forever-music
./infra/scripts/install-ubuntu-systemd-stack.sh --user "$USER" --enable --restart
```

설치 후 실제 서버 비밀값은 `/etc/my-forever-music/*.env`에서 관리합니다.

```bash
sudo editor /etc/my-forever-music/api.env
sudo editor /etc/my-forever-music/ai.env
sudo editor /etc/my-forever-music/web.env
```

`services/api/.env.local`에 정리한 provider key를 Ubuntu systemd env로 옮기려면:

```bash
cd /srv/my-forever-music
./infra/scripts/sync-ubuntu-env-from-local.sh --dry-run
./infra/scripts/sync-ubuntu-env-from-local.sh --restart
```

이 스크립트는 비밀값을 출력하지 않고 `/etc/my-forever-music/*.env`에 병합합니다. Ubuntu runtime 기본값은 아래처럼 고정합니다.

- `SPRING_PROFILES_ACTIVE=database`
- `API_PORT=8081`
- `DB_HOST=127.0.0.1`
- `DB_PORT=5432`
- `AI_SERVICE_BASE_URL=http://127.0.0.1:8000`
- OAuth redirect URI: `https://imapplepie20.tplinkdns.com/platforms/oauth/callback`

주요 서비스:

```bash
sudo systemctl status "my-forever-music@$USER.target"
sudo systemctl restart "my-forever-music@$USER.target"

sudo journalctl -u "my-forever-music-api@$USER.service" -f
sudo journalctl -u "my-forever-music-ai@$USER.service" -f
sudo journalctl -u "my-forever-music-web@$USER.service" -f
```

systemd 전환 후 smoke test:

```bash
cd /srv/my-forever-music
./infra/scripts/check-ubuntu-stack.sh
```

실제 사용자 데이터까지 확인하려면 `SMOKE_USER_ID`를 같이 넘깁니다.

```bash
SMOKE_USER_ID=user-id-from-login ./infra/scripts/check-ubuntu-stack.sh
```

DB/Redis, API, AI, Web, Nginx를 한 번에 재시작하려면 아래 스크립트를 사용합니다.

```bash
cd /srv/my-forever-music
./infra/scripts/restart-ubuntu-stack.sh
```

운영 데이터까지 함께 확인하려면:

```bash
SMOKE_USER_ID=user-id-from-login ./infra/scripts/restart-ubuntu-stack.sh
```

이 systemd 구성은 현재 Ubuntu 이전 검증 경로와 동일하게 아래 포트를 사용합니다.

- Web: `5173`
- API: `8081`
- AI: `8000`
- Nginx: `80`, 이후 HTTPS 연결 시 `443`

## 19. 현재 시점의 한계

- 이 문서는 여전히 운영 배포 런북이 아니라 Ubuntu 개발/이전 환경을 처음 맞추는 가이드입니다.
- 운영용 HTTPS 인증서 자동 갱신, 정식 배포 artifact 기반 실행, 백업/복구 자동화, 로그 로테이션은 별도 운영 런북에서 확정해야 합니다.
- `services/api`의 Gradle wrapper와 Flyway migration은 현재 레포에 포함되어 있습니다.
- `apps/web`에는 PMS/EMS/GMS와 메인 음악 경험 화면이 들어와 있으므로, 최초 서버 확인 후에는 실제 로그인/플랫폼 연결/가져오기 흐름을 별도로 검증해야 합니다.

즉, 이 문서는 "Ubuntu 서버에 개발 환경을 올리고 접근 가능한 상태까지"를 목표로 하고, 실제 제품 기능 완성은 그 다음 단계입니다.

## 20. 다음 추천 작업

1. 이 가이드로 `web`/`api`/`ai`/DB/Redis/Nginx 개발 경로를 먼저 통과
2. systemd target으로 장기 실행 전환 후 `check-ubuntu-stack.sh` 통과
3. 실제 도메인과 HTTPS 인증서를 붙인 `ubuntu.server.dev.https.conf` 검증
4. Spotify/TIDAL/Last.fm redirect URI와 서버 도메인 환경 변수 정리
5. 백업/복구, 로그 로테이션, 운영 헬스체크 런북 추가

HTTPS 전환 전에 현재 서버가 실제 도메인 트래픽을 받고 있는지 먼저 확인합니다.

```bash
cd /srv/my-forever-music
DOMAIN=imapplepie20.tplinkdns.com ./infra/scripts/check-ubuntu-https-readiness.sh
```

이 점검에서 아래가 모두 맞아야 `ubuntu.server.dev.https.conf` 적용과 인증서 발급으로 넘어갑니다.

- 공개 DNS/공유기 port-forward가 이 Ubuntu 서버의 LAN IP를 바라봄
- `80/tcp`가 이 서버의 Nginx로 들어옴
- `certbot`이 설치되어 있거나 설치 가능함
- `/etc/letsencrypt/live/<domain>/` 인증서가 있거나 webroot 방식으로 새로 발급 가능함
- HTTPS 적용 후 `443/tcp`가 이 서버의 Nginx로 들어옴

공개 HTTP가 이 Ubuntu 서버로 들어오는 것이 확인되면 인증서를 발급하고 HTTPS Nginx 설정을 적용합니다.

```bash
cd /srv/my-forever-music
./infra/scripts/install-ubuntu-https-cert.sh \
  --domain imapplepie20.tplinkdns.com \
  --email YOUR_EMAIL@example.com
```

적용 후 확인:

```bash
curl -I https://imapplepie20.tplinkdns.com/
BASE_URL=https://imapplepie20.tplinkdns.com ./infra/scripts/check-ubuntu-stack.sh
```

## 21. 공식 참고

- Docker Engine on Ubuntu: `https://docs.docker.com/engine/install/ubuntu/`
- Nginx Ubuntu packages: `https://nginx.org/en/linux_packages.html`
- Temurin Linux install: `https://adoptium.net/installation/linux/`
- Gradle install guide: `https://docs.gradle.org/current/userguide/installation.html`
- Node.js v24.14.1 archive: `https://nodejs.org/en/download/archive/v24.14.1`
- Node.js Corepack docs: `https://nodejs.org/download/release/v22.13.1/docs/api/corepack.html`
