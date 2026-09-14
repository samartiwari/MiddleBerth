#!/usr/bin/env bash
# How many bookings a second this keeps up with: not for one second, for a minute.
#
#   loadtest/steady.sh                      250 500 1000 1500 2000 4000
#   loadtest/steady.sh 500 1000             just those
#   DURATION=30s loadtest/steady.sh 1000    shorter runs
#
# ladder.sh throws everybody at the door at once. That is the tatkal spike, and it
# is over in about two seconds: far too short to tell a system that keeps up from
# one quietly building a queue. Here people arrive at a fixed rate for a whole
# minute, against 300 trains with berths to spare (deploy/seed-throughput.sql),
# and each row says whether the answers kept pace.
#
# Keeping up, on one row:
#   decided/s   close to the rate asked for (the median second, counted in the database)
#   decide95    about the same over the last 10 seconds as over the first 10
#   dropped     0: k6 could start every attempt on time
#
# Falling behind: decided/s stuck under the rate, and decide95 over the last 10
# seconds far above the first 10, because every second adds more waiting work
# than it clears. That queue is the thing a one-second peak can never show.
#
# k6 runs on this same machine, and the faster the rate the more CPU it takes away
# from what it measures. Say so next to any number this produces.

set -uo pipefail
cd "$(dirname "$0")/.."

RATES=("$@")
[ ${#RATES[@]} -eq 0 ] && RATES=(250 500 1000 1500 2000 4000)
DURATION="${DURATION:-60s}"
SECS="${DURATION%s}"
case "$SECS" in ''|*[!0-9]*) echo "DURATION must be whole seconds, like 60s" >&2; exit 1 ;; esac
[ "$SECS" -ge 30 ] || { echo "DURATION must be at least 30s: the first and last 10 seconds are compared" >&2; exit 1; }

# Inside the project, where it can be seen and deleted, and where snap Docker can
# see it. Absolute, because a relative path breaks the docker mount.
OUT="${OUT:-$PWD/loadtest/results/steady}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
export METRICS_DIR="$OUT" SHAPE=steady DURATION BROWSE=0
export SEED=deploy/seed-throughput.sql

strip() { sed 's/\x1b\[[0-9;]*m//g'; }
ran() { grep -aq "http_reqs" "$1"; }
why() { strip <"$1" | grep -aiE "error|refusing|never started|invalid|denied|cannot" | head -3 | sed 's/^/     /'; }

sql() {
    docker compose exec -T booking-db psql -U middleberth -d booking -At -c "$1" 2>/dev/null | tr -d '\r'
}

# Where every booking-requests partition ends, one "partition:offset" per line.
# Compared before and after a run, it says how many partitions (and so how many
# booking threads) were actually given work.
offsets() {
    docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh \
        --bootstrap-server localhost:9092 --topic booking-requests 2>/dev/null \
        | tr -d '\r' | awk -F: 'NF == 3 {print $2 ":" $3}'
}

# From k6's per-request file: attempts reaching the door per second (median), intake
# p95, decision p95 over the first and last 10 seconds, how long answers kept
# arriving after people stopped, attempts k6 could not start, and the first second.
analyse() {   # analyse <csv.gz> <seconds>
    gzip -dcf "$1" 2>/dev/null \
        | grep -aE '^(http_reqs|http_req_duration|booking_decision_ms|dropped_iterations),' \
        | python3 -c '
import sys, statistics
secs = int(sys.argv[1])
door, intake, decisions, dropped = [], [], [], 0.0
for line in sys.stdin:
    p = line.rstrip("\n").split(",")
    metric, ts, value, name = p[0], int(p[1]), float(p[2]), p[9]
    if metric == "http_reqs" and name == "book": door.append(ts)
    elif metric == "http_req_duration" and name == "book": intake.append(value)
    elif metric == "booking_decision_ms": decisions.append((ts, value))
    elif metric == "dropped_iterations": dropped += value
def p95(v):
    v = sorted(v)
    return round(v[min(len(v) - 1, int(0.95 * len(v)))]) if v else -1
if not door:
    print("? ? ? ? ? ? 0"); sys.exit()
t0 = min(door)
per = {}
for ts in door: per[ts] = per.get(ts, 0) + 1
middle = [per.get(s, 0) for s in range(t0 + 5, t0 + secs - 5)]
first = p95([v for ts, v in decisions if t0 <= ts < t0 + 10])
last = p95([v for ts, v in decisions if t0 + secs - 10 <= ts < t0 + secs])
drain = max((ts for ts, _ in decisions), default=t0) - (t0 + secs)
print(round(statistics.median(middle)), p95(intake), first, last, max(drain, 0), int(dropped), t0)
' "$2"
}

# Average CPU over the busiest stretch the length of the run, minus 10 seconds.
cpu_avg() {   # cpu_avg <vmstat file> <seconds>
    awk 'NR > 2 && $15 ~ /^[0-9]+$/ {print 100 - $15}' "$1" | sort -rn | head -n $(($2 - 10)) \
        | awk '{s += $1; n++} END {if (n) printf "%d%%", s / n; else print "?"}'
}

echo "== warming up (discarded): 100 a second for 30 seconds"
bash loadtest/reset.sh >/dev/null 2>&1
RATE=100 DURATION=30s METRICS_DIR="" bash loadtest/run.sh >"$OUT/warmup.log" 2>&1
if ! ran "$OUT/warmup.log"; then
    echo "!! the warm-up never ran, so no rate after it would either. From $OUT/warmup.log:"
    why "$OUT/warmup.log"
    exit 1
fi
echo "   done"
echo

printf '%6s | %-7s %-7s | %-8s | %-15s %-6s | %-9s | %-5s | %-4s | %-8s | %s\n' \
    rate "door/s" dropped intake95 "decide95 10s→" drain "decided/s" parts cpu "keeps up" double-booked
printf -- '-------+-----------------+----------+------------------------+-----------+-------+------+----------+--------------\n'

for RATE in "${RATES[@]}"; do
    bash loadtest/reset.sh >/dev/null 2>&1
    BEFORE="$(offsets)"

    # Straight to a file: through a pipe, killing it loses the buffer.
    vmstat -n 1 $((SECS + 150)) >"$OUT/cpu-$RATE.txt" 2>&1 &
    VM=$!
    RATE="$RATE" bash loadtest/run.sh >"$OUT/run-$RATE.log" 2>&1
    kill $VM 2>/dev/null

    LOG="$OUT/run-$RATE.log"
    if ! ran "$LOG"; then
        printf '%6s | k6 did not run: NOTHING WAS TESTED at this rate. From %s:\n' "$RATE" "$LOG"
        why "$LOG"
        exit 1
    fi

    AFTER="$(offsets)"
    PARTS=$(awk -F: 'NR == FNR {b[$1] = $2; next} $2 > b[$1] {n++} END {print n + 0}' \
        <(echo "$BEFORE") <(echo "$AFTER"))
    PARTS="$PARTS/$(echo "$AFTER" | grep -c :)"

    read -r DOOR INTAKE FIRST LAST DRAIN DROPPED T0 <<<"$(analyse "$OUT/metrics-steady-$RATE.csv.gz" "$SECS")"

    # Berths and waitlist places written per second, in the middle of the run,
    # read before the next rate resets the table. Seconds with nothing count as 0.
    DECIDED=$(sql "WITH b AS (SELECT date_trunc('second', created_at) AS s, count(*) AS c FROM booking GROUP BY 1),
                        w AS (SELECT generate_series(to_timestamp($T0 + 5), to_timestamp($T0 + $SECS - 6), interval '1 second') AS s)
                   SELECT coalesce(round(percentile_cont(0.5) WITHIN GROUP (ORDER BY coalesce(b.c, 0))), 0) FROM w LEFT JOIN b USING (s);")

    CPU=$(cpu_avg "$OUT/cpu-$RATE.txt" "$SECS")
    DOUBLE=$(sed -n '/THE ONE THAT MATTERS/,/rows)/p' "$LOG" | grep -oE '\([0-9]+ rows?\)' | head -1)
    REFUSED=$(strip <"$LOG" | grep -a 'outcome_regretted_at_door' | grep -oE '[0-9]+' | head -1)

    # Keeping up: decisions at 95% of the rate or better, nothing dropped, and the
    # last 10 seconds no worse than twice the first (or one second, whichever is more).
    KEEPS=$(awk -v r="$RATE" -v d="${DECIDED:-0}" -v x="${DROPPED:-1}" -v f="${FIRST:--1}" -v l="${LAST:--1}" \
        'BEGIN {ok = d >= 0.95 * r && x == 0 && f >= 0 && l >= 0 && l <= (2 * f > f + 1000 ? 2 * f : f + 1000);
                print ok ? "yes" : "NO"}')
    [ "${REFUSED:-0}" -gt 0 ] && KEEPS="$KEEPS (ran out: $REFUSED refused)"

    printf '%6s | %-7s %-7s | %-8s | %-15s %-6s | %-9s | %-5s | %-4s | %-8s | %s\n' \
        "$RATE" "${DOOR:-?}" "${DROPPED:-?}" "${INTAKE:-?}ms" "${FIRST:-?}→${LAST:-?}ms" "${DRAIN:-?}s" \
        "${DECIDED:-?}" "$PARTS" "$CPU" "$KEEPS" "${DOUBLE:-?}"
done

cat <<EOF

  door/s       booking attempts reaching the door per second (median, middle of the run)
  dropped      attempts k6 could not start on time: it ran out of users still waiting
  intake95     how long the 202 took, the front door alone
  decide95     click to knowing what you got, over the first 10s → the last 10s.
               Climbing means a queue is building.
  drain        how long answers kept arriving after people stopped arriving
  decided/s    berths and waitlist places written per second (median, from the database)
  parts        booking-requests partitions that received work, of all of them
  cpu          whole machine, averaged over the busiest stretch of the run

Keeping up = decided/s at least 95% of the rate, nothing dropped, and the last
10 seconds' decide95 no more than double the first's. The answer is the highest
rate that keeps up.

k6 runs on this same machine and takes more CPU the faster it goes. Payments are
the stub and nobody pays in this test; mail is logged, not sent.

Full k6 output, per-request data and CPU samples: $OUT
EOF
