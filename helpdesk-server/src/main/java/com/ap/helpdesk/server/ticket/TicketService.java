package com.ap.helpdesk.server.ticket;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.ap.helpdesk.server.account.AccountService;
import com.ap.helpdesk.server.account.AccountService.Profile;
import com.ap.helpdesk.server.account.TechnicianRepository;
import com.ap.helpdesk.server.account.TechnicianRepository.TechnicianSummary;
import com.ap.helpdesk.server.account.UserAccount.Role;
import com.ap.helpdesk.server.exception.TicketAssignmentException;
import com.ap.helpdesk.server.exception.TicketNotFoundException;
import com.ap.helpdesk.server.notification.TicketNotification;
import com.ap.helpdesk.server.notification.TicketNotification.Type;
import com.ap.helpdesk.server.ticket.Ticket.TicketStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Applies ticket permissions and saves changes through the repositories. */
@Service
public class TicketService {
    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final TechnicianRepository technicians;
    private final AccountService accounts;
    private final ApplicationEventPublisher events;

    public TicketService(TicketRepository tickets, TicketCommentRepository comments,
            TechnicianRepository technicians, AccountService accounts, ApplicationEventPublisher events) {
        this.tickets = tickets;
        this.comments = comments;
        this.technicians = technicians;
        this.accounts = accounts;
        this.events = events;
    }

    private static boolean canSubmitTickets(Profile actor) {
        return actor.role() == Role.STUDENT || actor.role() == Role.STAFF;
    }
    
    /** Returns the signed-in user's visible tickets in newest-first order. */
    @Transactional(readOnly = true)
    public List<TicketView> listTickets(String email, TicketFilters filters) {
        Profile actor = signedInAccount(email);
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must be before or on the end date");
        }
        Sort order = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        List<Ticket> visible = tickets.findAll(visibleTickets(actor, filters), order);
        if (actor.role() != Role.ADMINISTRATOR) {
            return visible.stream().map(TicketService::viewOf).toList();
        }

        // Resolve names in one query for the administrator's ticket table.
        Map<Long, String> assignees = assignedNames(visible);
        return visible.stream().map(ticket -> viewOf(ticket, assigneeFrom(ticket, assignees))).toList();
    }

    /** Finds one ticket within the same role and ownership rules as the list. */
    @Transactional(readOnly = true)
    public TicketView getTicket(String email, long id) {
        Profile actor = signedInAccount(email);
        Ticket ticket = requireVisible(tickets.findById(id).orElseThrow(TicketNotFoundException::new), actor);
        if (actor.role() != Role.ADMINISTRATOR) {
            return viewOf(ticket);
        }
        return viewOf(ticket, assigneeFrom(ticket, assignedNames(List.of(ticket))));
    }

    /** Creates a fresh ticket for the signed-in requester. */
    @Transactional
    public TicketView createTicket(String email, String subject, String description, String priority) {
        Profile actor = signedInAccount(email);
        if (!canSubmitTickets(actor)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Student or staff access required");
        }
        // The server supplies the owner and creation time.
        var ticket = new Ticket(actor.userId(), subject.strip(), description.strip(), priority.strip(), now());
        Ticket saved = tickets.save(ticket);
        publishNotification("URGENT".equalsIgnoreCase(saved.getPriority()) ? Type.URGENT : Type.CREATED, saved, actor);
        return viewOf(saved);
    }

    /** Updates the original requester's text while the ticket is still new. */
    @Transactional
    public TicketView editDetails(String email, long id, String subject, String description) {
        Profile actor = signedInAccount(email);
        if (!canSubmitTickets(actor)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Student or staff access required");
        }
        // Assignment uses the same ticket lock, so the status cannot change during this check.
        Ticket ticket = requireVisible(tickets.findForUpdate(id).orElseThrow(TicketNotFoundException::new), actor);
        if (ticket.getStatus() != TicketStatus.NEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only new tickets can be edited");
        }

        ticket.editDetails(subject.strip(), description.strip());
        publishNotification(Type.DETAILS_CHANGED, ticket, actor);
        return viewOf(ticket);
    }

    /** Applies a status change to an owned or assigned ticket. */
    @Transactional
    public TicketView updateStatus(String email, long id, TicketStatus next, String notes) {
        Profile actor = signedInAccount(email);
        Ticket ticket = requireVisible(tickets.findForUpdate(id).orElseThrow(TicketNotFoundException::new), actor);
        if (canSubmitTickets(actor)) {
            checkRequesterStatus(ticket.getStatus(), next, notes);
        } else {
            requireRole(actor, Role.TECHNICIAN);
            checkTechnicianStatus(ticket.getStatus(), next);
        }
        boolean resolved = next == TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.RESOLVED;
        ticket.updateStatus(next, notes, now());
        publishNotification(resolved ? Type.RESOLVED : Type.STATUS_CHANGED, ticket, actor);
        return viewOf(ticket);
    }

    /** Assigns a new or reopened ticket to a technician. */
    @Transactional
    public TicketView assignTicket(String email, long id, long technicianId) {
        Profile actor = signedInAccount(email);
        requireRole(actor, Role.ADMINISTRATOR);

        // Lock the technician first so a concurrent role change cannot strand the assignment.
        var technicianName = technicians.findAssignableForUpdate(technicianId);
        // Lock the ticket so two administrators cannot both assign it.
        Ticket ticket = tickets.findForUpdate(id).orElseThrow(TicketNotFoundException::new);
        if (ticket.getStatus() != TicketStatus.NEW && ticket.getStatus() != TicketStatus.REOPENED) {
            throw new TicketAssignmentException(HttpStatus.CONFLICT, "Only a new or reopened ticket can be assigned");
        }
        String assignee = technicianName.orElseThrow(
                () -> new TicketAssignmentException(HttpStatus.NOT_FOUND, "Technician not found"));
        ticket.assignTo(technicianId);
        publishNotification(Type.ASSIGNED, ticket, actor);
        return viewOf(ticket, assignee);
    }

    /** Stores a comment with the signed-in account as its author. */
    @Transactional
    public CommentView addComment(String email, long id, String body) {
        Profile actor = signedInAccount(email);
        Ticket ticket = requireVisible(tickets.findForUpdate(id).orElseThrow(TicketNotFoundException::new), actor);
        var comment = comments.save(new TicketComment(ticket, actor.userId(), body.strip(), now()));
        publishNotification(Type.COMMENT, ticket, actor);
        return new CommentView(comment.getId(), comment.getTicket().getId(), comment.getAuthorId(),
                comment.getBody(), comment.getCreatedAt());
    }

    /** Captures the event for delivery after the surrounding transaction commits. */
    private void publishNotification(Type type, Ticket ticket, Profile actor) {
        Long technicianId = ticket.getAssignedTechnicianId();
        events.publishEvent(new TicketNotification(type, ticket.getId(), ticket.getRequesterId(),
                technicianId == null ? 0 : technicianId, actor.userId(), actor.fullName()));
    }

    /** Combines the account's visibility rules with the optional request filters. */
    private static Specification<Ticket> visibleTickets(Profile actor, TicketFilters filters) {
        return (ticket, query, criteria) -> {
            List<Predicate> matches = new ArrayList<>();

            switch (actor.role()) {
                case STUDENT, STAFF -> matches.add(criteria.equal(ticket.get("requesterId"), actor.userId()));
                case TECHNICIAN -> matches.add(criteria.equal(ticket.get("assignedTechnicianId"), actor.userId()));
                case ADMINISTRATOR -> {}
            }

            if (filters.id() != null) {
                matches.add(criteria.equal(ticket.get("id"), filters.id()));
            }
            if (filters.requesterId() != null) {
                matches.add(criteria.equal(ticket.get("requesterId"), filters.requesterId()));
            }
            if (filters.technicianId() != null) {
                matches.add(criteria.equal(ticket.get("assignedTechnicianId"), filters.technicianId()));
            }
            if (filters.status() != null) {
                matches.add(criteria.equal(ticket.get("status"), filters.status()));
            }
            if (filters.priority() != null) {
                matches.add(criteria.equal(ticket.get("priority"), filters.priority().strip()));
            }
            // Include the entire end date by comparing against midnight the next day in UTC.
            if (filters.from() != null) {
                matches.add(criteria.greaterThanOrEqualTo(ticket.get("createdAt"), startOfDay(filters.from())));
            }
            if (filters.to() != null) {
                matches.add(criteria.lessThan(ticket.get("createdAt"), startOfDay(filters.to().plusDays(1))));
            }
            return criteria.and(matches.toArray(Predicate[]::new));
        };
    }

    private Profile signedInAccount(String email) {
        try {
            return accounts.getProfile(email);
        } catch (UsernameNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again", exception);
        }
    }

    /** Hides tickets outside a requester's own tickets or a technician's assignments. */
    private static Ticket requireVisible(Ticket ticket, Profile actor) {
        boolean visible = switch (actor.role()) {
            case STUDENT, STAFF -> ticket.getRequesterId() == actor.userId();
            case TECHNICIAN -> Objects.equals(ticket.getAssignedTechnicianId(), actor.userId());
            case ADMINISTRATOR -> true;
        };
        if (!visible) {
            throw new TicketNotFoundException();
        }
        return ticket;
    }

    private static void requireRole(Profile actor, Role role) {
        if (actor.role() != role) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account cannot perform this action");
        }
    }

    private static void checkRequesterStatus(TicketStatus current, TicketStatus next, String notes) {
        boolean canReopen = next == TicketStatus.REOPENED
                && (current == TicketStatus.CLOSED || current == TicketStatus.RESOLVED);
        if (notes != null || (next != TicketStatus.CLOSED && !canReopen)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requesters can close or reopen their tickets");
        }
    }

    private static void checkTechnicianStatus(TicketStatus current, TicketStatus next) {
        if (next != TicketStatus.IN_PROGRESS && next != TicketStatus.PENDING && next != TicketStatus.RESOLVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose In Progress, Pending, or Resolved");
        }
        if (current == TicketStatus.CLOSED || (current == TicketStatus.RESOLVED && next != TicketStatus.RESOLVED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The ticket must be reopened before work resumes");
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** Resolves the names of current and historical assignees for administrator views. */
    private Map<Long, String> assignedNames(List<Ticket> visible) {
        List<Long> ids = visible.stream().map(Ticket::getAssignedTechnicianId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return technicians.findAssignedNames(ids).stream()
                .collect(Collectors.toMap(TechnicianSummary::getUserId, TechnicianSummary::getFullName));
    }

    private static String assigneeFrom(Ticket ticket, Map<Long, String> names) {
        Long id = ticket.getAssignedTechnicianId();
        return id == null ? null : names.get(id);
    }

    private static TicketView viewOf(Ticket ticket) {
        return viewOf(ticket, null);
    }

    private static TicketView viewOf(Ticket ticket, String assignee) {
        return new TicketView(ticket.getId(), ticket.getRequesterId(), ticket.getSubject(), ticket.getDescription(),
                ticket.getCreatedAt(), ticket.getPriority(), ticket.getStatus(), ticket.getAssignedTechnicianId(),
                assignee, ticket.getResolutionNotes(), ticket.getResolvedAt());
    }

    public record TicketFilters(Long id, Long requesterId, Long technicianId, TicketStatus status, String priority,
                                LocalDate from, LocalDate to) {}

    public record TicketView(long ticketId, long requesterId, String subject, String description, Instant createdAt,
                             String priority, TicketStatus status, Long assignedTechnicianId,
                             String assignedTechnicianName, String resolutionNotes, Instant resolvedAt) {}

    public record CommentView(long commentId, long ticketId, long authorId, String body, Instant createdAt) {}
}
