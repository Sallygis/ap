package com.ap.helpdesk.server.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

/** Saves comments in the existing comments table. */
public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {}
