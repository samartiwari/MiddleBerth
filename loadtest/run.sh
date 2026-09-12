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
NETWORK="${NETWORK:-middleberth_default}"

echo "== berths available before the run"
docker compose exec -T booking-db psql -U middleberth -d booking -q \
    -c "SELECT status, count(*) FROM seat WHERE travel_date = CURRENT_DATE + 1 GROUP BY status;"

echo "== $USERS users, all at once, travel date $DATE"
K6_EXIT=0
docker run --rm -i --network "$NETWORK" \
    -v "$PWD/loadtest:/loadtest:ro" \
    -e BASE_URL=http://nginx:80 \
    -e TRAVEL_DATE="$DATE" \
    -e VUS="$USERS" \
    -e PAY_PERCENT="${PAY_PERCENT:-50}" \
    -e RAZORPAY_WEBHOOK_SECRET="${RAZORPAY_WEBHOOK_SECRET:-dev-only-webhook-secret-not-for-real-use}" \
    -e POLL_INTERVAL="${POLL_INTERVAL:-0.5}" \
    -e RUN_ID="$(date +%H%M%S)" \
    grafana/k6:latest run /loadtest/tatkal.js || K6_EXIT=$?

echo
echo "== now the part that matters"
docker compose exec -T booking-db psql -U middleberth -d booking -q -f - < loadtest/verify.sql

# k6 exits 99 when a threshold is crossed. Report it, but only after the database
# has been checked — a slow system is a result, a double booking is a disaster.
exit "$K6_EXIT"
