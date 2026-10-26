package com.saga.order.web;

import com.saga.order.service.OrderService;
import com.saga.order.web.OrderDtos.CreateOrderRequest;
import com.saga.order.web.OrderDtos.OrderResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreateResult result = service.create(request, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/orders/" + result.order().id()));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.order());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** 200 com o pedido ja CANCELLED (idempotente) ou 409 se ja estiver CONFIRMED. */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
