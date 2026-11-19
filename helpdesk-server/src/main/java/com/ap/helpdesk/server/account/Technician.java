package com.ap.helpdesk.server.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** Maps a technician to the user account that shares its ID. */
@Entity
@Table(name = "technicians")
public class Technician {
    @Id
    @Column(name = "user_id")
    private Long userId;

    // The technician row uses the existing user ID as its primary key.
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount account;

    protected Technician() {}

    Technician(UserAccount account) {
        this.account = account;
    }
}
