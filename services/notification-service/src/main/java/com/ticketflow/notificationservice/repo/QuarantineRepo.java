package com.ticketflow.notificationservice.repo;
import com.ticketflow.notificationservice.domain.Quarantine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuarantineRepo extends JpaRepository<Quarantine,UUID>{
    boolean existsByTopicAndPartitionAndOffset(String topic,int partition,long offset);
}
