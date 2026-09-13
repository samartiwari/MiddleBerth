package com.middleberth.booking.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * What a berth costs, in paise, per class — plus the convenience fee on top.
 *
 * A flat fare per class on purpose. Real Indian Railways fares depend on distance,
 * quota and more — that is a pricing engine, and out of scope. What matters here is
 * that an amount exists to charge.
 *
 * A waitlisted ticket costs the same full fare as a confirmed one, as on IRCTC.
 *
 * ---
 *
 * THE CONVENIENCE FEE, and why it is not decoration.
 *
 * The payment gateway takes its cut on the way IN. Charge somebody 2,400 rupees
 * and Razorpay keeps about 2.36% of it — 2% plus GST on the fee — so 2,343.36
 * arrives, not 2,400. The other 56.64 is gone and is never returned, not even
 * when the payment is refunded.
 *
 * So a booking that collects exactly the fare can never give exactly the fare
 * back. The money to cover the gap has to come from somewhere, and with nothing
 * else in the account there is nowhere — which is precisely what the live test
 * ran into: Razorpay refused a full refund with "invalid request sent", meaning
 * "you do not have that much".
 *
 * Charging base + fee and refunding only the base closes the gap. This is exactly
 * what IRCTC does, and why their convenience fee is never refunded.
 *
 * WHY THE FEE MUST BE MORE THAN THE GATEWAY'S OWN CUT. The obvious move — charge
 * a fee equal to the 2.36% the gateway takes — does not work, because the
 * gateway's cut is a percentage of the LARGER total, not of the base:
 *
 *     charge 2400 + 2.36%  = 2456.64
 *     gateway takes 2.36%  =   57.98
 *     we receive           = 2398.66     <- still short of the 2400 base
 *
 * The fee has to satisfy  fee >= cut / (1 - cut), which at a 2.36% cut is
 * 2.4171%. The default below is 3%, comfortably clear of it, which leaves room
 * for the gateway's rate to rise as far as 2.912% before refunds stop covering
 * themselves.
 */
@ConfigurationProperties(prefix = "middleberth")
public record FareSettings(Map<String, Long> fares, int convenienceFeeBps) {

    /**
     * What Razorpay actually took from us: 2% plus 18% GST on that fee, in basis
     * points. Measured from a real payment — fee 5,664 paise on 240,000.
     *
     * If the gateway contract changes, this changes with it.
     */
    private static final int GATEWAY_CUT_BPS = 236;

    /**
     * The smallest fee that still covers the cut, because the cut is taken from the
     * TOTAL and not from the base: fee >= cut / (1 - cut). At 2.36% that is
     * 2.4171%, so 242 basis points.
     */
    private static final int FLOOR_BPS =
            (int) Math.ceilDiv(GATEWAY_CUT_BPS * 10_000L, 10_000L - GATEWAY_CUT_BPS);

    /**
     * Refuse to start rather than discover it one cancellation at a time.
     *
     * A fee below the floor does not fail loudly — it just means every refund
     * quietly costs a little more than was collected, and eventually a refund is
     * refused outright for want of a balance. That is a very slow thing to notice,
     * so it is checked here, once, at startup.
     */
    public FareSettings {
        if (convenienceFeeBps < FLOOR_BPS) {
            throw new IllegalStateException(
                    "middleberth.convenience-fee-bps is " + convenienceFeeBps + ", which does not cover the "
                    + "payment gateway's " + GATEWAY_CUT_BPS + " bps cut. It must be at least " + FLOOR_BPS
                    + " — the cut is taken from the total, not the base fare, so matching it is not enough.");
        }
    }

    /**
     * What the ticket itself costs, and the only part given back when a passenger
     * cancels of their own accord.
     */
    public long baseFare(String coachClass) {
        Long paise = fares == null ? null : fares.get(coachClass);
        if (paise == null) {
            throw new IllegalStateException("No fare configured for class " + coachClass);
        }
        return paise;
    }

    /**
     * Rounded UP, and in whole paise. Rounding down would collect a fraction less
     * than the gateway takes, which over enough bookings is the whole problem this
     * fee exists to solve, reintroduced quietly.
     *
     * Basis points rather than a percentage as a decimal: money and floating point
     * do not belong in the same method. 300 bps is 3.00%.
     */
    public long convenienceFee(String coachClass) {
        return Math.ceilDiv(baseFare(coachClass) * convenienceFeeBps, 10_000L);
    }

    /** What the customer is actually asked to pay. */
    public long totalCharge(String coachClass) {
        return baseFare(coachClass) + convenienceFee(coachClass);
    }
}
