package com.ap.helpdesk.server.ticket;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Maps a comment to its ticket and author. */
@Entity
@Table(name = "comments")
public class TicketComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketComment() {}

    TicketComment(Ticket ticket, long authorId, String body, Instant createdAt) {
        this.ticket = ticket;
        this.authorId = authorId;
        this.body = body;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    Ticket getTicket() {
        return ticket;
    }

    long getAuthorId() {
        return authorId;
    }

    String getBody() {
        return body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

}
