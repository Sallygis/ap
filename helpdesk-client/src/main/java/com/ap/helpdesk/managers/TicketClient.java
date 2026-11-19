package com.ap.helpdesk.managers;

import com.ap.helpdesk.classes.Ticket;
import com.ap.helpdesk.classes.Ticket.TicketStatus;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Calls the ticket endpoints through the existing signed-in HTTP session. */
final class TicketClient {
    private static final String TICKETS = "/api/tickets";
    private final AccountClient session;

    TicketClient(AccountClient session) {
        this.session = session;
    }

    /** Loads the tickets visible to the signed-in account. */
    CompletableFuture<List<Ticket>> listTickets() {
        return session.get(TICKETS).thenApply(body -> {
            List<Ticket> tickets = new ArrayList<>();
            for (JsonElement entry : JsonParser.parseString(body).getAsJsonArray()) {
                tickets.add(readTicket(entry.getAsJsonObject()));
            }
            return tickets;
        });
    }

    /** Loads the details for one selected ticket. */
    CompletableFuture<Ticket> getTicket(long id) {
        return session.get(TICKETS + "/" + id).thenApply(TicketClient::readTicket);
    }

    /** Opens a new ticket for the account associated with the session. */
    CompletableFuture<Ticket> createTicket(String subject, String description, String priority) {
        return session.write("POST", TICKETS, Map.of("subject", subject, "description", description, "priority", priority))
                .thenApply(TicketClient::readTicket);
    }

    /** Saves a requester's revised subject and description. */
    CompletableFuture<Ticket> editDetails(long id, String subject, String description) {
        return session.write("PATCH", TICKETS + "/" + id, Map.of("subject", subject, "description", description))
                .thenApply(TicketClient::readTicket);
    }

    /** Changes the status and sends resolution notes when supplied. */
    CompletableFuture<Ticket> updateStatus(long id, TicketStatus status, String notes) {
        Map<String, String> fields = notes == null
                ? Map.of("status", status.name())
                : Map.of("status", status.name(), "resolutionNotes", notes);
        return session.write("PATCH", TICKETS + "/" + id + "/status", fields)
                .thenApply(TicketClient::readTicket);
    }

    /** Assigns the ticket to the selected technician. */
    CompletableFuture<Ticket> assignTicket(long id, long technicianId) {
        return session.write("PATCH", TICKETS + "/" + id + "/assignment", Map.of("technicianId", technicianId))
                .thenApply(TicketClient::readTicket);
    }

    /** Saves a comment under the signed-in account. */
    CompletableFuture<String> addComment(long id, String body) {
        return session.write("POST", TICKETS + "/" + id + "/comments", Map.of("body", body));
    }

    /** Returns the real technician choices for the administrator's assignment form. */
    CompletableFuture<Map<String, Long>> listTechnicians() {
        return session.get("/api/technicians").thenApply(body -> {
            Map<String, Long> choices = new LinkedHashMap<>();
            for (JsonElement entry : JsonParser.parseString(body).getAsJsonArray()) {
                JsonObject technician = entry.getAsJsonObject();
                long id = technician.get("userId").getAsLong();
                String name = technician.get("fullName").getAsString();
                choices.put(name + " (#" + id + ")", id);
            }
            return choices;
        });
    }

    private static Ticket readTicket(String body) {
        return readTicket(JsonParser.parseString(body).getAsJsonObject());
    }

    private static Ticket readTicket(JsonObject ticket) {
        // The same decoder handles list rows, ticket details, and saved changes.
        String technicianId = optionalField(ticket, "assignedTechnicianId");
        String resolvedAt = optionalField(ticket, "resolvedAt");
        return new Ticket(
                ticket.get("ticketId").getAsLong(),
                ticket.get("requesterId").getAsLong(),
                ticket.get("subject").getAsString(),
                ticket.get("description").getAsString(),
                Instant.parse(ticket.get("createdAt").getAsString()),
                ticket.get("priority").getAsString(),
                TicketStatus.valueOf(ticket.get("status").getAsString()),
                technicianId == null ? null : Long.valueOf(technicianId),
                optionalField(ticket, "assignedTechnicianName"),
                optionalField(ticket, "resolutionNotes"),
                resolvedAt == null ? null : Instant.parse(resolvedAt));
    }

    private static String optionalField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
