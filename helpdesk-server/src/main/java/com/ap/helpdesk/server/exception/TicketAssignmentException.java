package com.ap.helpdesk.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reports why a ticket assignment could not be saved. */
public final class TicketAssignmentException extends ResponseStatusException {
    private static final long serialVersionUID = 1L;

	public TicketAssignmentException(HttpStatus status, String reason) {
        super(status, reason);
    }
}
