package com.ap.helpdesk.server.notification;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import com.ap.helpdesk.server.account.AccountService.Profile;
import com.ap.helpdesk.server.account.UserAccount.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/** Issues subscription grants and forwards saved changes to the TCP server. */
@Component
public class NotificationPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationPublisher.class);
    private final NotificationToken tokens;
    private final int port;

    public NotificationPublisher(@Value("${HELPDESK_NOTIFICATIONS_SECRET:}") String secret,
            @Value("${HELPDESK_NOTIFICATIONS_PORT:9090}") int port) {
        this.tokens = secret.isBlank() ? null : new NotificationToken(secret);
        this.port = port;
    }

    /** Gives the desktop client the TCP port and a short-lived access grant. */
    public Subscription subscription(Profile account) {
        if (tokens == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notifications are disabled");
        }
        String payload = "SUBSCRIBE\t" + (NotificationToken.now() + 120) + "\t" + account.userId()
                + "\t" + (account.role() == Role.ADMINISTRATOR);
        return new Subscription(port, tokens.sign(payload));
    }

    /** Sends a notification after the ticket transaction commits successfully. */
    @TransactionalEventListener
    public void ticketSaved(TicketNotification event) {
        // The transaction has committed, so these entries describe saved changes.
        switch (event.type()) {
            case CREATED -> LOG.info("Ticket {} created by user {}", event.ticketId(), event.actorId());
            case URGENT -> LOG.info("Urgent ticket {} created by user {}", event.ticketId(), event.actorId());
            case ASSIGNED -> LOG.info("Ticket {} assigned to technician {} by user {}",
                    event.ticketId(), event.technicianId(), event.actorId());
            case RESOLVED -> LOG.info("Ticket {} resolved by technician {}", event.ticketId(), event.actorId());
            case COMMENT -> LOG.info("User {} added a comment to ticket {}", event.actorId(), event.ticketId());
            case STATUS_CHANGED -> LOG.info("User {} changed the status of ticket {}", event.actorId(), event.ticketId());
            case DETAILS_CHANGED -> LOG.info("User {} edited ticket {}", event.actorId(), event.ticketId());
        }
        if (tokens != null) {
            Thread.startVirtualThread(() -> deliver(event));
        }
    }

    private void deliver(TicketNotification event) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            var output = new DataOutputStream(socket.getOutputStream());
            var input = new DataInputStream(socket.getInputStream());
            String payload = String.join("\t", "TICKET", Long.toString(NotificationToken.now() + 15),
                    event.type().name(), Long.toString(event.ticketId()), Long.toString(event.requesterId()),
                    Long.toString(event.technicianId()), Long.toString(event.actorId()), event.actorName());
            output.writeUTF(tokens.sign(payload));
            if (!input.readUTF().equals("OK")) {
                LOG.error("Notification server did not confirm {} for ticket {}", event.type(), event.ticketId());
            }
        } catch (IOException failure) {
            LOG.error("Could not deliver {} notification for ticket {}", event.type(), event.ticketId(), failure);
        }
    }

    public record Subscription(int port, String token) {}
}
