package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "campaigns")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Retailer name for display e.g. "Shoprite"
    @Column(name = "retailer_name")
    private String retailerName;

    @Column(name = "retailer_emoji")
    private String retailerEmoji;

    // How many vouchers per bundle (matches number of RandgoVouchers with includeInBundle=true)
    @Column(name = "bundle_size")
    private int bundleSize = 5;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;

    @Column(name = "low_stock_threshold")
    private int lowStockThreshold = 10;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return expiryDate != null && LocalDateTime.now().isAfter(expiryDate);
    }
}
