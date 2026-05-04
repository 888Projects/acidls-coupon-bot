package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Stores the Randgo SessionToken in the database.
 * Survives server restarts — no need to re-login unnecessarily.
 *
 * CRITICAL: Randgo suspends account for 2 hours if Login
 * is called more than once per day in production.
 */
@Entity
@Table(name = "randgo_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RandgoSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_token", nullable = false, length = 500)
    private String sessionToken;

    @Column(name = "account_guid")
    private String accountGuid;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Token is valid for 24hrs on sliding scale
    // We refresh 1 hour before expiry to be safe
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "is_active")
    private boolean active = true;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        // Randgo token expires after 24hrs of inactivity (sliding)
        expiresAt = LocalDateTime.now().plusHours(23); // refresh 1hr early
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Extend token expiry — called every time we successfully use the token.
     * Randgo uses a sliding 24hr window.
     */
    public void extendExpiry() {
        this.expiresAt = LocalDateTime.now().plusHours(23);
        this.lastUsedAt = LocalDateTime.now();
    }
}
