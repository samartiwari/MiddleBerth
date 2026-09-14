#!/usr/bin/env bash
# Takes a fresh Ubuntu 24.04 server to MiddleBerth running over HTTPS.
#
#   On the server, the first time:
#     curl -fsSL https://raw.githubusercontent.com/samartiwari/MiddleBerth/main/deploy/setup-server.sh | bash
#   Every time after that:
#     bash ~/MiddleBerth/deploy/setup-server.sh
#
# Safe to run again. Every step checks whether it is already done and skips itself,
# so the same command sets up a new server, finishes a half-done one, or updates a
# running one to the latest code.
#
# It stops once with a clear message if .env has not been filled in yet: secrets are
# typed on the server, never kept in the repository or passed around.

set -euo pipefail

REPO=https://github.com/samartiwari/MiddleBerth.git
DIR="$HOME/MiddleBerth"
# The server's compose is the laptop's plus deploy/compose.vm.yml. Written out once,
# because the certificate renewal hooks below need the very same command.
COMPOSE_CMD="docker compose --project-directory $DIR -f $DIR/docker-compose.yml -f $DIR/deploy/compose.vm.yml"

step() { printf '\n== %s\n' "$1"; }
fail() { printf '\n!! %s\n' "$1" >&2; exit 1; }
compose() { sudo $COMPOSE_CMD "$@"; }

step "1 of 7  firewall: open 80 and 443 inside Ubuntu"
# Oracle's Ubuntu images reject everything but SSH in iptables, on top of the cloud
# firewall. Saved BEFORE Docker is installed, so Docker's own rules are not frozen
# into the saved set and duplicated on every reboot.
if sudo iptables -C INPUT -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW -j ACCEPT 2>/dev/null; then
    echo "already open"
else
    sudo iptables -I INPUT 1 -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW -j ACCEPT
    sudo netfilter-persistent save
    echo "opened and saved"
fi

step "2 of 7  Docker, with its log files capped"
if command -v docker >/dev/null; then
    echo "already installed: $(docker --version)"
else
    curl -fsSL https://get.docker.com | sudo sh
fi
sudo usermod -aG docker "$USER"
# nginx writes a line per request. Without a cap, container logs grow until the disk
# is full, and a full disk takes everything down with it.
if [ -f /etc/docker/daemon.json ]; then
    echo "log cap already set"
else
    echo '{"log-driver":"json-file","log-opts":{"max-size":"20m","max-file":"3"}}' | sudo tee /etc/docker/daemon.json >/dev/null
    sudo systemctl restart docker
    echo "logs capped at 3 x 20 MB per container"
fi

step "3 of 7  the code"
command -v git >/dev/null || sudo apt-get install -y git
if [ -d "$DIR/.git" ]; then
    git -C "$DIR" pull --ff-only
else
    git clone "$REPO" "$DIR"
fi

step "4 of 7  secrets"
cd "$DIR"
if [ ! -f .env ]; then
    cp deploy/env.vm.example .env
    chmod 600 .env
    fail "Created $DIR/.env from the example. Fill it in (nano $DIR/.env), then run this script again."
fi
chmod 600 .env
set -a; . ./.env; set +a

[ -n "${DOMAIN:-}" ]     || fail "DOMAIN is empty in .env"
[ -n "${CERT_EMAIL:-}" ] || fail "CERT_EMAIL is empty in .env"
[ "${#JWT_SECRET}" -ge 32 ] && [ "$JWT_SECRET" != "dev-only-secret-change-me-it-must-be-32-bytes-or-more" ] \
    || fail "JWT_SECRET must be at least 32 characters and not the laptop default: openssl rand -hex 32"
[ "${#POSTGRES_PASSWORD}" -ge 16 ] && [ "$POSTGRES_PASSWORD" != "middleberth" ] \
    || fail "POSTGRES_PASSWORD must be at least 16 characters and not the laptop default: openssl rand -hex 24"
[ "${AUTH_DEMO_TOKENS:-false}" = "false" ] || fail "AUTH_DEMO_TOKENS must be false on a public server"
if [ "${RAZORPAY_MODE:-stub}" = "live" ]; then
    [ -n "${RAZORPAY_KEY_ID:-}" ] && [ -n "${RAZORPAY_KEY_SECRET:-}" ] && [ "${#RAZORPAY_WEBHOOK_SECRET}" -ge 16 ] \
        || fail "RAZORPAY_MODE=live needs RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and a RAZORPAY_WEBHOOK_SECRET of 16+ characters"
fi
echo "all present"

step "5 of 7  HTTPS certificate for $DOMAIN"
command -v certbot >/dev/null || sudo apt-get install -y certbot
if sudo test -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem"; then
    echo "already issued; certbot renews it on its own"
else
    # Let's Encrypt checks the name points here before it issues anything, and too
    # many failed attempts lock the domain out for an hour. Check first.
    here=$(curl -fsS --max-time 10 https://api.ipify.org || true)
    there=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1 {print $1}')
    [ -n "$there" ] || fail "$DOMAIN does not resolve yet. Add its A record ($here), wait a few minutes, run again."
    [ -z "$here" ] || [ "$here" = "$there" ] || fail "$DOMAIN points to $there, but this server is $here. Fix the A record."
    # Port 80 must be free for certbot's own short-lived web server.
    compose stop nginx 2>/dev/null || true
    sudo certbot certonly --standalone --non-interactive --agree-tos -m "$CERT_EMAIL" -d "$DOMAIN"
fi
# Renewals need port 80 too, so nginx steps aside for the few seconds they take.
# certbot renews as root, so the hooks call docker directly.
sudo mkdir -p /etc/letsencrypt/renewal-hooks/pre /etc/letsencrypt/renewal-hooks/post
printf '#!/bin/sh\n%s stop nginx\n'  "$COMPOSE_CMD" | sudo tee /etc/letsencrypt/renewal-hooks/pre/middleberth-nginx-stop.sh  >/dev/null
printf '#!/bin/sh\n%s start nginx\n' "$COMPOSE_CMD" | sudo tee /etc/letsencrypt/renewal-hooks/post/middleberth-nginx-start.sh >/dev/null
sudo chmod +x /etc/letsencrypt/renewal-hooks/pre/middleberth-nginx-stop.sh /etc/letsencrypt/renewal-hooks/post/middleberth-nginx-start.sh

step "6 of 7  build and start (the first build takes 20 to 30 minutes on 2 ARM cores)"
compose up -d --build

step "7 of 7  waiting for https://$DOMAIN to answer"
# Asked of this machine directly, under the real name, so the certificate is still
# checked but the answer does not depend on the route out to the internet and back.
for _ in $(seq 1 60); do
    if curl -fsS --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/actuator/health" 2>/dev/null | grep -q UP; then
        printf '\nMiddleBerth is live at https://%s\n' "$DOMAIN"
        printf 'Log out and back in once (for the docker group), then check a whole booking:\n'
        printf '  cd %s && bash deploy/smoke.sh https://%s\n' "$DIR" "$DOMAIN"
        exit 0
    fi
    sleep 10
done
fail "Started, but https://$DOMAIN did not answer within 10 minutes. See: sudo $COMPOSE_CMD ps"
