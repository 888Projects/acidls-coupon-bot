package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Request for POST /api/Member/Import
 * Registers a new customer (phone number) in Randgo
 * before we can issue coupons to them.
 */
@Data
@Builder
public class RandgoMemberImportRequest {

    @JsonProperty("SessionToken")
    private String sessionToken;

    @JsonProperty("ClientSchemeGuid")
    private String clientSchemeGuid;

    // "Cell Phone" — we identify members by WhatsApp number
    @JsonProperty("PrimaryKeyName")
    private String primaryKeyName;

    @JsonProperty("ClientSchemeMemberIdentifierGuid")
    private String clientSchemeMemberIdentifierGuid;

    @JsonProperty("Members")
    private List<Member> members;

    @Data
    @Builder
    public static class Member {
        // WhatsApp number e.g. "27831234567"
        @JsonProperty("Cellphone")
        private String cellphone;

        // Use phone as unique key
        @JsonProperty("UniqueUserKey")
        private String uniqueUserKey;

        @JsonProperty("Active")
        private boolean active;

        // Mark as registered so they can use coupons immediately
        @JsonProperty("Registered")
        private boolean registered;
    }
}

// ─── Batch Monitor Request ────────────────────────────────────────────────────

class RandgoBatchRequest {
    @JsonProperty("SessionToken")
    public String sessionToken;

    @JsonProperty("BatchGuid")
    public String batchGuid;
}

// ─── Import Response ──────────────────────────────────────────────────────────

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
class RandgoMemberImportResponse {

    @JsonProperty("Success")
    private boolean success;

    @JsonProperty("Message")
    private String message;

    @JsonProperty("Batch")
    private BatchInfo batch;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchInfo {
        @JsonProperty("Guid")
        private String guid;

        @JsonProperty("Status")
        private String status;

        @JsonProperty("Progress")
        private String progress;

        @JsonProperty("RowCount")
        private int rowCount;

        @JsonProperty("ImportedRowCount")
        private int importedRowCount;

        @JsonProperty("Failed")
        private boolean failed;

        @JsonProperty("FailedDueTo")
        private String failedDueTo;

        @JsonProperty("FailedRowCount")
        private int failedRowCount;
    }
}
