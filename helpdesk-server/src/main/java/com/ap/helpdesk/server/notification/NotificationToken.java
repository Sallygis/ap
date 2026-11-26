package com.ap.helpdesk.server.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs messages exchanged between Spring Boot and the notification server. */
final class NotificationToken {
	static final String SHARED_SECRET = "Notification_Key";
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final SecretKeySpec key;
    

    NotificationToken(String secret) {
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Encodes a payload and adds a signature the TCP server can verify. */
    String sign(String payload) {
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(signature(encoded));
    }

    String verify(String token) {
        String[] parts = token.split("[.]", 2);
        if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]), DECODER.decode(parts[1]))) {
            throw new IllegalArgumentException("Invalid notification token");
        }
        return new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
    }

    static long now() {
        return Instant.now().getEpochSecond();
    }

    private byte[] signature(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not sign notification message", failure);
        }
    }
}
