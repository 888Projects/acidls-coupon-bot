package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Response from POST /api/Voucher/Issued
 * Nightly call (23:00-05:00) to get redemption stats.
 * Tells us which codes were actually used at the till.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RandgoIssuedResponse {

    @JsonProperty("Success")
    private boolean success;

    @JsonProperty("Message")
    private String message;

    // List of coupons issued in the requested period
    @JsonProperty("Coupons")
    private List<IssuedCoupon> coupons;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssuedCoupon {
        @JsonProperty("Guid")
        private String guid;

        @JsonProperty("Name")
        private String name;

        @JsonProperty("NumberOfRequests")
        private int numberOfRequests;

        // Members who issued this coupon
        @JsonProperty("Issues")
        private List<MemberIssue> issues;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemberIssue {
        @JsonProperty("UniqueKey")
        private String uniqueKey;

        @JsonProperty("Cellphone")
        private String cellphone;

        @JsonProperty("Codes")
        private List<CodeDetail> codes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CodeDetail {
        // "WebService", "Mall", "USSD"
        @JsonProperty("ClaimedVia")
        private String claimedVia;

        @JsonProperty("ClaimedOn")
        private String claimedOn;

        // The actual code issued
        @JsonProperty("ObfuscatedCode")
        private String obfuscatedCode;

        // When Randgo received redemption data from retailer
        @JsonProperty("RedemptionStatsReceivedOn")
        private String redemptionStatsReceivedOn;

        // When the code was scanned at the till
        @JsonProperty("RedeemedOn")
        private String redeemedOn;

        // Which store it was redeemed at
        @JsonProperty("RedeemedAt")
        private String redeemedAt;

        @JsonProperty("RedemptionCount")
        private int redemptionCount;
    }
}
