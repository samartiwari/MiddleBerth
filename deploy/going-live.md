# Going live

Everything in this project defaults to stubs: a fake payment gateway and mail
that is only written to the log. That is deliberate — the load test fires
thousands of requests a minute, and neither a real payment gateway nor a real
mail server should ever see that.

This is what changes for a real deployment, and nothing here needs a code change.

## 1. Razorpay

Use **test mode**. It makes the same API calls and sends the same webhooks as
live, and it does not need a registered business, KYC or a settlement account.
Real money needs all three.

From the dashboard:

- Settings → API Keys → generate keys. You get a key id (`rzp_test_…`) and a secret.
- Settings → Webhooks → add `https://your-domain/webhooks/razorpay`, with a long
  random secret you invent, for the event **`payment.captured`**. That secret is
  not the API secret; it is what the signature check uses.

Then:

    RAZORPAY_MODE=live
    RAZORPAY_KEY_ID=rzp_test_...
    RAZORPAY_KEY_SECRET=...
    RAZORPAY_WEBHOOK_SECRET=...
    RECONCILE_INTERVAL=PT60S      # their API is rate limited; the stub is not

payment-service refuses to start in live mode without the keys, rather than
coming up healthy and failing every customer.

### The gateway's cut, and why refunds need it

Razorpay takes its fee on the way **in** — about 2.36% (2% plus GST on the fee) —
and does **not** return it when a payment is refunded. Charge 2,400 and 2,343.36
arrives. The other 56.64 is gone.

So a booking that collects exactly the fare can never refund exactly the fare.
Ask Razorpay to do it anyway and it refuses with `invalid request sent`, which
means "you do not have that much" and reads like a malformed request. That cost
an afternoon to work out.

Two things follow, and both are already handled:

- **A convenience fee is charged on top** (`middleberth.convenience-fee-bps`,
  3% by default) and is **not** refunded when a passenger cancels — so every
  cancellation pays for itself. This is what IRCTC does and why their convenience
  fee is never returned. The fee has to clear `cut / (1 - cut)` = 2.4171%, not the
  2.36% cut itself; `ConvenienceFeeTest` asserts it.
- **A refund we owe because WE failed** — money taken for a berth that could not
  be given — still goes back in full, fee included. The company swallows the
  gateway's cut, because keeping a service charge for a service never delivered
  would be indefensible.

One consequence worth knowing before the first cancellation in production: a
refund is paid out of the account **balance**, not out of that payment. A brand
new account with a single payment in it may have nothing to draw on. With real
traffic there is always a balance and this never comes up.

## 2. Mail

    MAIL_MODE=smtp
    MAIL_FROM=you@example.com
    SPRING_MAIL_HOST=smtp.gmail.com
    SPRING_MAIL_PORT=587
    SPRING_MAIL_USERNAME=...
    SPRING_MAIL_PASSWORD=...        # a Gmail app password needs 2FA on the account

A free Gmail account sends about **100 mails a day over SMTP**, on a rolling 24
hours, and blocks sending for up to a day if you go over. Fine for a demo,
useless for a load test — which is why the default is to log instead of send.

Use a throwaway account. Automated mail from a personal one gets it flagged.

## 3. HTTPS

Razorpay will not post a webhook to plain HTTP, so the webhook needs a public
HTTPS address. See `nginx-tls.conf.example`; a free DuckDNS subdomain and certbot
are enough.

Without it the system still works — a payment is picked up by the reconciliation
job instead of the webhook — but minutes later rather than seconds.

## 4. Accounts

People sign up with an email and a password, which is BCrypt hashed before it is
stored. Nothing else is needed — but one thing must be OFF:

    AUTH_DEMO_TOKENS=false    # the default. With it TRUE, /auth/token hands out a
                              # token for any user id with no password at all,
                              # which makes every other protection pointless.
                              # docker compose turns it on for the laptop, because
                              # the load test needs two thousand identities and
                              # BCrypt takes a tenth of a second each.

Failed logins are counted per account in Redis: five wrong answers and that
account is locked for fifteen minutes. Signups are capped per IP address.

## 5. The rest

    JWT_SECRET=...            # 32+ random bytes. Anyone who knows it can mint a
                              # token for any user.
    POSTGRES_PASSWORD=...

Keep all of it in the VM's environment or a Kubernetes Secret. Never in the
repository, and never in a chat message.

## What is still not real

- **No password reset.** Forgetting a password means losing the account. The mail
  pipeline is there, so it is a small addition, but it is not built.
- **No email verification.** You can sign up with an address you do not own.
- **No PNR.** The booking reference is the request id.
- **No cancellation by the passenger.** Refunds happen automatically when a
  payment cannot be honoured.
- **Five hardcoded trains**, and no routes, stations or schedules.
