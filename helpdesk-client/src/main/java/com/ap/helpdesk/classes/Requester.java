package com.ap.helpdesk.classes;

/** A student or staff member who can submit and track their own tickets. */
public final class Requester extends User {
    /** Student and staff accounts share the same requester permissions. */
    public enum Kind {
        STUDENT,
        STAFF
    }

    private final Kind kind;

    public Requester(long userId, String fullName, String email, Kind kind) {
        super(userId, fullName, email);
        this.kind = kind;
    }

    @Override
    public Role getRole() {
        return Role.REQUESTER;
    }

    public Kind getKind() {
        return kind;
    }
}
