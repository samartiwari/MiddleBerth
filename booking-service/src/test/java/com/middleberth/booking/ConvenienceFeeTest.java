package com.middleberth.booking;

import com.middleberth.booking.payment.FareSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic that decides whether a cancellation can pay for itself.
 *
 * No Spring, no database — this is a sum, and a sum that was got wrong live. The
 * first live payment could not be refunded at all: Razorpay had taken its cut on
 * the way in, so the account never held the full fare and a full refund was
 * refused with the unhelpful "invalid request sent".
 *
 * The fix is what IRCTC does — charge a convenience fee on top and do not give it
 * back — and the whole thing turns on the fee being big enough.
 */
class ConvenienceFeeTest {

    /** 2% plus 18% GST on the fee, which is what Razorpay actually charged us. */
    private static final double GATEWAY_CUT = 0.0236;

    private static FareSettings faresWithFee(int bps) {
        return new FareSettings(Map.of("3A", 240_000L, "SL", 90_000L), bps);
    }

    @Test
    void the_fee_is_added_on_top_of_the_fare() {
        FareSettings fares = faresWithFee(300);

        assertThat(fares.baseFare("3A")).isEqualTo(240_000);
        assertThat(fares.convenienceFee("3A")).as("3% of 2,400 rupees").isEqualTo(7_200);
        assertThat(fares.totalCharge("3A")).isEqualTo(247_200);
    }

    /**
     * THE test. Money that was never received cannot be paid out.
     *
     * The gateway takes its cut of the TOTAL, and the passenger gets the BASE back.
     * What is left over has to be zero or better, for every class, or some
     * cancellations quietly cost more than they collected.
     */
    @Test
    void every_class_can_fund_its_own_refund() {
        FareSettings fares = faresWithFee(300);

        for (String coachClass : new String[]{"3A", "SL"}) {
            long total = fares.totalCharge(coachClass);
            long received = total - Math.round(total * GATEWAY_CUT);
            long refunded = fares.baseFare(coachClass);

            assertThat(received - refunded)
                    .as("%s: received %d, refunds %d", coachClass, received, refunded)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    /**
     * The trap, and the service now refuses to start on it.
     *
     * Setting the fee to exactly the gateway's 2.36% looks obviously right and is
     * obviously wrong: the gateway takes 2.36% of the LARGER total, not of the
     * base, so the sum still comes up short. The floor is cut/(1 - cut) = 2.4171%.
     *
     * Getting this wrong does not fail loudly — every refund just quietly costs
     * slightly more than was collected — so it is refused at startup instead.
     */
    @Test
    void a_fee_equal_to_the_gateways_own_cut_is_refused_at_startup() {
        assertThatThrownBy(() -> faresWithFee(236))              // 2.36%, the tempting answer
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 242");
    }

    /** And the arithmetic behind that refusal, spelled out. */
    @Test
    void matching_the_cut_would_have_left_us_short() {
        long base = 240_000;
        long total = base + Math.round(base * GATEWAY_CUT);      // charge the fare + 2.36%
        long received = total - Math.round(total * GATEWAY_CUT); // gateway takes 2.36% of THAT

        assertThat(received).as("%d received against a %d fare", received, base).isLessThan(base);
    }

    /** Rounded up, so the fee is never a fraction less than it should be. */
    @Test
    void the_fee_is_rounded_up_never_down() {
        // 333 bps of 1,001 paise is 33.33 paise — must become 34, not 33.
        FareSettings odd = new FareSettings(Map.of("3A", 1_001L), 333);

        assertThat(odd.convenienceFee("3A")).isEqualTo(34);
    }
}
