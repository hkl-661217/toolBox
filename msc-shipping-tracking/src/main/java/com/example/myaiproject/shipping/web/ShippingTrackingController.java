package com.example.myaiproject.shipping.web;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.service.ShippingTrackingService;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable long id) {
        trackingService.enableBinding(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateBindingRequest(String orderNo, String bookingNo) {
    }

    @ExceptionHandler({IllegalArgumentException.class, DuplicateKeyException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception error) {
        String message = error instanceof DuplicateKeyException
                ? "订单号或订舱号已绑定，请检查现有绑定列表"
                : error.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message == null ? "请求参数无效" : message));
    }
}
