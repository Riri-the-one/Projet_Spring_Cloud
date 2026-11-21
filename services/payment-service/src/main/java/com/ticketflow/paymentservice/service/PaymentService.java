package com.ticketflow.paymentservice.service;

import com.ticketflow.paymentservice.entities.Payment;
import com.ticketflow.paymentservice.repos.PaymentRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class PaymentService {

    private final PaymentRepo paymentRepo;

    public PaymentService(PaymentRepo paymentRepo) {
        this.paymentRepo = paymentRepo;
    }

    @Transactional
    public Payment pay(Long bookingId, BigDecimal amount, boolean success) {

        if (bookingId == null || bookingId <= 0) {
            throw new IllegalArgumentException("Invalid booking ID");
        }

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid amount");
        }

        // Éviter de créer deux paiements pour la même réservation
        Payment existing = paymentRepo.findByBookingId(bookingId)
                .orElse(null);

        if (existing != null) {
            return existing;
        }

        Payment payment = new Payment(bookingId, amount);

        if (success) {
            payment.succeed();
        } else {
            payment.fail();
        }

        return paymentRepo.save(payment);
    }
}