#!/usr/bin/env bash
set -euo pipefail

DOMAIN="${DOMAIN:-imapplepie20.tplinkdns.com}"
LOCAL_RESOLVE_IP="${LOCAL_RESOLVE_IP:-127.0.0.1}"

failures=0

section() {
  printf '\n== %s ==\n' "$1"
}

warn() {
  failures=$((failures + 1))
  printf '[warn] %s\n' "$1"
}

ok() {
  printf '[ok] %s\n' "$1"
}

section "Domain"
if getent ahosts "$DOMAIN"; then
  ok "DNS resolved for ${DOMAIN}"
else
  warn "DNS did not resolve for ${DOMAIN}"
fi

section "Local Addresses"
hostname -I || true
ip -br addr || true

section "Port Listeners"
ss -ltnp '( sport = :80 or sport = :443 )' || true

section "Local Nginx Through Domain Host Header"
if curl -fsSI --resolve "${DOMAIN}:80:${LOCAL_RESOLVE_IP}" "http://${DOMAIN}/" --max-time 10; then
  ok "local HTTP responds through ${LOCAL_RESOLVE_IP}:80"
else
  warn "local HTTP does not respond through ${LOCAL_RESOLVE_IP}:80"
fi

if curl -kfsSI --resolve "${DOMAIN}:443:${LOCAL_RESOLVE_IP}" "https://${DOMAIN}/" --max-time 10; then
  ok "local HTTPS responds through ${LOCAL_RESOLVE_IP}:443"
else
  warn "local HTTPS does not respond through ${LOCAL_RESOLVE_IP}:443"
fi

section "Public Domain Response"
if curl -fsSI "http://${DOMAIN}/" --max-time 10; then
  ok "public HTTP responds"
else
  warn "public HTTP does not respond"
fi

if curl -kfsSI "https://${DOMAIN}/" --max-time 10; then
  ok "public HTTPS responds"
else
  warn "public HTTPS does not respond"
fi

section "Certbot"
if command -v certbot >/dev/null 2>&1; then
  certbot --version
  ok "certbot is installed"
else
  warn "certbot is not installed"
fi

section "Let's Encrypt Files"
if [[ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" && -f "/etc/letsencrypt/live/${DOMAIN}/privkey.pem" ]]; then
  ls -l "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" "/etc/letsencrypt/live/${DOMAIN}/privkey.pem"
  ok "certificate files exist for ${DOMAIN}"
else
  warn "certificate files are missing for ${DOMAIN}"
fi

section "Summary"
if [[ "$failures" -eq 0 ]]; then
  ok "HTTPS readiness checks passed"
else
  warn "${failures} readiness check(s) need attention before HTTPS cutover"
  exit 1
fi
