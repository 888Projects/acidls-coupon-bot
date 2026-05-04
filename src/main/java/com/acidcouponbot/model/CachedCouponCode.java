package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Local cache of coupon codes pulled from Randgo CodesGet API.
 *
 * PURPOSE:
 * Primary flow uses live Randgo CouponBasketCheckout.
 * If Randgo API is down, we fall back to this local pool.
 *
 * POPULATED BY:
 * RandgoSyncService nightly job (23:00-05:00) via CodesGet.
 *
 * SECURITY NOTE (from Randgo doc):
 * "ALL CODES ARE TO BE HANDLED AS SENSITIVE INFORMATION."
 * These codes must never be exposed via any public endpoint.
 */
@Entity
@Table(name = "cached_coupon_codes",
    indexes = {
        @Index(name = "idx_cached_status", columnList = "status"),
        @Index(name = "idx_cached_voucher", columnList = "voucher_guid"),
        @Index(name = "idx_cached_batch", columnList = "batch_guid")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CachedCouponCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Randgo's code GUID
    @Column(name = "code_guid", unique = true)
    private String codeGuid;

    // The actual coupon code string e.g. "MILK-ABC123"
    @Column(name = "code", nullable = false)
    private String code;

    // Which voucher this code belongs to
    @Column(name = "voucher_guid", nullable = false)
    private String voucherGuid;

    // Product name for display e.g. "Milk"
    @Column(name = "product")
    private String product;

    // Discount display e.g. "R20 off"
    @Column(name = "discount_display")
    private String discountDisplay;

    // Randgo batch this code came from
    @Column(name = "batch_guid")
    private String batchGuid;

    @Column(name = "batch_number")
    private String batchNumber;

    // Valid from date
    @Column(name = "start_date")
    private LocalDateTime startDate;

    // Code expiry
    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodeStatus status = CodeStatus.AVAILABLE;

    // Phone number this was assigned to (if USED_FALLBACK)
    @Column(name = "assigned_to_phone")
    private String assignedToPhone;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    // Whether this was used as fallback (Randgo API was down)
    @Column(name = "used_as_fallback")
    private boolean usedAsFallback = false;

    // Whether Randgo has been notified this code was issued
    // (required when using bulk codes — Randgo must be updated)
    @Column(name = "randgo_notified")
    private boolean randgoNotified = false;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @PrePersist
    protected void onCreate() {
        syncedAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return endDate != null && LocalDateTime.now().isAfter(endDate);
    }

    public boolean isAvailable() {
        return status == CodeStatus.AVAILABLE && !isExpired();
    }

    public enum CodeStatus {
        AVAILABLE,        // Ready to use as fallback
        USED_FALLBACK,    // Used when Randgo API was down
        USED_PRIMARY,     // Issued via live API (code tracked here for reference)
        EXPIRED           // Past end date
    }
}
