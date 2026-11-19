package com.ap.helpdesk.server.notification;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Runs independently from Spring Boot and relays ticket updates to connected clients. */
public final class NotificationServer {
    private static final Logger LOG = LogManager.getLogger(NotificationServer.class);
    private final NotificationToken tokens;
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    private NotificationServer(String secret) {
        tokens = new NotificationToken(secret);
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> environment = System.getenv();
        String secret = environment.get("HELPDESK_NOTIFICATIONS_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Set HELPDESK_NOTIFICATIONS_SECRET before starting the notification server");
        }
        int port = Integer.parseInt(environment.getOrDefault("HELPDESK_NOTIFICATIONS_PORT", "9090"));
        try {
            new NotificationServer(secret).listen(port);
        } catch (IOException failure) {
            LOG.error("Notification server stopped on port {}", port, failure);
            throw failure;
        }
    }

    /** Accepts subscriptions and published events, each on its own background thread. */
    private void listen(int port) throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            // The desktop clients and REST server use this local TCP listener.
            server.bind(new InetSocketAddress("127.0.0.1", port));
            LOG.info("Notification server listening on local port {}", port);
            while (true) {
                Socket socket = server.accept();
                Thread.startVirtualThread(() -> handle(socket));
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            // The first frame identifies a subscription or a ticket event signed by Spring Boot.
            String[] fields = tokens.verify(input.readUTF()).split("\t", 8);
            long expires = Long.parseLong(fields[1]);
            if (expires <= NotificationToken.now()) {
                return;
            }

            switch (fields[0]) {
                case "SUBSCRIBE" -> subscribe(socket, output, expires, Long.parseLong(fields[2]),
                        Boolean.parseBoolean(fields[3]));
                case "TICKET" -> {
                    var event = new TicketNotification(TicketNotification.Type.valueOf(fields[2]),
                            Long.parseLong(fields[3]), Long.parseLong(fields[4]),
                            Long.parseLong(fields[5]), Long.parseLong(fields[6]), fields[7]);
                    publish(event);
                    output.writeUTF("OK");
                }
                default -> throw new IllegalArgumentException("Unknown notification command");
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException failure) {
            LOG.debug("Notification connection closed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keeps the socket subscribed until it disconnects or its access grant expires. */
    private void subscribe(Socket socket, DataOutputStream output, long expires, long userId, boolean administrator)
            throws IOException, InterruptedException {
        var subscriber = new Subscriber(userId, administrator, socket, new ArrayBlockingQueue<>(32));
        subscribers.add(subscriber);
        try {
            output.writeUTF("READY");
            long remaining = expires - NotificationToken.now();
            while (remaining > 0) {
                // An empty frame is a heartbeat and never creates a notification in the UI.
                String message = subscriber.messages().poll(Math.min(remaining, 10), TimeUnit.SECONDS);
                if (expires <= NotificationToken.now()) {
                    break;
                }
                output.writeUTF(message == null ? "" : message);
                remaining = expires - NotificationToken.now();
            }
        } finally {
            subscribers.remove(subscriber);
        }
    }

    private void publish(TicketNotification event) {
        for (Subscriber subscriber : subscribers) {
            String message = event.messageFor(subscriber.userId(), subscriber.administrator());
            if (message != null && !subscriber.messages().offer(message)) {
                // Drop a stalled connection rather than delaying delivery to other clients.
                try {
                    subscriber.socket().close();
                } catch (IOException failure) {
                    LOG.warn("Could not close stalled notification client", failure);
                }
            }
        }
    }

    private record Subscriber(long userId, boolean administrator, Socket socket, ArrayBlockingQueue<String> messages) {}
}
