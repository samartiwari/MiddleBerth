#!/usr/bin/env bash
# Wait until the system is actually usable: the front door answers, and the seed
# job has finished putting berths in the database.
#
#   deploy/wait-ready.sh [base-url]
#
# "docker compose up -d" returns as soon as the containers START, which is well
# before Spring has finished booting and before the one-shot seeder has run. And
# "up --wait" is no good here either: it treats the seeder exiting 0 as a failure.

set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-http://localhost:${HTTP_PORT:-80}}"
TRIES="${TRIES:-90}"

printf 'waiting for the front door at %s ' "$BASE"
for _ in $(seq 1 "$TRIES"); do
    if curl -fsS "$BASE/api/trains" >/dev/null 2>&1; then
        echo "ok"
        break
    fi
    printf '.'
    sleep 2
done
if ! curl -fsS "$BASE/api/trains" >/dev/null 2>&1; then
    echo
    echo "the front door is not answering. If the gateway was just recreated, nginx"
    echo "is probably still dialling its old address — it resolves the name once, at"
    echo "startup. Fix:  docker compose restart nginx"
    exit 1
fi

# The line above only proves search is alive. Check the BOOKING path as well: a
# restarted booking-service leaves the gateway answering 5xx for a while, and a
# load test started in that window has every request fail at once. Learned the
# hard way, with 2,000 requests failing in the first second.
printf 'waiting for the booking path '
token=$(curl -fsS -X POST "$BASE/auth/token" -H 'Content-Type: application/json' \
        -d '{"userId":1}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
for _ in $(seq 1 "$TRIES"); do
    if curl -fsS "$BASE/api/bookings/READYCHECK" -H "Authorization: Bearer $token" >/dev/null 2>&1; then
        echo "ok"
        break
    fi
    printf '.'
    sleep 2
done
curl -fsS "$BASE/api/bookings/READYCHECK" -H "Authorization: Bearer $token" >/dev/null

# Wait for the SEEDER to finish, not merely for berths to appear. It seeds the
# berths first and the passenger second, so "berths exist" is not "seeding done" —
# a booking made in that gap gets confirmed with nobody to mail, and the mail is
# never sent. Found exactly that way.
printf 'waiting for the seeder to finish '
for _ in $(seq 1 "$TRIES"); do
    container=$(docker compose ps -aq seed 2>/dev/null | head -1)
    if [ -n "$container" ]; then
        read -r status code <<<"$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$container" 2>/dev/null || echo "unknown 1")"
        if [ "$status" = "exited" ] && [ "$code" = "0" ]; then
            berths=$(docker compose exec -T booking-db psql -U middleberth -d booking -tAc \
                     "SELECT count(*) FROM seat" 2>/dev/null | tr -d '[:space:]' || true)
            echo "ok (${berths:-?} berths)"
            exit 0
        fi
        if [ "$status" = "exited" ] && [ "$code" != "0" ]; then
            echo "the seeder failed (exit $code)"
            docker compose logs seed --tail 20
            exit 1
        fi
    fi
    printf '.'
    sleep 2
done

echo "the seeder never finished"
docker compose logs seed --tail 20
exit 1
