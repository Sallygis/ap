package com.ap.helpdesk.server.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Returns the public details of help desk errors for the client's alerts. */
@RestControllerAdvice
public class HelpdeskExceptionHandler {
    @ExceptionHandler({TicketNotFoundException.class, UserNotFoundException.class, TicketAssignmentException.class})
    public ProblemDetail handleHelpdeskError(ResponseStatusException failure) {
        return failure.getBody();
    }
}
