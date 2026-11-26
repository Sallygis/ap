package com.ap.helpdesk.classes;

public final class Requester extends User {
    private final Role role;

    public Requester(long userId, String fullName, String email, Role role) {
        super(userId, fullName, email);

        if (role != Role.STUDENT && role != Role.STAFF) {
            throw new IllegalArgumentException(
                    "A requester must be a student or staff member");
        }

        this.role = role;
    }

    @Override
    public Role getRole() {
        return role;
    }
}
