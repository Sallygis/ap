package com.ap.helpdesk.classes;

import java.time.Instant;

/** A snapshot of a support ticket for display in the client. */
public final class Ticket {
    /** The statuses used by ticket views, filters, and status controls. */
    public enum TicketStatus {
        NEW,
        ASSIGNED,
        IN_PROGRESS,
        PENDING,
        RESOLVED,
        CLOSED,
        REOPENED
    }

    private final long ticketId;
    private final long requesterId;
    private final String subject;
    private final String description;
    private final Instant createdAt;
    private final String priority;
    private final TicketStatus status;

    // The assignee and resolution fields may be absent from the server response.
    private final Long assignedTechnicianId;
    private final String assignedTechnicianName;
    private final String resolutionNotes;
    private final Instant resolvedAt;

    public Ticket(
            long ticketId,
            long requesterId,
            String subject,
            String description,
            Instant createdAt,
            String priority,
            TicketStatus status,
            Long assignedTechnicianId,
            String resolutionNotes,
            Instant resolvedAt) {
        this(ticketId, requesterId, subject, description, createdAt, priority, status, assignedTechnicianId,
                null, resolutionNotes, resolvedAt);
    }

    public Ticket(
            long ticketId,
            long requesterId,
            String subject,
            String description,
            Instant createdAt,
            String priority,
            TicketStatus status,
            Long assignedTechnicianId,
            String assignedTechnicianName,
            String resolutionNotes,
            Instant resolvedAt) {
        this.ticketId = ticketId;
        this.requesterId = requesterId;
        this.subject = subject;
        this.description = description;
        this.createdAt = createdAt;
        this.priority = priority;
        this.status = status;
        this.assignedTechnicianId = assignedTechnicianId;
        this.assignedTechnicianName = assignedTechnicianName;
        this.resolutionNotes = resolutionNotes;
        this.resolvedAt = resolvedAt;
    }

    public long getTicketId() {
        return ticketId;
    }

    public long getRequesterId() {
        return requesterId;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Long getAssignedTechnicianId() {
        return assignedTechnicianId;
    }

    /** Returns the display name included for administrators when the ticket is assigned. */
    public String getAssignedTechnicianName() {
        return assignedTechnicianName;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public boolean isAssigned() {
        // TODO: Check whether a technician is associated with the ticket.
        throw new UnsupportedOperationException("Assignment check is not implemented yet");
    }

    public boolean isClosed() {
        // TODO: Check whether the ticket has reached the closed status.
        throw new UnsupportedOperationException("Closed-status check is not implemented yet");
    }
}
