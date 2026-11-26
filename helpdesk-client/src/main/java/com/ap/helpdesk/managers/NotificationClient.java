package com.ap.helpdesk.managers;

import com.google.gson.JsonParser;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Maintains the signed-in account's TCP connection on a background thread. */
final class NotificationClient {
    private static final Logger LOG = LogManager.getLogger(NotificationClient.class);
    private final AccountClient session;
    private Thread reader;

    NotificationClient(AccountClient session) {
        this.session = session;
    }

    void start(Consumer<String> onUpdate) {
        stop();
        reader = Thread.startVirtualThread(() -> listen(onUpdate));
    }

    void stop() {
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
    }

    private void listen(Consumer<String> onUpdate) {
        boolean warned = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Use the signed-in REST session to get a short-lived grant for the TCP connection.
                String body = session.get("/api/notifications/subscription").get();
                var grant = JsonParser.parseString(body).getAsJsonObject();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(session.serverHost(), grant.get("port").getAsInt()), 2000);
                    socket.setSoTimeout(15000);
                    try (DataInputStream input = new DataInputStream(socket.getInputStream());
                         DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                        output.writeUTF(grant.get("token").getAsString());
                        if (!input.readUTF().equals("READY")) {
                            throw new IOException("Notification subscription was rejected");
                        }
                        if (warned) {
                            LOG.info("Ticket notification connection restored");
                        }
                        warned = false;
                        // Refresh after reconnecting because ticket changes may have arrived in the meantime.
                        onUpdate.accept("REFRESH");
                        while (true) {
                            String message = input.readUTF();
                            if (!message.isEmpty()) {
                                onUpdate.accept(message);
                            }
                        }
                    }
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | ExecutionException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                // An expired grant ends the stream normally.
                if (!(failure instanceof EOFException) && !warned) {
                    LOG.warn("Ticket notification connection unavailable. Retrying.", failure);
                    warned = true;
                }
                // A server restart or expired subscription can be recovered by reconnecting.
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
