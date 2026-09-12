// 10:00:00. Everybody clicks at once.
//
// Each virtual user is one person: they log in, they try to book once, and then
// their page polls until the answer comes back. Some of them pay.
//
// That shape matters. A load test where one user hammers the endpoint measures
// the rate limiter, not the system — real tatkal is a great many people each
// doing the thing once.
//
//   k6 run loadtest/tatkal.js          (see loadtest/run.sh)
//
// Against the STUB payment gateway. Razorpay test mode rate limits, so pointing
// this at the real thing measures Razorpay.

import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx:80';
const DATE = __ENV.TRAVEL_DATE;
const WEBHOOK_SECRET = __ENV.RAZORPAY_WEBHOOK_SECRET || 'dev-only-webhook-secret-not-for-real-use';
const VUS = Number(__ENV.VUS || 1000);
const PAY_PERCENT = Number(__ENV.PAY_PERCENT || 50);

const TRAINS = (__ENV.TRAINS || '12951,12009,22691,12259,12627').split(',');
// Unique per run. Reusing request ids across runs is not a bug in the system —
// the UNIQUE constraint correctly hands back the booking that id already made,
// which in the second run was somebody's confirmed ticket from the first.
const RUN = __ENV.RUN_ID || `${Date.now() % 100000000}`;
const POLL_INTERVAL = Number(__ENV.POLL_INTERVAL || 0.5);
const CLASSES = ['3A', 'SL'];

// How long from "I clicked book" to "I know what I got". The number a passenger
// actually feels — the 202 that comes back in 2ms is not the answer.
const decisionTime = new Trend('booking_decision_ms', true);
const confirmTime = new Trend('payment_to_confirmed_ms', true);

const heldCount = new Counter('outcome_held');
const waitlistedCount = new Counter('outcome_waitlisted');
const regrettedCount = new Counter('outcome_regretted');
const undecided = new Counter('outcome_never_decided');
const alreadyDone = new Counter('outcome_already_booked');
const rateLimited = new Counter('rate_limited_429');
const confirmed = new Counter('paid_and_confirmed');
const decided = new Rate('decided_within_15s');

export const options = {
    scenarios: {
        // The spike. Every user starts at once and books exactly once.
        tatkal: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '3m',
        },
        // Meanwhile, people are browsing. Search must stay fast while booking is
        // on fire — it reads one Redis key and never touches booking's database.
        browsing: {
            executor: 'constant-arrival-rate',
            rate: 200,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 50,
            exec: 'browse',
        },
    },
    thresholds: {
        // Intake is supposed to be a couple of milliseconds: validate, drop it on
        // Kafka, reply 202. If this climbs, the front door is doing too much.
        'http_req_duration{name:book}': ['p(95)<500', 'p(99)<1000'],
        // Browsing must not suffer because booking is busy.
        'http_req_duration{name:search}': ['p(99)<500'],
        'decided_within_15s': ['rate>0.95'],
        // Not limits, just a way to see each step in the summary: k6 only reports
        // a tag that some threshold mentions.
        'http_req_duration{name:login}': ['p(99)<60000'],
        'http_req_duration{name:poll}': ['p(99)<60000'],
        'http_req_duration{name:pay}': ['p(99)<60000'],
        'http_req_duration{name:webhook}': ['p(99)<60000'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function login(userId) {
    const res = http.post(`${BASE}/auth/token`, JSON.stringify({ userId }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    return res.status === 200 ? res.json('token') : null;
}

export default function () {
    const userId = 100000 + __VU;
    const token = login(userId);
    if (!token) return;

    const auth = { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
    const train = TRAINS[__VU % TRAINS.length];
    const coachClass = CLASSES[__VU % CLASSES.length];
    const requestId = `K6-${RUN}-${__VU}`;

    const started = Date.now();
    const accepted = http.post(`${BASE}/api/bookings`, JSON.stringify({
        requestId, trainNumber: train, travelDate: DATE, coachClass,
    }), { headers: auth, tags: { name: 'book' } });

    if (accepted.status === 429) { rateLimited.add(1); return; }
    check(accepted, { 'booking accepted (202)': r => r.status === 202 });
    if (accepted.status !== 202) return;

    // The page polls, exactly like a real one would.
    let status = null;
    for (let i = 0; i < 30; i++) {
        sleep(POLL_INTERVAL);
        const res = http.get(`${BASE}/api/bookings/${requestId}`,
            { headers: auth, tags: { name: 'poll' } });
        if (res.status === 429) { rateLimited.add(1); continue; }
        if (res.status !== 200) continue;
        status = res.json('status');
        if (status && status !== 'PENDING') break;
    }

    const waited = Date.now() - started;
    decided.add(status !== null && status !== 'PENDING');

    if (status === 'HELD' || status === 'WAITLIST_HELD') {
        decisionTime.add(waited);
        status === 'HELD' ? heldCount.add(1) : waitlistedCount.add(1);
        if (__VU % 100 < PAY_PERCENT) {
            pay(requestId, auth, token);
        }
    } else if (status === 'WAITLISTED') {
        decisionTime.add(waited);
        waitlistedCount.add(1);
    } else if (status === 'REGRETTED') {
        decisionTime.add(waited);
        regrettedCount.add(1);
    } else if (status === 'CONFIRMED' || status === 'EXPIRED') {
        alreadyDone.add(1);      // this request id had been used before
    } else {
        undecided.add(1);
    }
}

/** Pay Now, then play Razorpay: sign a payment.captured webhook the way it does. */
function pay(requestId, auth) {
    const res = http.post(`${BASE}/api/bookings/${requestId}/pay`, null,
        { headers: auth, tags: { name: 'pay' } });
    if (res.status !== 200) return;

    const orderId = res.json('orderId');
    const amount = res.json('amountPaise');
    const now = Math.floor(Date.now() / 1000);
    const body = JSON.stringify({
        event: 'payment.captured',
        payload: { payment: { entity: {
            id: `pay_k6${requestId}`, amount, currency: 'INR',
            status: 'captured', order_id: orderId, created_at: now,
        } } },
    });
    // The signature is over the exact bytes sent — same rule as the real thing.
    const signature = crypto.hmac('sha256', WEBHOOK_SECRET, body, 'hex');

    const paidAt = Date.now();
    const webhook = http.post(`${BASE}/webhooks/razorpay`, body, {
        headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': signature },
        tags: { name: 'webhook' },
    });
    if (webhook.status !== 200) return;

    for (let i = 0; i < 20; i++) {
        sleep(POLL_INTERVAL);
        const check = http.get(`${BASE}/api/bookings/${requestId}`,
            { headers: auth, tags: { name: 'poll' } });
        if (check.status === 200 && check.json('status') === 'CONFIRMED') {
            confirmTime.add(Date.now() - paidAt);
            confirmed.add(1);
            return;
        }
    }
}

/** Someone who is only looking. */
export function browse() {
    const train = TRAINS[Math.floor(Math.random() * TRAINS.length)];
    const coachClass = CLASSES[Math.floor(Math.random() * CLASSES.length)];
    http.get(`${BASE}/api/trains/${train}/availability?date=${DATE}&class=${coachClass}`,
        { tags: { name: 'search' } });
}
