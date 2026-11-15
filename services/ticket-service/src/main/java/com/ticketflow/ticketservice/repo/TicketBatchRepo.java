package com.ticketflow.ticketservice.repo;
import com.ticketflow.ticketservice.domain.TicketBatch;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketBatchRepo extends JpaRepository<TicketBatch, Long> {}
