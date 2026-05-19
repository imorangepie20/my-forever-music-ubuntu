#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  cat >&2 <<'EOF'
setup-ubuntu-ssh-server.sh is for the Ubuntu host only.
EOF
  exit 1
fi

RUN_USER="${SUDO_USER:-$(id -un)}"
SSH_PORT="${SSH_PORT:-22}"
PASSWORD_AUTH="${PASSWORD_AUTH:-yes}"
PERMIT_ROOT_LOGIN="${PERMIT_ROOT_LOGIN:-prohibit-password}"
SSHD_DROPIN="/etc/ssh/sshd_config.d/01-my-forever-music.conf"
OLD_SSHD_DROPIN="/etc/ssh/sshd_config.d/90-my-forever-music.conf"
AUTHORIZED_KEY_FILE=""
AUTHORIZED_KEY_VALUE=""
ALLOW_UFW=true
ENABLE_UFW=false
CHECK_ONLY=false
ROLLBACK_ACTIVE=false
ROLLBACK_DIR=""
ROLLBACK_FILES=()

usage() {
  cat <<USAGE
Usage: ./infra/scripts/setup-ubuntu-ssh-server.sh [options]

Install and configure the Ubuntu OpenSSH server safely.

Defaults:
  user:               ${RUN_USER}
  port:               ${SSH_PORT}
  password auth:      ${PASSWORD_AUTH}
  permit root login:  ${PERMIT_ROOT_LOGIN}
  ufw rule:           allow SSH port if ufw is installed

Options:
  --user USER              target account for authorized_keys. Default: sudo user/current user
  --port PORT              SSH listen port. Default: 22
  --authorized-key-file P  append public key(s) from file to USER authorized_keys
  --authorized-key KEY     append one public key string to USER authorized_keys
  --disable-password       set PasswordAuthentication/KbdInteractiveAuthentication to no
  --password-auth yes|no   explicitly set password authentication
  --permit-root-login VAL  yes|no|prohibit-password|forced-commands-only
  --enable-ufw             enable ufw after adding the SSH allow rule
  --no-ufw                 do not change ufw rules
  --check-only             print current SSH status/effective config only
  -h, --help               show this help

Examples:
  ./infra/scripts/setup-ubuntu-ssh-server.sh
  ./infra/scripts/setup-ubuntu-ssh-server.sh --authorized-key-file ~/.ssh/id_ed25519.pub
  ./infra/scripts/setup-ubuntu-ssh-server.sh --disable-password
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user)
      RUN_USER="${2:?--user requires a value}"
      shift 2
      ;;
    --port)
      SSH_PORT="${2:?--port requires a value}"
      shift 2
      ;;
    --authorized-key-file)
      AUTHORIZED_KEY_FILE="${2:?--authorized-key-file requires a value}"
      shift 2
      ;;
    --authorized-key)
      AUTHORIZED_KEY_VALUE="${2:?--authorized-key requires a value}"
      shift 2
      ;;
    --disable-password)
      PASSWORD_AUTH="no"
      shift
      ;;
    --password-auth)
      PASSWORD_AUTH="${2:?--password-auth requires a value}"
      shift 2
      ;;
    --permit-root-login)
      PERMIT_ROOT_LOGIN="${2:?--permit-root-login requires a value}"
      shift 2
      ;;
    --enable-ufw)
      ENABLE_UFW=true
      shift
      ;;
    --no-ufw)
      ALLOW_UFW=false
      shift
      ;;
    --check-only)
      CHECK_ONLY=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

log() {
  printf '[ssh-setup] %s\n' "$*"
}

run_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  else
    sudo "$@"
  fi
}

root_shell() {
  if [[ "$(id -u)" -eq 0 ]]; then
    bash -c "$1"
  else
    sudo bash -c "$1"
  fi
}

require_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$name" >&2
    exit 1
  fi
}

prepare_rollback() {
  ROLLBACK_DIR="$(mktemp -d)"
  ROLLBACK_ACTIVE=true
  record_rollback_file /etc/ssh/sshd_config
  record_rollback_file "$SSHD_DROPIN"
  record_rollback_file "$OLD_SSHD_DROPIN"
  record_rollback_file /etc/systemd/system/ssh.socket.d/override.conf
}

record_rollback_file() {
  local path="$1"
  local label="${path//\//__}"
  ROLLBACK_FILES+=("$path")

  if run_root test -e "$path"; then
    run_root cp -a "$path" "$ROLLBACK_DIR/$label"
  else
    : >"$ROLLBACK_DIR/$label.missing"
  fi
}

restore_rollback_files() {
  local path label i

  if [[ "$ROLLBACK_ACTIVE" != true || -z "$ROLLBACK_DIR" ]]; then
    return
  fi

  set +e
  log "Restoring previous SSH configuration"
  for (( i=${#ROLLBACK_FILES[@]}-1; i>=0; i-- )); do
    path="${ROLLBACK_FILES[$i]}"
    label="${path//\//__}"
    if [[ -e "$ROLLBACK_DIR/$label.missing" ]]; then
      run_root rm -f "$path"
    elif [[ -e "$ROLLBACK_DIR/$label" ]]; then
      run_root cp -a "$ROLLBACK_DIR/$label" "$path"
    fi
  done
  run_root systemctl daemon-reload
  if unit_exists ssh.socket && unit_active ssh.socket; then
    run_root systemctl restart ssh.socket
  elif unit_exists ssh.service && unit_active ssh.service; then
    run_root systemctl restart ssh.service
  fi
  set -e
}

cleanup_rollback() {
  ROLLBACK_ACTIVE=false
  if [[ -n "$ROLLBACK_DIR" ]]; then
    rm -rf "$ROLLBACK_DIR"
  fi
}

rollback_on_error() {
  local code=$?
  restore_rollback_files
  exit "$code"
}

validate_inputs() {
  if ! [[ "$SSH_PORT" =~ ^[0-9]+$ ]] || (( SSH_PORT < 1 || SSH_PORT > 65535 )); then
    printf 'Invalid SSH port: %s\n' "$SSH_PORT" >&2
    exit 2
  fi

  case "$PASSWORD_AUTH" in
    yes|no) ;;
    *)
      printf 'Invalid password auth value: %s (use yes or no)\n' "$PASSWORD_AUTH" >&2
      exit 2
      ;;
  esac

  case "$PERMIT_ROOT_LOGIN" in
    yes|no|prohibit-password|forced-commands-only) ;;
    *)
      printf 'Invalid PermitRootLogin value: %s\n' "$PERMIT_ROOT_LOGIN" >&2
      exit 2
      ;;
  esac

  if ! id "$RUN_USER" >/dev/null 2>&1; then
    printf 'User does not exist: %s\n' "$RUN_USER" >&2
    exit 1
  fi

  if [[ -n "$AUTHORIZED_KEY_FILE" && ! -r "$AUTHORIZED_KEY_FILE" ]]; then
    cat >&2 <<EOF
Public key file is not readable: ${AUTHORIZED_KEY_FILE}

If you only want to enable the SSH server first, run without --authorized-key-file:
  ./infra/scripts/setup-ubuntu-ssh-server.sh

For key login, generate the key on the client machine you will SSH from, then pass
that public key to this server:
  ./infra/scripts/setup-ubuntu-ssh-server.sh --authorized-key 'ssh-ed25519 AAAA...'
EOF
    exit 1
  fi
}

unit_exists() {
  systemctl list-unit-files "$1" >/dev/null 2>&1
}

unit_active() {
  systemctl is-active --quiet "$1" >/dev/null 2>&1
}

install_openssh_server() {
  if [[ "$(id -u)" -ne 0 ]]; then
    require_command sudo
  fi

  if dpkg-query -W -f='${Status}\n' openssh-server 2>/dev/null | grep -q '^install ok installed$'; then
    log "openssh-server already installed"
  else
    log "Installing openssh-server"
    run_root apt update
    run_root apt install -y openssh-server
  fi

  log "Ensuring SSH host keys exist"
  run_root ssh-keygen -A
  ensure_sshd_runtime_dir
}

ensure_sshd_runtime_dir() {
  run_root install -d -m 0755 -o root -g root /run/sshd
}

backup_file() {
  local path="$1"
  if run_root test -e "$path"; then
    run_root cp -a "$path" "${path}.bak.$(date +%Y%m%d%H%M%S)"
  fi
}

ensure_sshd_include_dir() {
  run_root install -d -m 0755 /etc/ssh/sshd_config.d

  if run_root grep -Eq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config; then
    return
  fi

  log "Adding sshd_config.d include to /etc/ssh/sshd_config"
  backup_file /etc/ssh/sshd_config

  local tmp
  tmp="$(mktemp)"
  {
    printf 'Include /etc/ssh/sshd_config.d/*.conf\n'
    run_root cat /etc/ssh/sshd_config
  } >"$tmp"

  run_root install -m 0644 "$tmp" /etc/ssh/sshd_config
  rm -f "$tmp"
}

write_sshd_dropin() {
  local tmp
  tmp="$(mktemp)"

  cat >"$tmp" <<EOF
# Managed by my-forever-music infra/scripts/setup-ubuntu-ssh-server.sh
# Keep this file small so rollback is easy if SSH needs manual repair.
Port ${SSH_PORT}
PubkeyAuthentication yes
PasswordAuthentication ${PASSWORD_AUTH}
KbdInteractiveAuthentication ${PASSWORD_AUTH}
UsePAM yes
PermitRootLogin ${PERMIT_ROOT_LOGIN}
X11Forwarding no
ClientAliveInterval 300
ClientAliveCountMax 2
EOF

  backup_file "$SSHD_DROPIN"
  run_root install -m 0644 "$tmp" "$SSHD_DROPIN"
  rm -f "$tmp"

  if run_root test -e "$OLD_SSHD_DROPIN"; then
    log "Moving old managed drop-in out of the active include set"
    run_root mv "$OLD_SSHD_DROPIN" "${OLD_SSHD_DROPIN}.disabled.$(date +%Y%m%d%H%M%S)"
  fi
}

configure_ssh_socket_port() {
  if ! unit_exists ssh.socket; then
    return
  fi

  log "Configuring ssh.socket ListenStream=${SSH_PORT}"
  run_root install -d -m 0755 /etc/systemd/system/ssh.socket.d

  local tmp
  tmp="$(mktemp)"
  cat >"$tmp" <<EOF
[Socket]
ListenStream=
ListenStream=0.0.0.0:${SSH_PORT}
ListenStream=[::]:${SSH_PORT}
BindIPv6Only=ipv6-only
EOF

  run_root install -m 0644 "$tmp" /etc/systemd/system/ssh.socket.d/override.conf
  rm -f "$tmp"
}

append_authorized_keys() {
  if [[ -z "$AUTHORIZED_KEY_FILE" && -z "$AUTHORIZED_KEY_VALUE" ]]; then
    return
  fi

  local home group ssh_dir authorized_keys tmp keys_tmp
  home="$(getent passwd "$RUN_USER" | cut -d: -f6)"
  group="$(id -gn "$RUN_USER")"
  ssh_dir="${home}/.ssh"
  authorized_keys="${ssh_dir}/authorized_keys"
  keys_tmp="$(mktemp)"

  if [[ -n "$AUTHORIZED_KEY_FILE" ]]; then
    sed '/^[[:space:]]*$/d' "$AUTHORIZED_KEY_FILE" >>"$keys_tmp"
  fi

  if [[ -n "$AUTHORIZED_KEY_VALUE" ]]; then
    printf '%s\n' "$AUTHORIZED_KEY_VALUE" >>"$keys_tmp"
  fi

  if [[ ! -s "$keys_tmp" ]]; then
    rm -f "$keys_tmp"
    printf 'No public keys found to install.\n' >&2
    exit 1
  fi

  if ! ssh-keygen -l -f "$keys_tmp" >/dev/null 2>&1; then
    rm -f "$keys_tmp"
    printf 'Invalid public key input. Expected OpenSSH public key line(s).\n' >&2
    exit 1
  fi

  log "Installing public key(s) for ${RUN_USER}"
  run_root install -d -m 0700 -o "$RUN_USER" -g "$group" "$ssh_dir"
  run_root touch "$authorized_keys"
  run_root chown "$RUN_USER:$group" "$authorized_keys"
  run_root chmod 0600 "$authorized_keys"

  tmp="$(mktemp)"
  while IFS= read -r key; do
    [[ -z "$key" ]] && continue
    if run_root grep -qxF "$key" "$authorized_keys"; then
      log "Key already present for ${RUN_USER}"
    else
      printf '%s\n' "$key" >>"$tmp"
    fi
  done <"$keys_tmp"

  if [[ -s "$tmp" ]]; then
    root_shell "cat '$tmp' >> '$authorized_keys'"
    run_root chown "$RUN_USER:$group" "$authorized_keys"
    run_root chmod 0600 "$authorized_keys"
  fi

  rm -f "$tmp" "$keys_tmp"
}

assert_key_before_password_disable() {
  if [[ "$PASSWORD_AUTH" != "no" ]]; then
    return
  fi

  local home authorized_keys
  home="$(getent passwd "$RUN_USER" | cut -d: -f6)"
  authorized_keys="${home}/.ssh/authorized_keys"

  if ! run_root test -s "$authorized_keys"; then
    cat >&2 <<EOF
Refusing to disable password auth because ${authorized_keys} is missing or empty.

First install a key:
  ./infra/scripts/setup-ubuntu-ssh-server.sh --authorized-key-file ~/.ssh/id_ed25519.pub

Then verify key login from another terminal, and rerun:
  ./infra/scripts/setup-ubuntu-ssh-server.sh --disable-password
EOF
    exit 1
  fi
}

configure_ufw() {
  if [[ "$ALLOW_UFW" != true ]]; then
    log "Skipping ufw changes"
    return
  fi

  if ! command -v ufw >/dev/null 2>&1; then
    log "ufw is not installed; skipping firewall rule"
    return
  fi

  log "Allowing SSH port ${SSH_PORT}/tcp in ufw"
  run_root ufw allow "${SSH_PORT}/tcp" comment 'my-forever-music SSH'

  if [[ "$ENABLE_UFW" == true ]]; then
    log "Enabling ufw"
    run_root ufw --force enable
  fi
}

validate_and_restart_ssh() {
  ensure_sshd_runtime_dir

  log "Validating sshd configuration"
  run_root sshd -t

  log "Reloading systemd units"
  run_root systemctl daemon-reload

  if unit_exists ssh.socket && unit_active ssh.socket; then
    log "Restarting ssh.socket"
    run_root systemctl restart ssh.socket
    run_root systemctl reset-failed ssh.service || true
  elif unit_exists ssh.service; then
    log "Enabling and restarting ssh.service"
    run_root systemctl enable ssh.service
    run_root systemctl restart ssh.service
  else
    printf 'Neither ssh.socket nor ssh.service exists after openssh-server install.\n' >&2
    exit 1
  fi
}

show_status() {
  cat <<EOF

[ssh-setup] Current SSH status
EOF
  systemctl status ssh.socket --no-pager -l 2>/dev/null || true
  systemctl status ssh.service --no-pager -l 2>/dev/null || true

  printf '\n[ssh-setup] Listening ports\n'
  ss -tlnp 2>/dev/null | grep -E ":(22|${SSH_PORT})\\b" || true

  printf '\n[ssh-setup] Effective sshd config\n'
  ensure_sshd_runtime_dir
  run_root sshd -T | grep -E '^(port|passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication|permitrootlogin|usepam|x11forwarding|clientaliveinterval|clientalivecountmax|authorizedkeysfile)\b' || true

  if command -v ufw >/dev/null 2>&1; then
    printf '\n[ssh-setup] ufw status\n'
    run_root ufw status || true
  fi
}

validate_inputs

if [[ "$CHECK_ONLY" == true ]]; then
  show_status
  exit 0
fi

install_openssh_server
append_authorized_keys
assert_key_before_password_disable
prepare_rollback
trap rollback_on_error ERR
ensure_sshd_include_dir
write_sshd_dropin
configure_ssh_socket_port
validate_and_restart_ssh
cleanup_rollback
trap - ERR
configure_ufw
show_status

cat <<EOF

[ssh-setup] Done.

Test from another machine/terminal before closing the current session:
  ssh -p ${SSH_PORT} ${RUN_USER}@<server-ip-or-domain>
EOF
