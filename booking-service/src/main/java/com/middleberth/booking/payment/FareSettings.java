package com.middleberth.booking.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * What a berth costs, in paise, per class.
 *
 * A flat fare per class on purpose. Real Indian Railways fares depend on distance,
 * quota and more — that is a pricing engine, and out of scope. What matters here is
 * that an amount exists to charge.
 *
 * A waitlisted ticket costs the same full fare as a confirmed one, as on IRCTC.
 */
@ConfigurationProperties(prefix = "middleberth")
public record FareSettings(Map<String, Long> fares) {

    public long forClass(String coachClass) {
        Long paise = fares == null ? null : fares.get(coachClass);
        if (paise == null) {
            throw new IllegalStateException("No fare configured for class " + coachClass);
        }
        return paise;
    }
}
