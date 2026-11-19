package com.ap.helpdesk.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reports a missing ticket while hiding tickets the caller cannot view. */
public final class TicketNotFoundException extends ResponseStatusException {
    public TicketNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Ticket not found");
    }
}
