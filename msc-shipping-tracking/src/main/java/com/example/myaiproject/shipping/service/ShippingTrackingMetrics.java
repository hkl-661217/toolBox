package com.example.myaiproject.shipping.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counter facade over the Micrometer registry for the shipping-tracking pipeline. */
@Component
public class ShippingTrackingMetrics {
    static final String QUERY = "msc.shipping.query";
    static final String EMAIL = "msc.shipping.email";
    static final String PARSER_FAILURE = "msc.shipping.parser_failure";

    private final MeterRegistry registry;

    public ShippingTrackingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** outcome is the snapshot status: success | no_result | failed | manual_required */
    public void recordQuery(String outcome) {
        registry.counter(QUERY, "outcome", normalize(outcome)).increment();
    }

    /** outcome is one of: sent | failed | sent_retry | failed_retry | given_up */
    public void recordEmail(String outcome) {
        registry.counter(EMAIL, "outcome", normalize(outcome)).increment();
    }

    /** reason classifies why the parser couldn't extract anything (used in #5). */
    public void recordParserFailure(String reason) {
        registry.counter(PARSER_FAILURE, "reason", normalize(reason)).increment();
    }

    private static String normalize(String value) {
        return value == null ? "unknown" : value.toLowerCase();
    }
}
