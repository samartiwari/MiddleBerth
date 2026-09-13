#!/usr/bin/env bash
# Find the knee: the load at which the front door stops meeting its latency target.
#
#   loadtest/ladder.sh                        100 250 500 750 1000 1500 2000
#   loadtest/ladder.sh 500 1000 2000          just those
#   BROWSE=0 loadtest/ladder.sh 1000 2000     bookers only, no searching
#
# ONE run tells you nothing about capacity. "It handled 2,000 users" is not a
# number until you say at what latency, because a system that answers everybody
# in nine seconds has technically handled them. Capacity is the load at which the
# target still holds, so the only way to find it is to climb until it breaks.
#
# Things this does that a single run cannot:
#
#   A THROWAWAY RUN FIRST. The services stay up between levels, so each one runs
#   on a hotter JVM than the last. Measured cold-first, latency appears to IMPROVE
#   as load rises, which is nonsense and was actually observed: 110ms at 100 users
#   against 80ms at 500. The first run is discarded for that reason.
#
#   CPU SAMPLED THROUGHOUT. This is what says whether a number belongs to the
#   application or to the machine. Pegged at ~100% means the box ran out, and a
#   bigger box would go further. Plenty of headroom while latency climbs anyway
#   means something inside is the limit — a pool, a lock, a partition count — and
#   that is the one worth chasing. Look at how LONG it stays high, not just the
#   peak: one busy second is a burst, a minute of it is a ceiling.
#
#   THE BUSIEST SECOND, NOT THE AVERAGE. Requests per second averaged over a run
#   hides the burst entirely, because the quiet tail pulls it down. So booking
#   requests are counted per second from k6's own per-request log, and berths are
#   counted per second from the database, and the peak of each is reported.
#
# The load generator runs on this same machine and competes with what it measures,
# which no amount of tuning fixes. Say so next to any number this produces.

set -uo pipefail
cd "$(dirname "$0")/.."

LEVELS=("$@")
[ ${#LEVELS[@]} -eq 0 ] && LEVELS=(100 250 500 750 1000 1500 2000)

# Inside the project, where it can be seen and deleted — and because the k6
# container writes into it, and snap Docker cannot see /tmp.
OUT="${OUT:-$PWD/loadtest/results}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"          # absolute: a relative path breaks the docker mount
export METRICS_DIR="$OUT"
export BROWSE="${BROWSE:-1}"

strip() { sed 's/\x1b\[[0-9;]*m//g'; }

# Did k6 actually run? Its summary always reports http_reqs; a run that never
# started does not. Without this check a failed run still produced a full table
# ending in "(0 rows)" — the database check passing trivially on an empty table,
# which looks precisely like "no double bookings".
ran() { grep -aq "http_reqs" "$1"; }
why() { strip <"$1" | grep -aiE "error|refusing|never started|invalid|denied|cannot" | head -3 | sed 's/^/     /'; }

pick() {   # pick <file> <metric line> <stat>
    grep -a "$2" "$1" | strip | grep -oE "$3=[^ ]+" | head -1 | cut -d= -f2-
}

sql() {
    docker compose exec -T booking-db psql -U middleberth -d booking -At -c "$1" 2>/dev/null | tr -d '\r'
}

# Booking requests in the busiest single second, from k6's per-request output.
book_peak() {
    gzip -dcf "$1" 2>/dev/null | awk -F, '
        NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }
        $(col["metric_name"]) == "http_reqs" && $(col["name"]) == "book" { n[int($(col["timestamp"]))]++ }
        END { m = 0; for (s in n) if (n[s] > m) m = n[s]; print m }'
}

echo "== warming up (discarded — the JVMs are cold and would flatter the first level)"
bash loadtest/reset.sh >/dev/null 2>&1
bash loadtest/run.sh 200 >"$OUT/warmup.log" 2>&1
if ! ran "$OUT/warmup.log"; then
    echo "!! the warm-up never ran, so no level after it would either. From $OUT/warmup.log:"
    why "$OUT/warmup.log"
    exit 1
fi
echo "   done"
echo

printf '%6s | %-9s %-9s | %-10s %-8s | %-8s %-8s | %-10s | %-4s | %s\n' \
    users "intake95" "p99" "decide95" "last" "book/s" "claim/s" "search99" cpu double-booked
printf -- '-------+---------------------+---------------------+-------------------+------------+------+--------------\n'

for USERS in "${LEVELS[@]}"; do
    bash loadtest/reset.sh >/dev/null 2>&1

    # Straight to a file. Through a pipe, killing it loses the buffer and reports
    # a confident, meaningless 0%.
    vmstat -n 1 300 >"$OUT/cpu-$USERS.txt" 2>&1 &
    VM=$!

    bash loadtest/run.sh "$USERS" >"$OUT/run-$USERS.log" 2>&1
    kill $VM 2>/dev/null
    sleep 1

    LOG="$OUT/run-$USERS.log"
    if ! ran "$LOG"; then
        printf '%6s | k6 did not run — NOTHING WAS TESTED at this level. From %s:\n' "$USERS" "$LOG"
        why "$LOG"
        exit 1
    fi

    BOOK=$(pick "$LOG" "{ name:book }" 'p\(95\)')
    B99=$(pick "$LOG" "{ name:book }" 'p\(99\)')
    DEC=$(pick "$LOG" "booking_decision_ms" 'p\(95\)')
    LAST=$(pick "$LOG" "booking_decision_ms" 'max')
    if [ "$BROWSE" = "0" ]; then SRCH="-"; else SRCH=$(pick "$LOG" "{ name:search }" 'p\(99\)'); fi
    DOUBLE=$(sed -n '/THE ONE THAT MATTERS/,/rows)/p' "$LOG" | grep -oE '\([0-9]+ rows?\)' | head -1)

    PEAK_BOOK=$(book_peak "$OUT/metrics-$USERS.csv.gz")

    # Berths and waitlist places handed out, per second, as the consumer wrote
    # them. Read before the next level resets the table.
    PEAK_CLAIM=$(sql "SELECT coalesce(max(c), 0) FROM (SELECT count(*) AS c FROM booking GROUP BY date_trunc('second', created_at)) s;")

    # Busiest second of the run: 100 minus the lowest idle reading vmstat saw.
    PEAK=$(awk 'NR>2 && $15 ~ /^[0-9]+$/ {if (min=="" || $15<min) min=$15}
                END {if (min=="") print "?"; else printf "%d%%", 100-min}' "$OUT/cpu-$USERS.txt")

    printf '%6s | %-9s %-9s | %-10s %-8s | %-8s %-8s | %-10s | %-4s | %s\n' \
        "$USERS" "${BOOK:-?}" "${B99:-?}" "${DEC:-?}" "${LAST:-?}" \
        "${PEAK_BOOK:-?}" "${PEAK_CLAIM:-?}" "${SRCH:-?}" "$PEAK" "${DOUBLE:-?}"
done

cat <<EOF

  intake95, p99   how long the 202 took — the front door alone
  decide95        click to knowing what you got — what a passenger actually feels
  last            the unluckiest person's wait — roughly when the queue ran dry
  book/s          booking requests that arrived in the busiest single second
  claim/s         berths and waitlist places the consumer handed out in its busiest
                  second. Regrets write no row, so they are not counted here.
  search99        only with browsing on (BROWSE=1)

Targets, from loadtest/tatkal.js: intake p95 < 500ms, p99 < 1s, search p99 < 500ms.
The knee is the first row that crosses one of them.

"double-booked (0 rows)" is the claim this whole project exists to support. It is
checked against the database after every level, not assumed — and it must read 0
on every row, including the ones where latency has already fallen apart. Being
slow is a result. Giving one berth to two people is not.

Full k6 output, per-request data and CPU samples: $OUT
EOF
