-- AP Helpdesk database setup for MySQL/MariaDB.
-- Schema derived from Helpdesk(1).zip, helpdesk-server/src/main.
-- Import this file in phpMyAdmin using an account with CREATE privileges.
-- Existing tables and rows are preserved. This script sets up a fresh schema
-- and does not migrate existing tables with different column definitions.

CREATE DATABASE IF NOT EXISTS helpdesk
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE helpdesk;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('STUDENT', 'STAFF', 'TECHNICIAN', 'ADMINISTRATOR') NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS technicians (
    user_id BIGINT NOT NULL,

    PRIMARY KEY (user_id),
    CONSTRAINT fk_technicians_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tickets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requester_id BIGINT NOT NULL,
    assigned_technician_id BIGINT DEFAULT NULL,
    subject VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status ENUM(
        'NEW',
        'ASSIGNED',
        'IN_PROGRESS',
        'PENDING',
        'RESOLVED',
        'CLOSED',
        'REOPENED'
    ) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    resolution_notes TEXT,
    resolved_at TIMESTAMP(6) NULL DEFAULT NULL,

    PRIMARY KEY (id),
    KEY idx_tickets_requester (requester_id),
    KEY idx_tickets_assignee_status (assigned_technician_id, status),

    CONSTRAINT fk_tickets_requester
        FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_tickets_technician
        FOREIGN KEY (assigned_technician_id) REFERENCES technicians (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,

    PRIMARY KEY (id),
    KEY idx_comments_ticket_created (ticket_id, created_at),
    KEY idx_comments_author (author_id),

    CONSTRAINT fk_comments_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT fk_comments_author
        FOREIGN KEY (author_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
