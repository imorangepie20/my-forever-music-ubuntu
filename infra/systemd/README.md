# infra/systemd

Ubuntu 서버에서 `my-forever-music` 개발 스택을 장기 실행하기 위한 systemd 템플릿입니다.

현재 Ubuntu 이전 검증 경로는 아래 프로세스를 호스트에서 실행하고, PostgreSQL/Redis만 Docker로 실행하는 방식입니다.

- `my-forever-music-ai@USER.service`: FastAPI AI service on `8000`
- `my-forever-music-api@USER.service`: Spring Boot API on `8081`
- `my-forever-music-web@USER.service`: Vite dev server on `5173`
- `my-forever-music@USER.target`: 위 세 서비스를 함께 묶는 target

## 설치

```bash
cd /srv/my-forever-music
./infra/scripts/install-ubuntu-systemd-stack.sh --user "$USER" --enable --restart
```

스크립트는 아래 파일을 설치합니다.

- unit files: `/etc/systemd/system/my-forever-music*`
- env files: `/etc/my-forever-music/{api,ai,web}.env`

기존 env 파일은 덮어쓰지 않습니다. 새 서버에서 처음 설치한 뒤에는 `/etc/my-forever-music/*.env` 값을 실제 provider key, redirect URI, 운영 scheduler user id에 맞게 조정합니다.

이미 `services/api/.env.local`에 provider key를 정리했다면, 아래 스크립트로 `/etc/my-forever-music/*.env`에 병합할 수 있습니다. 값은 출력하지 않고 키 이름만 dry-run으로 확인할 수 있습니다.

```bash
cd /srv/my-forever-music
./infra/scripts/sync-ubuntu-env-from-local.sh --dry-run
./infra/scripts/sync-ubuntu-env-from-local.sh --restart
```

## 운영 명령

```bash
sudo systemctl status "my-forever-music@$USER.target"
sudo systemctl restart "my-forever-music@$USER.target"
sudo journalctl -u "my-forever-music-api@$USER.service" -f
sudo journalctl -u "my-forever-music-ai@$USER.service" -f
sudo journalctl -u "my-forever-music-web@$USER.service" -f
```

전체 Ubuntu 스택을 한 번에 재시작하려면:

```bash
cd /srv/my-forever-music
./infra/scripts/restart-ubuntu-stack.sh
```

Ubuntu 스크립트는 `sudo`로 호출해도 `SUDO_USER`를 통해 로그인 계정을 유지하고,
중복 서비스가 생기지 않도록 `root` application stack 생성을 거부합니다.
기존 `@root` 서비스가 남아 있으면 재시작 전에 정리 명령을 출력하고 중단합니다.

실제 사용자 데이터까지 smoke test하려면:

```bash
SMOKE_USER_ID=user-id-from-login ./infra/scripts/restart-ubuntu-stack.sh
```

## 점검

```bash
cd /srv/my-forever-music
./infra/scripts/check-ubuntu-stack.sh
```

Nginx가 다른 host로 열려 있으면:

```bash
BASE_URL=http://SERVER_IP ./infra/scripts/check-ubuntu-stack.sh
```
