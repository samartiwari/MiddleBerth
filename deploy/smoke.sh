#!/usr/bin/env bash
# One booking, all the way through, against a running docker compose.
#
#   token -> search -> book -> poll -> pay -> webhook -> confirmed
#
# Everything goes through nginx on port 80, the way a real client would. If this
# passes, the five services are genuinely wired together — which is something no
# unit test can tell you.
#
#   usage: deploy/smoke.sh [base-url]

set -euo pipefail

BASE="${1:-http://localhost:${HTTP_PORT:-80}}"
USER_ID=5512
REQUEST_ID="SMOKE$(date +%s)"
TRAIN=12951
DATE=$(date -u -d "+1 day" +%Y-%m-%d)
WEBHOOK_SECRET="${RAZORPAY_WEBHOOK_SECRET:-dev-only-webhook-secret-not-for-real-use}"
# Only mail sent AFTER this moment counts. Otherwise the check passes on the mail
# from a previous run still sitting in the log — which it did, until I noticed.
STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

say() { printf '\n== %s\n' "$1"; }

say "token for user $USER_ID"
TOKEN=$(curl -fsS -X POST "$BASE/auth/token" -H 'Content-Type: application/json' \
        -d "{\"userId\":$USER_ID}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] || { echo "no token"; exit 1; }
echo "ok"

say "search: $TRAIN on $DATE, 3A"
curl -fsS "$BASE/api/trains/$TRAIN/availability?date=$DATE&class=3A"
echo

say "book $REQUEST_ID"
curl -fsS -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d "{\"requestId\":\"$REQUEST_ID\",\"trainNumber\":\"$TRAIN\",\"travelDate\":\"$DATE\",\"coachClass\":\"3A\"}"
echo

say "poll until it is decided"
for _ in $(seq 1 30); do
    STATUS=$(curl -fsS "$BASE/api/bookings/$REQUEST_ID" -H "Authorization: Bearer $TOKEN")
    echo "$STATUS"
    case "$STATUS" in *PENDING*) sleep 1 ;; *) break ;; esac
done
case "$STATUS" in *HELD*|*WAITLIST*) ;; *) echo "not held: $STATUS"; exit 1 ;; esac

say "pay now"
PAY=$(curl -fsS -X POST "$BASE/api/bookings/$REQUEST_ID/pay" -H "Authorization: Bearer $TOKEN")
echo "$PAY"
ORDER=$(echo "$PAY" | sed -E 's/.*"orderId":"([^"]+)".*/\1/')
AMOUNT=$(echo "$PAY" | sed -E 's/.*"amountPaise":([0-9]+).*/\1/')

say "Razorpay says it was paid (signed the way Razorpay signs it)"
BODY="{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_smoke$(date +%s)\",\"amount\":$AMOUNT,\"currency\":\"INR\",\"status\":\"captured\",\"order_id\":\"$ORDER\",\"created_at\":$(date +%s)}}}}"
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" -hex | sed 's/^.*= //')
curl -fsS -X POST "$BASE/webhooks/razorpay" -H 'Content-Type: application/json' \
     -H "X-Razorpay-Signature: $SIG" -d "$BODY"
echo

say "poll until confirmed"
for _ in $(seq 1 30); do
    STATUS=$(curl -fsS "$BASE/api/bookings/$REQUEST_ID" -H "Authorization: Bearer $TOKEN")
    case "$STATUS" in *CONFIRMED*) echo "$STATUS"; break ;; *) sleep 1 ;; esac
done
case "$STATUS" in *CONFIRMED*) ;; *) echo "never confirmed: $STATUS"; exit 1 ;; esac

# The mail comes a second or two after the ticket: the note goes into the outbox
# with the booking, a job picks it up, notification-service sends it.
mail_since() {
    docker compose logs --since "$STARTED_AT" notification-service 2>/dev/null || true
    PATH="$HOME/.local/bin:$PATH" kubectl -n middleberth logs --since-time="$STARTED_AT" \
        deploy/notification-service 2>/dev/null || true
}

say "the mail"
for _ in $(seq 1 30); do
    if mail_since | grep "MAIL to"; then
        printf '\nall the way through: booked, paid, confirmed, mailed.\n'
        exit 0
    fi
    sleep 1
done

echo "confirmed, but no mail went out — is there a passenger row for $USER_ID?"
exit 1
