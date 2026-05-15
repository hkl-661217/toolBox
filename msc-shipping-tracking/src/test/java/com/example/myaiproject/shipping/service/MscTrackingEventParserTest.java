package com.example.myaiproject.shipping.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MscTrackingEventParserTest {

    @Test
    void parsesCanonicalFourColumnTable() {
        Fixtures fx = new Fixtures();
        String raw = String.join("\n",
                "日期", "位置", "描述", "空载/满载/船舶/航次",
                "01/05/2026", "Shanghai", "Loaded on vessel", "MAERSK 123E");

        List<ShippingTrackingEvent> events = fx.parser.parse(raw);

        assertEquals(1, events.size());
        assertEquals("Shanghai", events.get(0).location());
    }

    @Test
    void warnsWhenHeaderMissingOnSubstantialPage() {
        Fixtures fx = new Fixtures();
        String raw = "a".repeat(500);  // > 200 chars but no header

        List<ShippingTrackingEvent> events = fx.parser.parse(raw);

        assertTrue(events.isEmpty());
        assertEquals(1.0, fx.meterRegistry.counter(
                ShippingTrackingMetrics.PARSER_FAILURE, "reason", "header_not_found").count());
    }

    @Test
    void silentWhenHeaderMissingOnShortPage() {
        Fixtures fx = new Fixtures();
        String raw = "tiny error stub";  // < 200 chars

        List<ShippingTrackingEvent> events = fx.parser.parse(raw);

        assertTrue(events.isEmpty());
        assertEquals(0.0, fx.meterRegistry.counter(
                ShippingTrackingMetrics.PARSER_FAILURE, "reason", "header_not_found").count(),
                "short pages are not worth alerting on");
    }

    @Test
    void blankOrNullPageIsSilent() {
        Fixtures fx = new Fixtures();
        assertTrue(fx.parser.parse(null).isEmpty());
        assertTrue(fx.parser.parse("").isEmpty());
        assertFalse(fx.meterRegistry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals(ShippingTrackingMetrics.PARSER_FAILURE)));
    }

    private static class Fixtures {
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final ShippingTrackingMetrics metrics = new ShippingTrackingMetrics(meterRegistry);
        final MscTrackingEventParser parser = new MscTrackingEventParser(metrics);
    }
}
