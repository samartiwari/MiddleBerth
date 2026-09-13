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

# Per-request data, only when asked for: METRICS_DIR=/some/folder.
#
# The summary k6 prints is averaged over the whole run, which hides the burst —
# the second that matters is the busiest one, and only per-request timestamps can
# find it. Written as the calling user, because the k6 image runs as a user of its
# own that cannot write into this folder. And the folder must live under $HOME:
# snap Docker cannot see /tmp at all, and a mount it cannot see is silently empty.
K6_MOUNT=()
K6_OUT=()
if [ -n "${METRICS_DIR:-}" ]; then
    mkdir -p "$METRICS_DIR"
    # Always absolute. Docker reads a relative path after -v as the NAME of a
    # volume, refuses the slashes in it, and k6 never starts — which is exactly
    # what happened the first time this was run with OUT=loadtest/results/...
    METRICS_DIR="$(cd "$METRICS_DIR" && pwd)"

    # Snap Docker cannot see outside $HOME. The mount does not fail; it quietly
    # gives k6 an empty folder of its own, the data lands nowhere, and every
    # per-second count reads zero as if nothing had happened.
    if [ "$(readlink -f "$(command -v docker)")" = /usr/bin/snap ] && [ "${METRICS_DIR#"$HOME"/}" = "$METRICS_DIR" ]; then
        echo "refusing to run: METRICS_DIR=$METRICS_DIR is outside \$HOME, and snap Docker cannot see it" >&2
        exit 1
    fi

    K6_MOUNT=(-v "$METRICS_DIR:/out" --user "$(id -u):$(id -g)")
    K6_OUT=(--out "csv=/out/metrics-$USERS.csv.gz")
fi

echo "== $USERS users, all at once, travel date $DATE, browsing ${BROWSE:-1}"
K6_EXIT=0
docker run --rm -i --network "$K6_NET" \
    -v "$PWD/loadtest:/loadtest:ro" \
    ${K6_MOUNT[@]+"${K6_MOUNT[@]}"} \
    -e BASE_URL="$BASE" \
    -e TRAVEL_DATE="$DATE" \
    -e VUS="$USERS" \
    -e BROWSE="${BROWSE:-1}" \
    -e PAY_PERCENT="${PAY_PERCENT:-50}" \
    -e RAZORPAY_WEBHOOK_SECRET="${RAZORPAY_WEBHOOK_SECRET:-dev-only-webhook-secret-not-for-real-use}" \
    -e POLL_INTERVAL="${POLL_INTERVAL:-0.5}" \
    -e RUN_ID="$(date +%H%M%S)" \
    grafana/k6:latest run ${K6_OUT[@]+"${K6_OUT[@]}"} /loadtest/tatkal.js || K6_EXIT=$?

# 125 and above is Docker failing to start the container at all — k6 never ran.
# Checking the database now would print "0 double bookings" for a test that did
# not happen, which reads exactly like a pass. So stop, and say so.
if [ "$K6_EXIT" -ge 125 ]; then
    echo >&2
    echo "!! the load generator never started (docker exit $K6_EXIT) — nothing was tested" >&2
    exit "$K6_EXIT"
fi

echo
echo "== now the part that matters"
psql_booking -f - < loadtest/verify.sql

# k6 exits 99 when a threshold is crossed. Report it, but only after the database
# has been checked — a slow system is a result, a double booking is a disaster.
exit "$K6_EXIT"
