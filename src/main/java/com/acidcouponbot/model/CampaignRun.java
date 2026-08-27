package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One bulk coupon campaign — a CSV load plus its execution state. Progress counters are updated by the
 * runner so a status endpoint (and a resume) can read them straight from the row. State lives in the DB
 * (not memory) precisely so a resume survives a container restart.
 *
 * <p>JPA auto-DDL (no Flyway in this repo).
 */
@Entity
@Table(name = "campaign_runs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.CREATED;

    @Column(name = "total_recipients")
    private int totalRecipients;

    @Column(name = "processed_count")
    private int processedCount;

    @Column(name = "failed_count")
    private int failedCount;

    /** Human-readable reason a run is PAUSED (e.g. "401 during campaign — Login suppressed"). */
    @Column(name = "paused_reason", length = 500)
    private String pausedReason;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
