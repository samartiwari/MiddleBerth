#!/usr/bin/env bash
# One real payment, against real Razorpay test mode, end to end.
#
#   sign up -> book -> pay (you, in a browser) -> confirmed -> cancel -> refunded
#
# Everything else in this project can be proved by a test. This cannot: Razorpay's
# checkout is a card form on their page, and somebody has to type into it. So the
# script does every part that can be automated and stops at the one that cannot.
#
# NO WEBHOOK IS NEEDED. Razorpay cannot call back to a laptop, and it does not have
# to — the reconciliation job asks Razorpay about orders it has not heard about,
# every 15 seconds, once they are 30 seconds old. Watching this run IS the proof
# that the safety net works, because the fast path is not available at all.
#
#   usage: deploy/live-payment.sh [base-url]
#
# Needs RAZORPAY_MODE=live and the keys in .env, and docker compose already up.

set -euo pipefail

BASE="${1:-http://localhost:${HTTP_PORT:-80}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PAGE_PORT="${PAGE_PORT:-8899}"
TRAIN=12951
DATE=$(date -u -d "+1 day" +%Y-%m-%d)
REQUEST_ID="LIVE$(date +%s)"

say() { printf '\n== %s\n' "$1"; }
die() { printf '\n!! %s\n' "$1" >&2; exit 1; }

command -v jq >/dev/null || die "jq is needed: sudo apt install jq"

# ---------------------------------------------------------------- preflight

if [ -f "$HERE/../.env" ]; then
    # shellcheck disable=SC1091
    set -a; . "$HERE/../.env"; set +a
fi
[ "${RAZORPAY_MODE:-stub}" = "live" ] \
    || die "RAZORPAY_MODE is not live. Set it in .env and: docker compose up -d payment-service"
[ -n "${RAZORPAY_KEY_ID:-}" ] || die "RAZORPAY_KEY_ID is not set in .env"

MODE=$(docker compose exec -T payment-service sh -c 'echo $MIDDLEBERTH_RAZORPAY_MODE' 2>/dev/null | tr -d '\r')
[ "$MODE" = "live" ] \
    || die "the running payment-service is in '$MODE' mode — docker compose up -d payment-service"

say "who gets the ticket"
read -rp "email for the ticket (the mail goes here, blank to skip mail): " PASSENGER_EMAIL
PASSENGER_EMAIL="${PASSENGER_EMAIL:-nobody@middleberth.invalid}"

# ---------------------------------------------------------------- book

EMAIL="live-$(date +%s)@middleberth.invalid"
PASSWORD="live test password"

say "sign up $EMAIL"
TOKEN=$(curl -fsS -X POST "$BASE/auth/signup" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .token)
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || die "signup failed"
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
echo "ok"

say "book a berth on $TRAIN for $DATE"
BOOK=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/bookings" "${AUTH[@]}" \
        -d "{\"requestId\":\"$REQUEST_ID\",\"trainNumber\":\"$TRAIN\",\"travelDate\":\"$DATE\",
             \"coachClass\":\"3A\",\"passenger\":{\"name\":\"Live Test\",
             \"email\":\"$PASSENGER_EMAIL\",\"phone\":\"9876543210\"}}")
case "$BOOK" in
    202) ;;
    409) die "the train is full or not on sale for $DATE — reseed: docker compose up -d seed" ;;
    *)   die "booking was refused with HTTP $BOOK" ;;
esac

say "wait for the berth"
for _ in $(seq 1 60); do
    STATUS=$(curl -fsS "$BASE/api/bookings/$REQUEST_ID" "${AUTH[@]}" | jq -r .status)
    [ "$STATUS" = PENDING ] || break
    sleep 1
done
[ "$STATUS" = HELD ] || die "expected HELD, got $STATUS"
echo "held"

# ---------------------------------------------------------------- pay

say "ask Razorpay for a real order"
PAY=$(curl -fsS -X POST "$BASE/api/bookings/$REQUEST_ID/pay" "${AUTH[@]}")
ORDER=$(echo "$PAY" | jq -r .orderId)
AMOUNT=$(echo "$PAY" | jq -r .amountPaise)
BASE=$(echo "$PAY" | jq -r .baseFarePaise)
FEE=$(echo "$PAY" | jq -r .convenienceFeePaise)
KEY=$(echo "$PAY" | jq -r .keyId)
case "$ORDER" in
    order_*) ;;
    *) die "that is not a real Razorpay order id: $ORDER — is the service still stubbed?" ;;
esac
printf '%s for ₹%s  (fare ₹%s + convenience fee ₹%s)\n' \
    "$ORDER" "$((AMOUNT / 100))" "$((BASE / 100))" "$((FEE / 100))"

# Razorpay's checkout script will not run from a file:// page, so serve the one
# next to this script for as long as we need it.
python3 -m http.server "$PAGE_PORT" --directory "$HERE" --bind 127.0.0.1 >/dev/null 2>&1 &
PAGE_PID=$!
trap 'kill $PAGE_PID 2>/dev/null || true' EXIT
sleep 1

URL="http://127.0.0.1:$PAGE_PORT/checkout.html?key=$KEY&order=$ORDER&amount=$AMOUNT"
say "YOUR TURN — open this and pay"
printf '\n   %s\n\n' "$URL"
cat <<'CARDS'
   Use a DOMESTIC test card, any future expiry, any CVV, then "Success":
       Visa debit          4100 2800 0000 1007
       Mastercard credit   5555 5100 0008 1006

   Not 4111 1111 1111 1111 — Razorpay reads that as an international card, and
   most Indian test accounts have international payments switched off.
CARDS
command -v xdg-open >/dev/null && xdg-open "$URL" >/dev/null 2>&1 || true

# ---------------------------------------------------------------- confirm

say "watching for the money (no webhook can reach a laptop — this is the reconciliation job)"
CONFIRMED=no
for i in $(seq 1 90); do
    BODY=$(curl -fsS "$BASE/api/bookings/$REQUEST_ID" "${AUTH[@]}")
    if [ "$(echo "$BODY" | jq -r .status)" = CONFIRMED ]; then CONFIRMED=yes; break; fi
    [ $((i % 10)) -eq 0 ] && printf '   still waiting (%ss)\n' "$((i * 2))"
    sleep 2
done
[ "$CONFIRMED" = yes ] || die "not confirmed after 3 minutes. Did the payment go through?
    docker compose logs --tail=50 payment-service"

PNR=$(echo "$BODY" | jq -r .pnr)
printf '\n   CONFIRMED — berth %s, PNR %s\n' "$(echo "$BODY" | jq -r .seat)" "$PNR"

say "the ticket mail"
docker compose logs --tail=40 notification-service | grep -i "PNR $PNR" -A2 -B6 || \
    echo "   nothing in the log — MAIL_MODE=smtp sends it for real instead"

# ---------------------------------------------------------------- refund

say "the part that has never been proved against real Razorpay"
cat <<EOF
Cancelling refunds the FARE (₹$((BASE / 100))), not the convenience fee (₹$((FEE / 100))).
The fee is already spent: Razorpay took its cut out of this payment on the way in
and does not give it back, so refunding the whole ₹$((AMOUNT / 100)) would mean paying out
money that never arrived. IRCTC keeps its convenience fee for the same reason.
EOF
read -rp "cancel this ticket and ask Razorpay for a real refund? [y/N] " ANSWER
[ "$ANSWER" = y ] || { echo "left booked. PNR $PNR"; exit 0; }

CANCEL=$(curl -fsS -X POST "$BASE/api/bookings/$REQUEST_ID/cancel" "${AUTH[@]}")
echo "$CANCEL" | jq -r '"cancelled, refund on its way: \(.refundOnItsWay)"'

say "watching payment-service do the refund"
for _ in $(seq 1 30); do
    if docker compose logs --tail=200 payment-service 2>/dev/null | grep -qi "refund"; then
        docker compose logs --tail=200 payment-service | grep -i refund | tail -5
        break
    fi
    sleep 2
done

cat <<EOF

== check it yourself
Razorpay dashboard -> Transactions -> Refunds. There should be one for ₹$((BASE / 100))
against the payment for order $ORDER — the fare, with the convenience fee kept.

If it is there, every path in this system has now been run against real Razorpay:
orders, payment, reconciliation and refund.
EOF
