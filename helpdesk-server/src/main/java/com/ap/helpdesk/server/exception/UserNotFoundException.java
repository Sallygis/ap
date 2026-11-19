package com.ap.helpdesk.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reports a missing account in administrator account management. */
public final class UserNotFoundException extends ResponseStatusException {
    public UserNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Account not found");
    }
}
