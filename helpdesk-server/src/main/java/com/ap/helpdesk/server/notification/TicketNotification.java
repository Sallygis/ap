package com.ap.helpdesk.server.notification;

/** Carries only the details needed to route and display a ticket notification. */
public record TicketNotification(Type type, long ticketId, long requesterId, long technicianId,
                                 long actorId, String actorName) {
    public enum Type { CREATED, ASSIGNED, RESOLVED, COMMENT, URGENT, STATUS_CHANGED, DETAILS_CHANGED }

    /** Chooses a visible message or a refresh for each account that may see this ticket. */
    String messageFor(long userId, boolean administrator) {
        if (userId == actorId || (!administrator && userId != requesterId && userId != technicianId)) {
            return null;
        }

        return switch (type) {
            case ASSIGNED -> userId == technicianId
                    ? "You were assigned ticket #" + ticketId + "." : "REFRESH";
            case RESOLVED -> userId == requesterId
                    ? "Ticket #" + ticketId + " was resolved by " + actorName + "." : "REFRESH";
            case COMMENT -> "New comment on ticket #" + ticketId + " from " + actorName + ".";
            case URGENT -> administrator ? "Urgent ticket #" + ticketId + " was submitted." : "REFRESH";
            case CREATED, STATUS_CHANGED, DETAILS_CHANGED -> "REFRESH";
        };
    }
}
