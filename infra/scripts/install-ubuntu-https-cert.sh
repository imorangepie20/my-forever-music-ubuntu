#!/usr/bin/env bash
set -euo pipefail

DOMAIN="imapplepie20.tplinkdns.com"
EMAIL=""
STAGING=false

usage() {
  cat <<'USAGE'
Usage: ./infra/scripts/install-ubuntu-https-cert.sh --email EMAIL [options]

Options:
  --domain DOMAIN   Domain to issue the certificate for.
                    Default: imapplepie20.tplinkdns.com
  --email EMAIL     Let's Encrypt account email. Required.
  --staging         Use Let's Encrypt staging endpoint for a dry run.
  -h, --help        Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)
      DOMAIN="${2:?--domain requires a value}"
      shift 2
      ;;
    --email)
      EMAIL="${2:?--email requires a value}"
      shift 2
      ;;
    --staging)
      STAGING=true
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

if [[ -z "$EMAIL" ]]; then
  printf 'Missing required --email.\n\n' >&2
  usage >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HTTP_NGINX_CONF="$REPO_ROOT/infra/nginx/ubuntu.server.dev.conf"
HTTPS_NGINX_CONF="$REPO_ROOT/infra/nginx/ubuntu.server.dev.https.conf"
ESCAPED_DOMAIN="${DOMAIN//\//\\/}"
TMP_HTTPS_CONF="$(mktemp)"

cleanup() {
  rm -f "$TMP_HTTPS_CONF"
}
trap cleanup EXIT

printf '[step] Installing certbot packages\n'
sudo apt update
sudo apt install -y certbot python3-certbot-nginx

printf '[step] Preparing ACME webroot\n'
sudo install -d -m 0755 /var/www/certbot

printf '[step] Ensuring HTTP nginx config is active for webroot challenge\n'
sudo cp /etc/nginx/nginx.conf "/etc/nginx/nginx.conf.bak.$(date +%F-%H%M%S)"
sudo cp "$HTTP_NGINX_CONF" /etc/nginx/nginx.conf
sudo nginx -t
sudo systemctl reload nginx

certbot_args=(
  certonly
  --webroot
  -w /var/www/certbot
  -d "$DOMAIN"
  --email "$EMAIL"
  --agree-tos
  --non-interactive
  --keep-until-expiring
)

if [[ "$STAGING" == true ]]; then
  certbot_args+=(--staging)
fi

printf '[step] Requesting certificate for %s\n' "$DOMAIN"
sudo certbot "${certbot_args[@]}"

printf '[step] Applying HTTPS nginx config\n'
sed "s/imapplepie20\\.tplinkdns\\.com/${ESCAPED_DOMAIN}/g" "$HTTPS_NGINX_CONF" > "$TMP_HTTPS_CONF"
sudo cp /etc/nginx/nginx.conf "/etc/nginx/nginx.conf.bak.$(date +%F-%H%M%S)"
sudo install -m 0644 "$TMP_HTTPS_CONF" /etc/nginx/nginx.conf
sudo nginx -t
sudo systemctl reload nginx

printf '[step] Enabling certificate renewal timer\n'
sudo systemctl enable --now certbot.timer

printf '[ok] HTTPS is configured for %s\n' "$DOMAIN"
printf 'Check: curl -I https://%s/\n' "$DOMAIN"
