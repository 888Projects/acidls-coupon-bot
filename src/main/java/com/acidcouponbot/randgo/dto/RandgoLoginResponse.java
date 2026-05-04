package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RandgoLoginResponse {

    @JsonProperty("Success")
    private boolean success;

    @JsonProperty("Message")
    private String message;

    @JsonProperty("Info")
    private LoginInfo info;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoginInfo {
        @JsonProperty("AccountGuid")
        private String accountGuid;

        @JsonProperty("SessionToken")
        private String sessionToken;
    }

    public String getSessionToken() {
        return info != null ? info.getSessionToken() : null;
    }

    public String getAccountGuid() {
        return info != null ? info.getAccountGuid() : null;
    }
}
