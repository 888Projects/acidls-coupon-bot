package com.acidcouponbot.service;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("RateLimiterService")
class RateLimiterServiceTest {

    private final RateLimiterService rateLimiter = new RateLimiterService();

    @Test @DisplayName("first 3 requests from same phone are allowed")
    void first3Requests_allowed() {
        assertThat(rateLimiter.isAllowed("27831234567")).isTrue();
        assertThat(rateLimiter.isAllowed("27831234567")).isTrue();
        assertThat(rateLimiter.isAllowed("27831234567")).isTrue();
    }

    @Test @DisplayName("4th request from same phone is blocked")
    void fourthRequest_blocked() {
        String phone = "27839999999";
        rateLimiter.isAllowed(phone);
        rateLimiter.isAllowed(phone);
        rateLimiter.isAllowed(phone);
        assertThat(rateLimiter.isAllowed(phone)).isFalse();
    }

    @Test @DisplayName("different phones have independent limits")
    void differentPhones_independentLimits() {
        String phoneA = "27831111111";
        String phoneB = "27832222222";
        rateLimiter.isAllowed(phoneA);
        rateLimiter.isAllowed(phoneA);
        rateLimiter.isAllowed(phoneA);
        // phoneA exhausted — phoneB still allowed
        assertThat(rateLimiter.isAllowed(phoneA)).isFalse();
        assertThat(rateLimiter.isAllowed(phoneB)).isTrue();
    }
}