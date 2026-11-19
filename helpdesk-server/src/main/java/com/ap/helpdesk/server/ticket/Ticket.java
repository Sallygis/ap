package com.ap.helpdesk.server.ticket;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps a support ticket to a row in the tickets table. */
@Entity
@Table(name = "tickets")
public class Ticket {
    public enum TicketStatus {
        NEW,
        ASSIGNED,
        IN_PROGRESS,
        PENDING,
        RESOLVED,
        CLOSED,
        REOPENED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private long requesterId;

    @Column(name = "assigned_technician_id")
    private Long assignedTechnicianId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 16)
    private String priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Ticket() {}

    Ticket(long requesterId, String subject, String description, String priority, Instant createdAt) {
        this.requesterId = requesterId;
        this.subject = subject;
        this.description = description;
        this.priority = priority;
        this.createdAt = createdAt;
        this.status = TicketStatus.NEW;
    }

    /** Updates the requester-provided text. */
    void editDetails(String subject, String description) {
        this.subject = subject;
        this.description = description;
    }

    /** Saves the assignee and marks the ticket assigned. */
    void assignTo(long technicianId) {
        assignedTechnicianId = technicianId;
        status = TicketStatus.ASSIGNED;
    }

    /** Updates the status and maintains the resolution timestamp. */
    void updateStatus(TicketStatus next, String notes, Instant now) {
        if (next == TicketStatus.RESOLVED && status != TicketStatus.RESOLVED) {
            resolvedAt = now;
        } else if (next == TicketStatus.REOPENED) {
            resolvedAt = null;
        }
        status = next;
        if (notes != null) {
            resolutionNotes = notes;
        }
    }

    Long getId() {
        return id;
    }

    long getRequesterId() {
        return requesterId;
    }

    Long getAssignedTechnicianId() {
        return assignedTechnicianId;
    }

    String getSubject() {
        return subject;
    }

    String getDescription() {
        return description;
    }

    String getPriority() {
        return priority;
    }

    TicketStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getResolutionNotes() {
        return resolutionNotes;
    }

    Instant getResolvedAt() {
        return resolvedAt;
    }

}
