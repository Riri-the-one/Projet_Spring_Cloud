package com.ticketflow.eventservice.repos;

import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface EventRepo extends JpaRepository<Event, Long> {

    List<Event> findByStatusOrderByStartsAtAsc(EventStatus status);

    List<Event> findByStatusAndVenueIgnoreCaseOrderByStartsAtAsc(EventStatus status, String venue);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);
    @Query("""
        select e from Event e
        where e.status = :status
          and (:venue = '' or lower(e.venue) = lower(:venue))
          and (:keyword = '' or locate(lower(:keyword), lower(e.title)) > 0)
        """)
    Page<Event> searchCatalog(
            @Param("status") EventStatus status,
            @Param("venue") String venue,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}
