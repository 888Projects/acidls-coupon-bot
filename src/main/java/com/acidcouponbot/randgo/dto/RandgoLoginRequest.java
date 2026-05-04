package com.acidcouponbot.randgo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RandgoLoginRequest {
    @JsonProperty("Username")
    private String username;

    @JsonProperty("Password")
    private String password;
}
