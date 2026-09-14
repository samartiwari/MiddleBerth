#!/usr/bin/env bash
# Empty the booking data and lay out fresh berths, so a run starts from 10:00:00
# rather than from whatever the last run left behind.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${MODE:-compose}"
export PATH="$HOME/.local/bin:$PATH"

if [ "$MODE" = "k8s" ]; then
    psql_booking() { kubectl -n middleberth exec -i deploy/booking-db -- psql -U middleberth -d booking -q "$@"; }
else
    psql_booking() { docker compose exec -T booking-db psql -U middleberth -d booking -q "$@"; }
fi

# Which berths. The default is the spike's: five trains, 480 berths, gone in a
# second. loadtest/steady.sh uses SEED=deploy/seed-throughput.sql, 300 trains that
# a minute of steady booking cannot run out of.
SEED="${SEED:-deploy/seed-load.sql}"

psql_booking -c "TRUNCATE booking, seat, quota_counter, outbox, train, train_quota CASCADE;"
psql_booking -f - < "$SEED"
