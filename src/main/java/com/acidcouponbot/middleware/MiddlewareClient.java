package com.acidcouponbot.middleware;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * HTTP client for the ACID LS middleware member-status endpoint.
 *
 * Used to distinguish:
 *   isMember=true  → registered wallet user → monthly coupon allocation
 *   isMember=false → unknown phone → one-time promo only
 *
 * Endpoint: GET {middleware-url}/internal/coupon/member-status?phone={phone}
 * Auth:     X-Internal-API-Key header
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MiddlewareClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${middleware.base-url:http://acidls-api-gateway:8100}")
    private String middlewareUrl;

    @Value("${middleware.internal-api-key:ci-internal-key-not-for-prod}")
    private String internalApiKey;

    @Value("${middleware.enabled:true}")
    private boolean enabled;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * Returns member status for a phone number.
     * Falls back to NON_MEMBER on any error — never blocks coupon flow.
     */
    public MemberStatus getMemberStatus(String phone) {
        if (!enabled) {
            log.debug("Middleware check disabled — treating +{} as NON_MEMBER", phone);
            return MemberStatus.nonMember(phone);
        }

        try {
            MiddlewareResponse resp = webClientBuilder.build()
                .get()
                .uri(middlewareUrl + "/internal/coupon/member-status?phone=" + phone)
                .header("X-Internal-API-Key", internalApiKey)
                .retrieve()
                .bodyToMono(MiddlewareResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (resp == null || resp.getData() == null) {
                log.warn("MIDDLEWARE_MEMBER_STATUS null response for +{}", phone);
                return MemberStatus.nonMember(phone);
            }

            MiddlewareResponse.MemberStatusData data = resp.getData();
            log.info("MIDDLEWARE_MEMBER_STATUS phone={} isMember={} status={}",
                phone, data.isMember(), data.getStatus());

            return new MemberStatus(
                phone,
                data.isMember(),
                data.isSuspended(),
                data.getWalletId(),
                data.getUserId(),
                data.getCurrentPeriod(),
                data.getStatus()
            );

        } catch (WebClientResponseException e) {
            log.warn("MIDDLEWARE_MEMBER_STATUS_HTTP_ERROR phone={} http={}",
                phone, e.getStatusCode());
            return MemberStatus.nonMember(phone);
        } catch (Exception e) {
            log.warn("MIDDLEWARE_MEMBER_STATUS_ERROR phone={} err={}",
                phone, e.getMessage());
            return MemberStatus.nonMember(phone); // fail open — never block coupons
        }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MiddlewareResponse {
        @JsonProperty("data") private MemberStatusData data;
        @JsonProperty("success") private boolean success;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class MemberStatusData {
            @JsonProperty("isMember")      private boolean isMember;
            @JsonProperty("suspended")     private boolean suspended;
            @JsonProperty("walletId")      private String  walletId;
            @JsonProperty("userId")        private String  userId;
            @JsonProperty("currentPeriod") private String  currentPeriod;
            @JsonProperty("status")        private String  status;
        }
    }

    public record MemberStatus(
            String  phone,
            boolean isMember,
            boolean suspended,
            String  walletId,
            String  userId,
            String  currentPeriod,
            String  status) {

        public static MemberStatus nonMember(String phone) {
            return new MemberStatus(phone, false, false,
                null, null, null, "NON_MEMBER");
        }

        /** Monthly coupon key — resets automatically each calendar month */
        public String monthlyAllocationKey() {
            if (!isMember || currentPeriod == null) return null;
            return phone + "-MEMBER-" + currentPeriod;
        }
    }
}