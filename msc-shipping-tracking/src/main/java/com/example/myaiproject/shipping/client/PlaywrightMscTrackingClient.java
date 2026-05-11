package com.example.myaiproject.shipping.client;

import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.service.MscTrackingEventParser;
import com.example.myaiproject.shipping.service.ShippingTrackingProperties;
import com.example.myaiproject.tool.msc.MscBrowserTracker;
import com.example.myaiproject.tool.msc.MscTrackingQueryType;
import com.example.myaiproject.tool.msc.MscTrackingResult;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shipping.tracking.client", havingValue = "playwright")
public class PlaywrightMscTrackingClient implements MscTrackingClient {
    private final MscTrackingEventParser eventParser;
    private final ShippingTrackingProperties properties;

    public PlaywrightMscTrackingClient(MscTrackingEventParser eventParser, ShippingTrackingProperties properties) {
        this.eventParser = eventParser;
        this.properties = properties;
    }

    @Override
    public MscTrackingQueryResult queryBooking(String bookingNo) {
        try (MscBrowserTracker tracker = new MscBrowserTracker(
                properties.getChromiumLaunchTimeoutMs(),
                properties.isChromiumHeadless(),
                properties.isChromiumStealthEnabled())) {
            MscTrackingResult result = tracker.query(
                    bookingNo,
                    MscTrackingQueryType.BOOKING,
                    1,
                    Path.of(properties.getScreenshotsDir()),
                    List.of(bookingNo));
            List<ShippingTrackingEvent> events = eventParser.parse(result.rawText());
            return new MscTrackingQueryResult(
                    result.status(),
                    result.rawText(),
                    result.parsedCurrentStatus(),
                    result.parsedEta(),
                    result.parsedLatestNode(),
                    events,
                    result.screenshotPath(),
                    result.errorReason(),
                    OffsetDateTime.parse(result.queriedAt()));
        }
    }
}
