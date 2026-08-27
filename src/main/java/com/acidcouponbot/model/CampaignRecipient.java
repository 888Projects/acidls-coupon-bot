package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One person in a campaign run. Carries its own RandGo progress so the runner is fully resumable from the
 * database.
 *
 * <p><b>UNIQUE(campaign_run_id, phone)</b> is the idempotency spine — it guarantees one row per person per
 * run, so a re-upload or a concurrent insert can never create a duplicate that would double-issue. The
 * (campaign_run_id, status) index backs the resume queries ("give me this run's PENDING / failed rows").
 *
 * <p>JPA auto-DDL (no Flyway in this repo).
 */
@Entity
@Table(
        name = "campaign_recipients",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_campaign_recipient_run_phone",
                columnNames = {"campaign_run_id", "phone"}),
        indexes = @Index(
                name = "idx_campaign_recipient_run_status",
                columnList = "campaign_run_id, status")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to campaign_runs.id, stored as a plain column (not a @ManyToOne) — the runner works in batches by
     *  run id and never needs to navigate the graph, so this keeps queries simple and lazy-load-free. */
    @Column(name = "campaign_run_id", nullable = false)
    private Long campaignRunId;

    /** Normalised SA MSISDN in 27XXXXXXXXX form (validated ^27[0-9]{9}$ at intake). */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 200)
    private String name;

    @Column(length = 200)
    private String surname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecipientStatus status = RecipientStatus.PENDING;

    /** The RandGo Member/Import batch GUID this recipient was submitted in (set during the import phase). */
    @Column(name = "randgo_batch_guid")
    private String randgoBatchGuid;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Codes returned by CouponBasketCheckout, stored verbatim (comma-joined) for export/audit. */
    @Column(name = "issued_codes", columnDefinition = "TEXT")
    private String issuedCodes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
