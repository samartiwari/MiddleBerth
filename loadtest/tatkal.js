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
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx:80';
const DATE = __ENV.TRAVEL_DATE;
const WEBHOOK_SECRET = __ENV.RAZORPAY_WEBHOOK_SECRET || 'dev-only-webhook-secret-not-for-real-use';
const VUS = Number(__ENV.VUS || 1000);

// SHAPE=steady: instead of everybody at once, people keep arriving at RATE a
// second for DURATION. The spike is over in two seconds, which is too short to
// tell a system that keeps up from one quietly building a queue. See
// loadtest/steady.sh.
const STEADY = (__ENV.SHAPE || 'spike') === 'steady';
const RATE = Number(__ENV.RATE || 500);
const DURATION = __ENV.DURATION || '60s';
// Enough virtual users to hold every attempt still waiting for its answer. When
// they run out, k6 counts the attempts it could not start as dropped_iterations —
// which is itself a sign the answers are not coming back fast enough.
const MAX_VUS = Number(__ENV.MAX_VUS || 6000);

// Paying is a different journey, and it keeps a virtual user busy for about ten
// more seconds. The steady test is about booking, so by default nobody pays in it.
const PAY_PERCENT = Number(__ENV.PAY_PERCENT || (STEADY ? 0 : 50));

// The steady test's trains are the 300 in deploy/seed-throughput.sql, numbered
// 90001 to 90300. The spike keeps its five.
const TRAIN_COUNT = Number(__ENV.TRAIN_COUNT || 300);
const TRAINS = __ENV.TRAINS
    ? __ENV.TRAINS.split(',')
    : STEADY
        ? Array.from({ length: TRAIN_COUNT }, (_, i) => String(90001 + i))
        : ['12951', '12009', '22691', '12259', '12627'];
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
// Of those, the ones turned away by the door before anything was queued. The
// cheapest request in the system: one read, no message, no row.
const doorRegrets = new Counter('outcome_regretted_at_door');
const undecided = new Counter('outcome_never_decided');
const alreadyDone = new Counter('outcome_already_booked');
const rateLimited = new Counter('rate_limited_429');
const confirmed = new Counter('paid_and_confirmed');
const decided = new Rate('decided_within_15s');

// BROWSE=0 runs the bookers alone.
//
// Two different questions, so two different runs. With browsing on, the question
// is "does search stay fast while booking is on fire". With it off, the question
// is "how much booking can this do" — and the answer is not buried under 12,000
// searches a minute that the test sends at a fixed rate no matter how fast the
// system is, which made a third of the old request count the test's own setting.
const BROWSE = (__ENV.BROWSE || '1') !== '0';

const scenarios = {};

if (STEADY) {
    // People keep arriving at the same rate whatever the system is doing, and each
    // attempt is a different person, exactly as in the spike.
    scenarios.steady = {
        executor: 'constant-arrival-rate',
        rate: RATE,
        timeUnit: '1s',
        duration: DURATION,
        preAllocatedVUs: Math.min(RATE * 2, MAX_VUS),
        maxVUs: MAX_VUS,
    };
} else {
    // The spike. Every user starts at once and books exactly once.
    scenarios.tatkal = {
        executor: 'per-vu-iterations',
        vus: VUS,
        iterations: 1,
        maxDuration: '3m',
    };
}

if (BROWSE) {
    // Meanwhile, people are browsing. Search must stay fast while booking is
    // on fire — it reads one Redis key and never touches booking's database.
    scenarios.browsing = {
        executor: 'constant-arrival-rate',
        rate: 200,
        timeUnit: '1s',
        duration: '60s',
        preAllocatedVUs: 50,
        exec: 'browse',
    };
}

const thresholds = {
    // Intake is supposed to be a couple of milliseconds: validate, drop it on
    // Kafka, reply 202. If this climbs, the front door is doing too much.
    'http_req_duration{name:book}': ['p(95)<500', 'p(99)<1000'],
    'decided_within_15s': ['rate>0.95'],
    // Not limits, just a way to see each step in the summary: k6 only reports
    // a tag that some threshold mentions.
    'http_req_duration{name:login}': ['p(99)<60000'],
    'http_req_duration{name:poll}': ['p(99)<60000'],
    'http_req_duration{name:pay}': ['p(99)<60000'],
    'http_req_duration{name:webhook}': ['p(99)<60000'],
};

if (BROWSE) {
    // Browsing must not suffer because booking is busy.
    thresholds['http_req_duration{name:search}'] = ['p(99)<500'];
}

export const options = {
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

/**
 * The demo token endpoint, on purpose.
 *
 * Real signup uses BCrypt, which takes about a tenth of a second by design — two
 * thousand of those is minutes of pure hashing, and what is being measured here
 * is seat contention, not a password hash. The endpoint only exists when
 * AUTH_DEMO_TOKENS=true, which is the laptop and never production.
 */
function login(userId) {
    const res = http.post(`${BASE}/auth/token`, JSON.stringify({ userId }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
    return res.status === 200 ? res.json('token') : null;
}

export default function () {
    // Who this is. In the spike each virtual user is one person. In the steady test
    // a virtual user is reused for attempt after attempt, so the attempt's own
    // number has to be the person: the virtual user's would repeat request ids,
    // which the database rightly answers as duplicates, and pile every attempt onto
    // one user's rate limit.
    const person = STEADY ? exec.scenario.iterationInTest + 1 : __VU;
    const userId = 100000 + person;
    const token = login(userId);
    if (!token) return;

    const auth = { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
    const train = TRAINS[person % TRAINS.length];
    // With 300 trains, "person % 2" would tie the class to the train: even people,
    // even trains, always 3A. So the steady test switches class each time it has
    // gone round every train, and all 600 train-and-class pairs get their share.
    const coachClass = STEADY
        ? CLASSES[Math.floor(person / TRAINS.length) % CLASSES.length]
        : CLASSES[person % CLASSES.length];
    const requestId = `K6-${RUN}-${person}`;

    const started = Date.now();
    const accepted = http.post(`${BASE}/api/bookings`, JSON.stringify({
        requestId, trainNumber: train, travelDate: DATE, coachClass,
        // Every ticket is for somebody. Typed in by the client, so the booking
        // path never looks a passenger up.
        passenger: {
            name: `Passenger ${person}`,
            email: `passenger${person}@example.invalid`,
            phone: '9876543210',
        },
    }), { headers: auth, tags: { name: 'book' } });

    if (accepted.status === 429) { rateLimited.add(1); return; }

    // 409 WAITLIST_FULL is a real answer, not a failure — the berths and the
    // waitlist are both gone and the door said so without queueing anything. It is
    // also the fastest "no" in the system: one read, no Kafka, no row. Counted as
    // a regret, because that is what it is.
    if (accepted.status === 409 && accepted.body.includes('WAITLIST_FULL')) {
        decided.add(true);
        decisionTime.add(Date.now() - started);
        regrettedCount.add(1);
        doorRegrets.add(1);
        return;
    }

    check(accepted, { 'booking answered (202 or 409)': r => r.status === 202 || r.status === 409 });
    if (accepted.status !== 202) return;

    // The page polls, exactly like a real one would, and waits as long as the
    // server says between asks. The server makes that longer the longer the booking
    // has waited, so a page stuck in a queue asks every few seconds rather than twice
    // a second. POLL_INTERVAL is only the first wait, and the wait when there is no hint.
    let status = null;
    let wait = POLL_INTERVAL;
    for (let i = 0; i < 30; i++) {
        sleep(wait);
        const res = http.get(`${BASE}/api/bookings/${requestId}`,
            { headers: auth, tags: { name: 'poll' } });
        if (res.status === 429) { rateLimited.add(1); continue; }
        if (res.status !== 200) continue;
        status = res.json('status');
        if (status && status !== 'PENDING') break;
        const hinted = res.json('retryAfterMs');
        wait = hinted ? hinted / 1000 : POLL_INTERVAL;
    }

    const waited = Date.now() - started;
    decided.add(status !== null && status !== 'PENDING');

    if (status === 'HELD' || status === 'WAITLIST_HELD') {
        decisionTime.add(waited);
        status === 'HELD' ? heldCount.add(1) : waitlistedCount.add(1);
        if (person % 100 < PAY_PERCENT) {
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
