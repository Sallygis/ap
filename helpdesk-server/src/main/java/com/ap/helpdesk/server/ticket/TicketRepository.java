package com.ap.helpdesk.server.ticket;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Saves tickets and runs queries with the dashboard's filters. */
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {
    /** Checks for active work while a technician's role is changing. */
    @Query(value = """
            select id from tickets
            where assigned_technician_id = :userId and status in ('ASSIGNED', 'IN_PROGRESS', 'PENDING')
            limit 1 for update
            """, nativeQuery = true)
    Optional<Long> findActiveTechnicianAssignments(@Param("userId") long userId);

    /** Locks one ticket until the service finishes changing it. */
    @Query(value = "select * from tickets where id = :id for update", nativeQuery = true)
    Optional<Ticket> findForUpdate(@Param("id") long id);
}
