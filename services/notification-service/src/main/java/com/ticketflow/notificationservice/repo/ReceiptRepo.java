package com.ticketflow.notificationservice.repo;
import com.ticketflow.notificationservice.domain.SimulatedReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReceiptRepo extends JpaRepository<SimulatedReceipt,UUID>{}
