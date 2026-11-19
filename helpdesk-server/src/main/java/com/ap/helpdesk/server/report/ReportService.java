package com.ap.helpdesk.server.report;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Builds administrator reports from saved tickets. */
@Service
@Transactional(readOnly = true)
public class ReportService {
    private final ReportRepository reports;

    public ReportService(ReportRepository reports) {
        this.reports = reports;
    }

    /** Counts submissions by creation date and resolutions by recorded resolution date. */
    public TicketSummary ticketSummary(LocalDate from, LocalDate to) {
        DateRange range = dateRange(from, to);
        var totals = reports.summarize(range.start(), range.end());
        return new TicketSummary(totals.getSubmitted(), totals.getResolved(), totals.getOpen(),
                totals.getAverageResolutionSeconds());
    }

    /** Lists the tickets counted by a summary metric. */
    public List<TicketDetail> ticketDetails(TicketMetric metric, LocalDate from, LocalDate to) {
        DateRange range = dateRange(from, to);
        return reports.ticketDetails(metric.name(), range.start(), range.end()).stream()
                .map(row -> new TicketDetail(row.getTicketId(), row.getSubject(), row.getPriority(),
                        row.getStatus(), row.getResolutionSeconds()))
                .toList();
    }

    /** Turns optional calendar dates into the bounds shared by all ticket reports. */
    private static DateRange dateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must be before or on the end date");
        }
        if ((from != null && (from.getYear() < 1 || from.getYear() > 9999))
                || (to != null && (to.getYear() < 1 || to.getYear() > 9999))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use dates with a four-digit year");
        }

        Long fromEpoch = from == null ? null : startOfDay(from);
        // An exclusive upper bound includes all of the selected end date.
        Long toEpoch = to == null || to.equals(LocalDate.of(9999, 12, 31)) ? null : startOfDay(to.plusDays(1));
        return new DateRange(fromEpoch, toEpoch);
    }

    /** Counts each technician's current open assignments. */
    public List<TechnicianWorkload> technicianWorkload() {
        return reports.technicianWorkload().stream()
                .map(row -> new TechnicianWorkload(row.getTechnicianId(), row.getFullName(), row.getOpenTickets()))
                .toList();
    }

    private static long startOfDay(LocalDate date) {
        return date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    private record DateRange(Long start, Long end) {}

    public enum TicketMetric { SUBMITTED, RESOLVED, OPEN }

    public record TicketDetail(long ticketId, String subject, String priority, String status, Double resolutionSeconds) {}

    public record TicketSummary(long submitted, long resolved, long open, Double averageResolutionSeconds) {}

    public record TechnicianWorkload(long technicianId, String fullName, long openTickets) {}
}
