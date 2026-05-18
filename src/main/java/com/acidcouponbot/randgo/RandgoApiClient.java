package com.acidcouponbot.randgo;

import com.acidcouponbot.randgo.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Low-level HTTP client for all Randgo API calls.
 *
 * RATE LIMIT ENFORCEMENT (from Randgo doc):
 * - VouchersGet: max 10/week → we only call monthly
 * - CodesGet: max 1/10min
 * - CouponBasketHistory: after hours only (23:00-05:00)
 * - Issued: after hours only (23:00-05:00)
 *
 * On 401: re-login once via RandgoSessionManager, then retry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RandgoApiClient {

    @Value("${randgo.api.url}")
    private String apiUrl;

    @Value("${randgo.mock.mode:true}")
    private boolean mockMode;

    @Value("${randgo.client.scheme.guid:YOUR_CLIENT_SCHEME_GUID}")
    private String clientSchemeGuid;

    private final RandgoSessionManager sessionManager;
    private final WebClient.Builder webClientBuilder;

    // Track last VouchersGet call — must not exceed 10/week
    private LocalDateTime lastVouchersGetCall;
    private int vouchersGetCallCount = 0;

    // ─── Vouchers Get ─────────────────────────────────────────────────────────

    /**
     * POST /api/Voucher/VouchersGet
     * Returns all available coupons/vouchers.
     * CACHED monthly — max 10 calls/week allowed.
     */
    public RandgoVouchersResponse getVouchers() {
        if (mockMode) {
            return buildMockVouchersResponse();
        }

        enforceVouchersGetRateLimit();

        // Log raw response to see full structure including any Offerings/nested GUIDs
        String rawJson = webClientBuilder.build()
                .post()
                .uri(apiUrl + "/api/Voucher/VouchersGet")
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "SessionToken",    sessionManager.getSessionToken(),
                        "ClientSchemeGuid", clientSchemeGuid
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        log.info("VouchersGet RAW RESPONSE: {}", rawJson);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawJson, RandgoVouchersResponse.class);
        } catch (Exception e) {
            log.error("Failed to deserialize VouchersGet response: {}", e.getMessage());
            return null;
        }
    }

    // ─── Coupon Basket Checkout ───────────────────────────────────────────────

    /**
     * POST /api/Voucher/Coupon/Basket/Checkout
     * Issues coupon codes to a customer.
     * Called live per customer when they send "Coupon".
     *
     * @param phoneNumber customer's WhatsApp number e.g. "27831234567"
     * @param voucherGuids list of Randgo VoucherGuids to issue
     */
    public RandgoCouponCheckoutResponse checkoutCoupons(
            String phoneNumber,
            List<String> voucherGuids) {

        if (mockMode) {
            log.info("MOCK: Issuing {} coupons to +{}", voucherGuids.size(), phoneNumber);
            return buildMockCheckoutResponse(phoneNumber, voucherGuids);
        }

        RandgoCouponCheckoutRequest request = RandgoCouponCheckoutRequest.builder()
                .sessionToken(sessionManager.getSessionToken())
                .primaryKeyName("Cellphone")
                .primaryKeyValue(phoneNumber)
                .redemptionType("SMS")          // "SMS" confirmed working on QA
                .issueExistingBasket(true)       // CRITICAL: return same codes if member already has a basket
                // Prevents duplicate issuance if dedup ever fails
                .coupons(voucherGuids)
                .alternativeCellphone("")
                .alternativeEmail("")
                .build();

        // Log raw response to verify Basket structure
        try {
            String rawCheckout = webClientBuilder.build()
                    .post()
                    .uri(apiUrl + "/api/Voucher/Coupon/Basket/Checkout")
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("CouponBasketCheckout RAW RESPONSE: {}", rawCheckout);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawCheckout, RandgoCouponCheckoutResponse.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Checkout JSON parse error: {}", e.getMessage());
            throw new RuntimeException("Checkout parse error", e);
        } catch (Exception e) {
            throw e;
        }
    }

    // ─── Member Import ────────────────────────────────────────────────────────

    /**
     * POST /api/Member/Import
     * Registers a new customer in Randgo before we can issue to them.
     *
     * @param phoneNumber customer's WhatsApp number
     * @param memberIdentifierGuid from Randgo account setup
     */
    public String importMember(String phoneNumber, String memberIdentifierGuid) {
        if (mockMode) {
            log.info("MOCK: Registering member +{} in Randgo", phoneNumber);
            return "MOCK-BATCH-GUID-" + phoneNumber;
        }

        RandgoMemberImportRequest request = RandgoMemberImportRequest.builder()
                .sessionToken(sessionManager.getSessionToken())
                .clientSchemeGuid(clientSchemeGuid)
                .primaryKeyName("Cellphone")
                .clientSchemeMemberIdentifierGuid(memberIdentifierGuid)
                .members(List.of(
                        RandgoMemberImportRequest.Member.builder()
                                .cellphone(phoneNumber)
                                .uniqueUserKey(phoneNumber)
                                .active(true)
                                .registered(true)
                                .build()
                ))
                .build();

        // Response is a Map because import response has nested Batch object
        Map response = callWithRetry("/api/Member/Import", request, Map.class);

        if (response != null) {
            Map batch = (Map) response.get("Batch");
            if (batch != null) {
                return (String) batch.get("Guid");
            }
        }
        return null;
    }

    /**
     * POST /api/Member/Import/Batch/GetByBatchGuid
     * Checks if a member import batch has completed.
     */
    public boolean isMemberImportComplete(String batchGuid) {
        if (mockMode) return true;

        Map response = callWithRetry("/api/Member/Import/Batch/GetByBatchGuid",
                Map.of("SessionToken", sessionManager.getSessionToken(),
                        "Guid", batchGuid),
                Map.class);

        if (response != null) {
            Map batch = (Map) response.get("Batch");
            if (batch != null) {
                String status = (String) batch.get("Status");
                return "Completed".equalsIgnoreCase(status);
            }
        }
        return false;
    }

    /**
     * POST /api/Voucher/Issued
     * Nightly redemption stats — ONLY call between 23:00 and 05:00.
     */
    public RandgoIssuedResponse getIssuedStats(String dateFrom, String dateTo) {
        if (mockMode) {
            return new RandgoIssuedResponse();
        }

        if (!isAfterHours()) {
            log.warn("Issued API called outside allowed hours (23:00-05:00) — skipping");
            return null;
        }

        return callWithRetry("/api/Voucher/Issued",
                Map.of("SessionToken", sessionManager.getSessionToken(),
                        "DateFrom", dateFrom,
                        "DateTo", dateTo),
                RandgoIssuedResponse.class);
    }

    // ─── Internal Helpers ─────────────────────────────────────────────────────

    /**
     * Makes an API call. On 401, refreshes session and retries once.
     */
    private <T> T callWithRetry(String path, Object requestBody, Class<T> responseType) {
        try {
            return doCall(path, requestBody, responseType);
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("Randgo 401 on {} — refreshing session and retrying", path);
            sessionManager.forceRelogin();

            // Update session token in request if it's a Map
            if (requestBody instanceof Map map) {
                map.put("SessionToken", sessionManager.getSessionToken());
            }
            return doCall(path, requestBody, responseType);
        }
    }

    private <T> T doCall(String path, Object requestBody, Class<T> responseType) {
        log.debug("Randgo API call: {}", path);
        log.debug("Randgo request body: {}", requestBody);
        return webClientBuilder.build()
                .post()
                .uri(apiUrl + path)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(responseType)
                .block();
    }

    private void enforceVouchersGetRateLimit() {
        // Max 10 calls per week
        if (lastVouchersGetCall != null &&
                lastVouchersGetCall.isAfter(LocalDateTime.now().minusWeeks(1))) {
            if (vouchersGetCallCount >= 10) {
                throw new RuntimeException(
                        "VouchersGet rate limit reached (10/week). " +
                                "Next allowed: " + lastVouchersGetCall.plusWeeks(1));
            }
        } else {
            vouchersGetCallCount = 0; // reset weekly counter
        }
        vouchersGetCallCount++;
        lastVouchersGetCall = LocalDateTime.now();
    }

    private boolean isAfterHours() {
        int hour = LocalDateTime.now().getHour();
        return hour >= 23 || hour < 5;
    }

    // ─── Mock Data ────────────────────────────────────────────────────────────

    private RandgoVouchersResponse buildMockVouchersResponse() {
        RandgoVouchersResponse response = new RandgoVouchersResponse();
        response.setSuccess(true);

        RandgoVouchersResponse.Voucher milk = new RandgoVouchersResponse.Voucher();
        milk.setGuid("MOCK-GUID-MILK-001");
        milk.setName("R20 off Full Cream Milk 2L");
        milk.setVoucherType("Coupon");
        milk.setDiscountDisplay("R20");
        milk.setDiscountSingle(20.0);
        milk.setSummaryText("Save R20 on 2L Full Cream Milk");

        RandgoVouchersResponse.Voucher bread = new RandgoVouchersResponse.Voucher();
        bread.setGuid("MOCK-GUID-BREAD-001");
        bread.setName("R10 off Albany White Bread");
        bread.setVoucherType("Coupon");
        bread.setDiscountDisplay("R10");
        bread.setDiscountSingle(10.0);
        bread.setSummaryText("Save R10 on Albany White Bread 700g");

        RandgoVouchersResponse.Voucher eggs = new RandgoVouchersResponse.Voucher();
        eggs.setGuid("MOCK-GUID-EGGS-001");
        eggs.setName("R15 off Large Eggs 6-pack");
        eggs.setVoucherType("Coupon");
        eggs.setDiscountDisplay("R15");
        eggs.setDiscountSingle(15.0);
        eggs.setSummaryText("Save R15 on 6-pack Large Eggs");

        RandgoVouchersResponse.Voucher sunlight = new RandgoVouchersResponse.Voucher();
        sunlight.setGuid("MOCK-GUID-SUNLIGHT-001");
        sunlight.setName("R8 off Sunlight Dishwashing 750ml");
        sunlight.setVoucherType("Coupon");
        sunlight.setDiscountDisplay("R8");
        sunlight.setDiscountSingle(8.0);
        sunlight.setSummaryText("Save R8 on Sunlight Dishwashing Liquid 750ml");

        RandgoVouchersResponse.Voucher cheese = new RandgoVouchersResponse.Voucher();
        cheese.setGuid("MOCK-GUID-CHEESE-001");
        cheese.setName("R25 off Gouda Cheese 400g");
        cheese.setVoucherType("Coupon");
        cheese.setDiscountDisplay("R25");
        cheese.setDiscountSingle(25.0);
        cheese.setSummaryText("Save R25 on 400g Gouda Cheese");

        RandgoVouchersResponse.Vendor vendor = new RandgoVouchersResponse.Vendor();
        vendor.setGuid("MOCK-VENDOR-SHOPRITE-001");
        vendor.setName("Shoprite");
        vendor.setVouchers(List.of(milk, bread, eggs, sunlight, cheese));

        response.setVendors(List.of(vendor));
        return response;
    }

    private RandgoCouponCheckoutResponse buildMockCheckoutResponse(
            String phoneNumber, List<String> voucherGuids) {

        String[][] mockProducts = {
                {"MOCK-GUID-MILK-001",     "🥛", "Milk",     "R20 off", "MIL"},
                {"MOCK-GUID-BREAD-001",    "🍞", "Bread",    "R10 off", "BRE"},
                {"MOCK-GUID-EGGS-001",     "🥚", "Eggs",     "R15 off", "EGG"},
                {"MOCK-GUID-SUNLIGHT-001", "🧴", "Sunlight", "R8 off",  "SUN"},
                {"MOCK-GUID-CHEESE-001",   "🧀", "Cheese",   "R25 off", "CHE"}
        };

        RandgoCouponCheckoutResponse response = new RandgoCouponCheckoutResponse();
        response.setSuccess(true);

        RandgoCouponCheckoutResponse.Basket basket = new RandgoCouponCheckoutResponse.Basket();
        basket.setGuid("MOCK-BASKET-" + System.currentTimeMillis());

        // Generate mock codes for each requested voucher GUID
        List<RandgoCouponCheckoutResponse.IssuedCode> codes = voucherGuids.stream().map(guid -> {
            RandgoCouponCheckoutResponse.IssuedCode code = new RandgoCouponCheckoutResponse.IssuedCode();
            String prefix = "CPX";
            for (String[] p : mockProducts) {
                if (p[0].equals(guid)) { prefix = p[4]; break; }
            }
            code.setIssuedCode(prefix + "-" + String.format("%04d", (int)(Math.random()*9000+1000)) + "-" + System.currentTimeMillis() % 10000);
            code.setIssuedDate(LocalDateTime.now().toString());
            code.setDenomination(2000);
            code.setCancelDate(LocalDateTime.now().plusMonths(3).toString());
            return code;
        }).toList();

        basket.setCodes(codes);
        response.setBaskets((List<RandgoCouponCheckoutResponse.Basket>) basket);
        return response;
    }
}