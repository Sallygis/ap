package com.ap.helpdesk.server.controller;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import com.ap.helpdesk.server.ticket.Ticket.TicketStatus;
import com.ap.helpdesk.server.ticket.TicketService;
import com.ap.helpdesk.server.ticket.TicketService.CommentView;
import com.ap.helpdesk.server.ticket.TicketService.TicketFilters;
import com.ap.helpdesk.server.ticket.TicketService.TicketView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Handles the ticket routes used by the JavaFX dashboards. */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    /** Lists visible tickets that match the supplied filters. */
    @GetMapping
    public List<TicketView> listTickets(
            Principal user,
            @RequestParam(name = "id", required = false) Long id,
            @RequestParam(name = "requesterId", required = false) Long requesterId,
            @RequestParam(name = "technicianId", required = false) Long technicianId,
            @RequestParam(name = "status", required = false) TicketStatus status,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return tickets.listTickets(user.getName(), new TicketFilters(id, requesterId, technicianId, status,
                priority, from, to));
    }

    /** Looks up a single ticket using the ID in the URL. */
    @GetMapping("/{id}")
    public TicketView getTicket(Principal user, @PathVariable("id") long id) {
        return tickets.getTicket(user.getName(), id);
    }

    /** Opens a ticket as the signed-in requester. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView createTicket(Principal user, @Valid @RequestBody CreateTicketRequest request) {
        return tickets.createTicket(user.getName(), request.subject(), request.description(), request.priority());
    }

    /** Saves the original requester's subject and description while the ticket is new. */
    @PatchMapping("/{id}")
    public TicketView editDetails(Principal user, @PathVariable("id") long id,
            @Valid @RequestBody EditTicketRequest request) {
        return tickets.editDetails(user.getName(), id, request.subject(), request.description());
    }

    /** Updates the ticket's status and optional resolution notes. */
    @PatchMapping("/{id}/status")
    public TicketView updateStatus(Principal user, @PathVariable("id") long id,
            @Valid @RequestBody UpdateStatusRequest request) {
        return tickets.updateStatus(user.getName(), id, request.status(), request.resolutionNotes());
    }

    /** Assigns the ticket to a technician as an administrator. */
    @PatchMapping("/{id}/assignment")
    public TicketView assignTicket(Principal user, @PathVariable("id") long id,
            @Valid @RequestBody AssignTicketRequest request) {
        return tickets.assignTicket(user.getName(), id, request.technicianId());
    }

    /** Adds a comment from the signed-in account. */
    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(Principal user, @PathVariable("id") long id,
            @Valid @RequestBody AddCommentRequest request) {
        return tickets.addComment(user.getName(), id, request.body());
    }

    public record CreateTicketRequest(
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 16000) String description,
            @NotBlank @Size(max = 16) String priority) {}

    public record EditTicketRequest(
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 16000) String description) {}

    public record UpdateStatusRequest(@NotNull TicketStatus status, @Size(max = 16000) String resolutionNotes) {}

    public record AssignTicketRequest(@NotNull @Positive Long technicianId) {}

    public record AddCommentRequest(@NotBlank @Size(max = 16000) String body) {}
}
