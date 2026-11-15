package com.ticketflow.ticketservice.repo;
import com.ticketflow.ticketservice.domain.Ticket;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketRepo extends JpaRepository<Ticket, UUID> {
    List<Ticket> findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(long bookingId);
    List<Ticket> findByBatchCustomerIdOrderByIssuedAtDesc(long customerId);
}
