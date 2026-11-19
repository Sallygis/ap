package com.ap.helpdesk.classes;

/** An account that manages users, ticket assignments, and reports. */
public final class Administrator extends User {
    public Administrator(long userId, String fullName, String email) {
        super(userId, fullName, email);
    }

    @Override
    public Role getRole() {
        return Role.ADMINISTRATOR;
    }
}
