package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Response from POST /api/Voucher/Coupon/Basket/Checkout
 * Contains the actual coupon codes to send to the customer.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RandgoCouponCheckoutResponse {

    @JsonProperty("Success")
    private boolean success;

    @JsonProperty("Message")
    private String message;

    // Randgo returns Basket as a JSON array — we take the first element
    @JsonProperty("Basket")
    private List<Basket> baskets;

    public Basket getBasket() {
        return (baskets != null && !baskets.isEmpty()) ? baskets.get(0) : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Basket {
        @JsonProperty("Guid")
        private String guid;

        @JsonProperty("DateCreated")
        private String dateCreated;

        // The actual coupon codes issued
        @JsonProperty("Codes")
        private List<IssuedCode> codes;

        // Per-voucher issue status
        @JsonProperty("Coupons")
        private List<CouponResult> coupons;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssuedCode {
        // The actual coupon code string e.g. "9347 1900 9545 4521"
        @JsonProperty("IssuedCode")
        private String issuedCode;

        @JsonProperty("IssuedDate")
        private String issuedDate;

        // Value in cents e.g. 2000 = R20
        @JsonProperty("Denomination")
        private Integer denomination;

        // Code expiry date
        @JsonProperty("CancelDate")
        private String cancelDate;

        // Which vouchers are covered by this code (Randgo groups by denomination)
        @JsonProperty("Coupons")
        private List<CouponResult> coupons;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CouponResult {
        @JsonProperty("VoucherGuid")
        private String voucherGuid;

        // True if this specific coupon failed to issue
        @JsonProperty("IssueFailed")
        private boolean issueFailed;

        // Message to show customer, or failure reason
        @JsonProperty("RedemptionTemplate")
        private String redemptionTemplate;
    }
}