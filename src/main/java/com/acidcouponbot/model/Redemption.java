package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Records every coupon claim.
 * Stores both the request (who claimed) and the
 * Randgo response (codes issued + redemption stats).
 */
@Entity
@Table(name = "redemptions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_phone_campaign",
            columnNames = {"phone_number", "campaign_id"})
    },
    indexes = {
        @Index(name = "idx_redemption_phone", columnList = "phone_number"),
        @Index(name = "idx_msg_sent", columnList = "message_sent_successfully")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Redemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // WhatsApp number e.g. "27831234567"
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    // Randgo basket GUID returned from CouponBasketCheckout
    @Column(name = "randgo_basket_guid")
    private String randgoBasketGuid;

    // The issued coupon codes from Randgo (JSON array stored as string)
    // e.g. ["MILK-ABC123","BRDL-XY99","EGG-871K","SUN-44ZZ","CHS-229A"]
    @Column(name = "issued_codes", columnDefinition = "TEXT")
    private String issuedCodesJson;

    // The exact WhatsApp message sent to the customer
    @Column(name = "message_sent", columnDefinition = "TEXT")
    private String messageSent;

    // FIX: Was the WhatsApp message actually delivered?
    @Column(name = "message_sent_successfully")
    private boolean messageSentSuccessfully = false;

    @Column(name = "send_attempts")
    private int sendAttempts = 0;

    @Column(name = "last_send_attempt")
    private LocalDateTime lastSendAttempt;

    // Randgo member registered before issuing?
    @Column(name = "member_registered_in_randgo")
    private boolean memberRegisteredInRandgo = false;

    // Randgo member registration batch GUID
    @Column(name = "randgo_batch_guid")
    private String randgoBatchGuid;

    // Till redemption data (populated nightly from Randgo Issued API)
    @Column(name = "redeemed_at_till")
    private boolean redeemedAtTill = false;

    @Column(name = "till_redeemed_on")
    private LocalDateTime tillRedeemedOn;

    @Column(name = "till_redeemed_at_store")
    private String tillRedeemedAtStore;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    @PrePersist
    protected void onCreate() {
        redeemedAt = LocalDateTime.now();
    }
}
