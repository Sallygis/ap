package com.ap.helpdesk.classes;

/** A support account that works on assigned tickets. */
public final class Technician extends User {
    public Technician(long userId, String fullName, String email) {
        super(userId, fullName, email);
    }

    @Override
    public Role getRole() {
        return Role.TECHNICIAN;
    }
}
