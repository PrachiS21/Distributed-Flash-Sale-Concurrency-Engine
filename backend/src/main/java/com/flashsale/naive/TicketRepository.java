package com.flashsale.naive;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    Optional<Ticket> findFirstByStatus(String status);

    // Locks the next AVAILABLE ticket(s) with SELECT ... FOR UPDATE SKIP LOCKED.
    // "-2" is Hibernate's SKIP_LOCKED lock timeout: a concurrent caller never queues
    // on a row another transaction already locked, it just locks a different
    // still-available row instead. So at most one caller can ever claim a given
    // ticket, and callers don't serialize behind each other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from Ticket t where t.status = 'AVAILABLE' order by t.id")
    List<Ticket> lockNextAvailable(Pageable pageable);
}
