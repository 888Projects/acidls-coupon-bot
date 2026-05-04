package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RandgoVouchersResponse {

    @JsonProperty("Success")
    private boolean success;

    @JsonProperty("Message")
    private String message;

    @JsonProperty("Vendors")
    private List<Vendor> vendors;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vendor {
        @JsonProperty("Guid")
        private String guid;

        @JsonProperty("Name")
        private String name;

        @JsonProperty("Description")
        private String description;

        @JsonProperty("Vouchers")
        private List<Voucher> vouchers;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Voucher {
        @JsonProperty("Guid")
        private String guid;

        @JsonProperty("Name")
        private String name;

        // e.g. "Coupon", "Voucher", "GiftCard"
        @JsonProperty("VoucherType")
        private String voucherType;

        @JsonProperty("SummaryText")
        private String summaryText;

        @JsonProperty("DescriptionText")
        private String descriptionText;

        // e.g. "R20" or "10%"
        @JsonProperty("DiscountDisplay")
        private String discountDisplay;

        @JsonProperty("DiscountSingle")
        private Double discountSingle;

        // Expiry date
        @JsonProperty("CancelDate")
        private String cancelDate;

        @JsonProperty("RedemptionDetailsText")
        private String redemptionDetailsText;

        @JsonProperty("TermsAndConditionsText")
        private String termsAndConditionsText;
    }
}
