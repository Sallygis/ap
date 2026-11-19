package com.ap.helpdesk.server.controller;

import java.security.Principal;

import com.ap.helpdesk.server.account.AccountService;
import com.ap.helpdesk.server.notification.NotificationPublisher;
import com.ap.helpdesk.server.notification.NotificationPublisher.Subscription;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets a signed-in account connect to the separate notification server. */
@RestController
public class NotificationController {
    private final AccountService accounts;
    private final NotificationPublisher notifications;

    public NotificationController(AccountService accounts, NotificationPublisher notifications) {
        this.accounts = accounts;
        this.notifications = notifications;
    }

    /** Issues a TCP grant for the authenticated account and its current role. */
    @GetMapping("/api/notifications/subscription")
    public Subscription subscription(Principal user) {
        return notifications.subscription(accounts.getProfile(user.getName()));
    }
}
