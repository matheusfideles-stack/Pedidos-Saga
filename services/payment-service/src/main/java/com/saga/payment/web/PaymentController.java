package com.saga.payment.web;

import com.saga.payment.domain.Payment;
import com.saga.payment.persistence.PaymentRepository;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<Payment> byOrder(@PathVariable UUID orderId) {
        return payments.findByOrder(orderId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
