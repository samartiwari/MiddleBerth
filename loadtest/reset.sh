#!/usr/bin/env bash
# Empty the booking data and lay out fresh berths, so a run starts from 10:00:00
# rather than from whatever the last run left behind.
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose exec -T booking-db psql -U middleberth -d booking -q \
    -c "TRUNCATE booking, seat, quota_counter, outbox, train CASCADE;"
docker compose exec -T booking-db psql -U middleberth -d booking -q -f - < deploy/seed-load.sql
