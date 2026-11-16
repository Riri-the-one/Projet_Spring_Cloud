package com.ticketflow.notificationservice.repo;
import com.ticketflow.notificationservice.domain.Notification;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface NotificationRepo extends JpaRepository<Notification,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from Notification n where n.eventId=:id") Optional<Notification> locked(@Param("id") UUID id);
    List<Notification> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(Collection<Notification.Status> statuses,Instant now,Pageable pageable);
    Page<Notification> findByCustomerId(long id,Pageable pageable);
}
