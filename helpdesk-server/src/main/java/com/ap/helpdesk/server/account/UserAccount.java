package com.ap.helpdesk.server.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps an account to the existing users table. */
@Entity
@Table(name = "users")
public class UserAccount {
    public enum Role {
        REQUESTER, TECHNICIAN, ADMINISTRATOR
    }

    /** Students and staff use the same requester role. */
    public enum RequesterKind {
        STUDENT, STAFF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "requester_kind")
    private RequesterKind requesterKind;

    protected UserAccount() {}

    UserAccount(String fullName, String email, String passwordHash, RequesterKind requesterKind) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = Role.REQUESTER;
        this.requesterKind = requesterKind;
    }

    /** Updates the role and keeps the requester category in sync. */
    void changeRole(Role role, RequesterKind requesterKind) {
        this.role = role;
        this.requesterKind = requesterKind;
    }

    Long getId() {
        return id;
    }

    String getFullName() {
        return fullName;
    }

    String getEmail() {
        return email;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    Role getRole() {
        return role;
    }

    RequesterKind getRequesterKind() {
        return requesterKind;
    }
}
