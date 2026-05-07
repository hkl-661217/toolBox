package com.example.myaiproject.shipping.web;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.service.ShippingTrackingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipping-tracking/bindings")
public class ShippingTrackingController {
    private final ShippingTrackingService trackingService;

    public ShippingTrackingController(ShippingTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping
    public ShippingTrackingBinding create(@RequestBody CreateBindingRequest request) {
        return trackingService.createBinding(request.orderNo(), request.bookingNo());
    }

    @GetMapping
    public List<ShippingTrackingBinding> list() {
        return trackingService.listBindings();
    }

    @GetMapping("/{id}")
    public ShippingTrackingBinding get(@PathVariable long id) {
        return trackingService.getBinding(id);
    }

    @PostMapping("/{id}/sync")
    public ShippingTrackingBinding sync(@PathVariable long id) {
        return trackingService.syncBinding(id);
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable long id) {
        trackingService.disableBinding(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateBindingRequest(String orderNo, String bookingNo) {
    }
}
