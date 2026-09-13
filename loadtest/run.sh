#!/usr/bin/env bash
# The tatkal spike, against a running docker compose.
#
#   loadtest/run.sh [users]        default 1000
#
# k6 runs in a container on the same network and hits nginx by name, so the test
# goes through the whole system: nginx, gateway, Kafka, Postgres, Redis.
#
# This is the laptop number. Nothing here is tuned, nothing is skipped, and the
# payment gateway is the stub — Razorpay test mode would rate limit us and we
# would be measuring them.

set -euo pipefail
cd "$(dirname "$0")/.."

USERS="${1:-1000}"
DATE=$(date -u -d "+1 day" +%Y-%m-%d)

# Where to run against: compose (default) or the kind cluster.
#
#   MODE=k8s loadtest/run.sh 2000
#
# The two differ in only two ways: how to reach the front door, and how to reach
# the database for the correctness check.
MODE="${MODE:-compose}"
export PATH="$HOME/.local/bin:$PATH"

if [ "$MODE" = "k8s" ]; then
    # kind maps the nginx node port to localhost:8088, so k6 needs the host's own
    # network to see it.
    K6_NET=host
    BASE=http://localhost:8088
    psql_booking() { kubectl -n middleberth exec -i deploy/booking-db -- psql -U middleberth -d booking -q "$@"; }
else
    K6_NET=middleberth_default
    BASE=http://nginx:80
    psql_booking() { docker compose exec -T booking-db psql -U middleberth -d booking -q "$@"; }
fi

# Refuse to measure the wrong thing, or to do real damage doing it.
#
# The comment at the top has always said "the payment gateway is the stub", and a
# comment has never once stopped anybody. Two things can be badly wrong here:
#
#   live payments  Razorpay rate limits their own API, so the numbers would be
#                  theirs and not ours.
#   real mail      a few thousand tickets through a mailbox that allows 500 a day
#                  is a load test aimed at a stranger's mail server. Most would
#                  fail, retry, and fill the dead letter topic with noise.
#
# So ask the running services what they actually are, and stop if either is real.
if [ "$MODE" != "k8s" ]; then
    PAY_MODE=$(docker compose exec -T payment-service sh -c 'echo $MIDDLEBERTH_RAZORPAY_MODE' 2>/dev/null | tr -d '\r')
    MAILS=$(docker compose exec -T notification-service sh -c 'echo $MAIL_MODE' 2>/dev/null | tr -d '\r')

    if [ "$PAY_MODE" = "live" ] || [ "$MAILS" = "smtp" ]; then
        echo "refusing to run: payments=${PAY_MODE:-unknown}, mail=${MAILS:-unknown}" >&2
        echo >&2
        echo "A load test must not reach a real payment gateway or a real mailbox." >&2
        echo "In .env set RAZORPAY_MODE=stub and MAIL_MODE=log, then:" >&2
        echo "    docker compose up -d payment-service notification-service" >&2
        exit 1
    fi
    echo "== payments: $PAY_MODE, mail: $MAILS"
fi

echo "== berths available before the run"
psql_booking -c "SELECT status, count(*) FROM seat WHERE travel_date = CURRENT_DATE + 1 GROUP BY status;"

echo "== $USERS users, all at once, travel date $DATE"
K6_EXIT=0
docker run --rm -i --network "$K6_NET" \
    -v "$PWD/loadtest:/loadtest:ro" \
    -e BASE_URL="$BASE" \
    -e TRAVEL_DATE="$DATE" \
    -e VUS="$USERS" \
    -e PAY_PERCENT="${PAY_PERCENT:-50}" \
    -e RAZORPAY_WEBHOOK_SECRET="${RAZORPAY_WEBHOOK_SECRET:-dev-only-webhook-secret-not-for-real-use}" \
    -e POLL_INTERVAL="${POLL_INTERVAL:-0.5}" \
    -e RUN_ID="$(date +%H%M%S)" \
    grafana/k6:latest run /loadtest/tatkal.js || K6_EXIT=$?

echo
echo "== now the part that matters"
psql_booking -f - < loadtest/verify.sql

# k6 exits 99 when a threshold is crossed. Report it, but only after the database
# has been checked — a slow system is a result, a double booking is a disaster.
exit "$K6_EXIT"
