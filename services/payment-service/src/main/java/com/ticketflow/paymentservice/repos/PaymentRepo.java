package com.ticketflow.paymentservice.repos;

import com.ticketflow.paymentservice.entities.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepo extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

}