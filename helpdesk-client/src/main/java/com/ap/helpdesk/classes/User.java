package com.ap.helpdesk.classes;

/** Shared account details for the signed-in client user. */
public abstract class User {
    /** The role controls which dashboard and actions the client shows. */
    public enum Role {
        REQUESTER,
        TECHNICIAN,
        ADMINISTRATOR
    }

    private final long userId;
    private final String fullName;
    private final String email;

    protected User(long userId, String fullName, String email) {
        this.userId = userId;
        this.fullName = fullName;
        this.email = email;
    }

    public long getId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public abstract Role getRole();
}
