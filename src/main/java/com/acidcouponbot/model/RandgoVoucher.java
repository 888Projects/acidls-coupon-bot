package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Cached voucher/coupon metadata from Randgo VouchersGet.
 *
 * VouchersGet is cached monthly (max 10 calls/week allowed).
 * We store the VoucherGuid here — this is what we pass
 * to CouponBasketCheckout to issue codes to customers.
 */
@Entity
@Table(name = "randgo_vouchers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RandgoVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Randgo's GUID for this voucher — used in CouponBasketCheckout
    @Column(name = "voucher_guid", nullable = false, unique = true)
    private String voucherGuid;

    // e.g. "R20 off Milk at Shoprite"
    @Column(name = "voucher_name")
    private String voucherName;

    // e.g. "Milk", "Bread", "Eggs" — extracted from name
    @Column(name = "product")
    private String product;

    // e.g. "R20" or "10%"
    @Column(name = "discount_display")
    private String discountDisplay;

    // Numeric value e.g. 20.0
    @Column(name = "discount_single")
    private Double discountSingle;

    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;

    // e.g. "Coupon" — we only use Coupon type
    @Column(name = "voucher_type")
    private String voucherType;

    // Vendor/retailer name e.g. "Shoprite"
    @Column(name = "vendor_name")
    private String vendorName;

    // Randgo's vendor GUID
    @Column(name = "vendor_guid")
    private String vendorGuid;

    /** Category assigned by CouponCategory keyword matcher e.g. GROCERIES */
    @Column(name = "category")
    private String category;

    @Column(name = "cancel_date")
    private LocalDateTime cancelDate;

    // Whether this voucher is included in the active campaign bundle
    @Column(name = "include_in_bundle")
    private boolean includeInBundle = true;

    // Display order in WhatsApp message
    @Column(name = "display_order")
    private int displayOrder = 0;

    @Column(name = "emoji")
    private String emoji = "🛒";

    @Column(name = "active")
    private boolean active = true;

    @Column(name = "last_synced")
    private LocalDateTime lastSynced;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        lastSynced = LocalDateTime.now();
    }

    public boolean isExpired() {
        return cancelDate != null && LocalDateTime.now().isAfter(cancelDate);
    }
}