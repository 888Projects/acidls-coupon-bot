package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Request for POST /api/Voucher/Coupon/Basket/Checkout
 *
 * We use IssueExistingBasket=false (we maintain our own basket)
 * and pass the list of VoucherGuids we want to issue.
 */
@Data
@Builder
public class RandgoCouponCheckoutRequest {

    @JsonProperty("SessionToken")
    private String sessionToken;

    // PrimaryKeyName = "Cell Phone" (we identify customers by WhatsApp number)
    @JsonProperty("PrimaryKeyName")
    private String primaryKeyName;

    // The customer's phone number e.g. "27831234567"
    @JsonProperty("PrimaryKeyValue")
    private String primaryKeyValue;

    // Redemption type — "WebService" for API-based issuing
    @JsonProperty("RedemptionType")
    private String redemptionType;

    @JsonProperty("AlternativeEmail")
    private String alternativeEmail;

    @JsonProperty("alternativeCellphone")
    private String alternativeCellphone;

    // We maintain our own basket — set to false
    @JsonProperty("IssueExistingBasket")
    private boolean issueExistingBasket;

    // The VoucherGuids we want to issue (one per product)
    @JsonProperty("Coupons")
    private List<String> coupons;

}
