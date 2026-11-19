package com.ap.helpdesk.server.controller;

import java.time.LocalDate;
import java.util.List;

import com.ap.helpdesk.server.report.ReportService;
import com.ap.helpdesk.server.report.ReportService.TechnicianWorkload;
import com.ap.helpdesk.server.report.ReportService.TicketDetail;
import com.ap.helpdesk.server.report.ReportService.TicketMetric;
import com.ap.helpdesk.server.report.ReportService.TicketSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Handles reports for the administrator dashboard. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Returns the number of current open assignments for each technician. */
    @GetMapping("/technician-workload")
    public List<TechnicianWorkload> getTechnicianWorkload() {
        return reports.technicianWorkload();
    }

    /** Summarizes submitted, open, and resolved tickets and their resolution time. */
    @GetMapping("/tickets")
    public TicketSummary getTicketSummary(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return reports.ticketSummary(from, to);
    }

    /** Returns the ticket rows behind the selected summary metric. */
    @GetMapping("/tickets/details")
    public List<TicketDetail> getTicketDetails(
            @RequestParam("metric") TicketMetric metric,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return reports.ticketDetails(metric, from, to);
    }
}
