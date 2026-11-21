package com.ticketflow.paymentservice.controller;

import com.ticketflow.paymentservice.entities.Payment;
import com.ticketflow.paymentservice.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<Payment> pay(
            @RequestBody PaymentRequest request) {

        Payment payment = paymentService.pay(
                request.bookingId(),
                request.amount(),
                request.success()
        );

        return ResponseEntity.ok(payment);
    }

    public record PaymentRequest(
            Long bookingId,
            BigDecimal amount,
            boolean success
    ) {}
}