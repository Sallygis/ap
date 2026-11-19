package com.ap.helpdesk.server.report;

import java.util.List;

import com.ap.helpdesk.server.ticket.Ticket;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Queries the report totals, matching tickets, and current technician workload. */
public interface ReportRepository extends Repository<Ticket, Long> {
    // Use the same UTC bounds in the totals and in the rows behind each report.
    String CREATION_RANGE = """
            ((:fromEpoch is null or unix_timestamp(created_at) >= :fromEpoch)
             and (:toEpoch is null or unix_timestamp(created_at) < :toEpoch))
            """;

    String RESOLUTION_RANGE = """
            (resolved_at is not null
             and (:fromEpoch is null or unix_timestamp(resolved_at) >= :fromEpoch)
             and (:toEpoch is null or unix_timestamp(resolved_at) < :toEpoch))
            """;

    String OPEN_STATUS = "status not in ('RESOLVED', 'CLOSED')";
    String RESOLUTION_SECONDS = "(unix_timestamp(resolved_at) - unix_timestamp(created_at))";

    @Query(value = "select count(case when " + CREATION_RANGE + " then 1 end) as submitted,"
            + " count(case when " + RESOLUTION_RANGE + " then 1 end) as resolved,"
            + " count(case when " + OPEN_STATUS + " and " + CREATION_RANGE + " then 1 end) as open,"
            + " avg(case when " + RESOLUTION_RANGE + " then " + RESOLUTION_SECONDS + " end) as averageResolutionSeconds"
            + " from tickets", nativeQuery = true)
    TicketTotals summarize(@Param("fromEpoch") Long fromEpoch, @Param("toEpoch") Long toEpoch);

    /** Uses the same date and status rules as the summary counts. */
    @Query(value = "select id as ticketId, subject, priority, status, " + RESOLUTION_SECONDS + " as resolutionSeconds"
            + " from tickets"
            + " where (:metric = 'SUBMITTED' and " + CREATION_RANGE + ")"
            + " or (:metric = 'RESOLVED' and " + RESOLUTION_RANGE + ")"
            + " or (:metric = 'OPEN' and " + OPEN_STATUS + " and " + CREATION_RANGE + ")"
            + " order by case when :metric = 'RESOLVED' then resolved_at else created_at end desc, id desc",
            nativeQuery = true)
    List<TicketDetail> ticketDetails(@Param("metric") String metric,
            @Param("fromEpoch") Long fromEpoch, @Param("toEpoch") Long toEpoch);

    // Include technicians with no open assignments.
    @Query(value = """
            select tech.user_id as technicianId, u.full_name as fullName, count(t.id) as openTickets
            from technicians tech
            join users u on u.id = tech.user_id
            left join tickets t on t.assigned_technician_id = tech.user_id
                               and t.status not in ('RESOLVED', 'CLOSED')
            where u.role = 'TECHNICIAN'
            group by tech.user_id, u.full_name
            order by openTickets desc, u.full_name, tech.user_id
            """, nativeQuery = true)
    List<WorkloadTotals> technicianWorkload();

    interface TicketDetail {
        long getTicketId();
        String getSubject();
        String getPriority();
        String getStatus();
        Double getResolutionSeconds();
    }

    interface TicketTotals {
        long getSubmitted();
        long getResolved();
        long getOpen();
        Double getAverageResolutionSeconds();
    }

    interface WorkloadTotals {
        long getTechnicianId();
        String getFullName();
        long getOpenTickets();
    }
}
