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

        // RandGo keys members by the LOCAL 0XXXXXXXXX MSISDN (same as the SSO NameID). Registration
        // (importMember) normalizes to that form, so checkout MUST use the identical key or the lookup
        // misses and no codes issue. Kept in lockstep via the shared normalizer.
        String memberKey = toLocalMemberKey(phoneNumber);

        RandgoCouponCheckoutRequest request = RandgoCouponCheckoutRequest.builder()
                .sessionToken(sessionManager.getSessionToken())
                .primaryKeyName("Cellphone")
                .primaryKeyValue(memberKey)
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

        // Store under the LOCAL 0XXXXXXXXX MSISDN so the member key matches the SSO assertion NameID
        // (auth-service toLocalMsisdn). The gateway hands us 27... form; sending that verbatim is the
        // root cause of the SSO login-wall for auto-provisioned users. Checkout uses the same key.
        String memberKey = toLocalMemberKey(phoneNumber);
        // Proves the ACTUAL key sent to RandGo (not the cosmetic "+27..." line in RandgoMemberService).
        // If prod ever shows anything other than the local 0... form here, the SSO key mismatch is live.
        log.info("Member/Import payload: raw={} → RandGo Cellphone/UniqueUserKey={}", phoneNumber, memberKey);

        RandgoMemberImportRequest request = RandgoMemberImportRequest.builder()
                .sessionToken(sessionManager.getSessionToken())
                .clientSchemeGuid(clientSchemeGuid)
                .primaryKeyName("Cellphone")
                .clientSchemeMemberIdentifierGuid(memberIdentifierGuid)
                .members(List.of(
                        RandgoMemberImportRequest.Member.builder()
                                .cellphone(memberKey)
                                .uniqueUserKey(memberKey)
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
     * Batched variant of {@link #importMember(String, String)} for the bulk campaign — imports MANY members
     * in ONE Member/Import call and returns the batch GUID. The single-member method is intentionally left
     * untouched (live SSO depends on it). Same key normalisation ({@link #toLocalMemberKey}) so campaign
     * members match the SSO NameID and the checkout key.
     *
     * <p>Routes through {@link #callWithRetry} exactly like the single-member path, so a 401 in campaign mode
     * surfaces as a {@link CampaignLoginSuppressedException} (the session manager refuses to re-authenticate
     * on the campaign thread) rather than a silent re-login.
     */
    public String importMembers(List<String> phoneNumbers, String memberIdentifierGuid) {
        if (mockMode) {
            log.info("MOCK: Batch-registering {} members in Randgo", phoneNumbers.size());
            return "MOCK-BATCH-" + phoneNumbers.size();
        }

        List<RandgoMemberImportRequest.Member> members = phoneNumbers.stream()
                .map(RandgoApiClient::toLocalMemberKey)
                .map(key -> RandgoMemberImportRequest.Member.builder()
                        .cellphone(key)
                        .uniqueUserKey(key)
                        .active(true)
                        .registered(true)
                        .build())
                .toList();

        RandgoMemberImportRequest request = RandgoMemberImportRequest.builder()
                .sessionToken(sessionManager.getSessionToken())
                .clientSchemeGuid(clientSchemeGuid)
                .primaryKeyName("Cellphone")
                .clientSchemeMemberIdentifierGuid(memberIdentifierGuid)
                .members(members)
                .build();

        // Request log — mirrors the single-member importMember payload line so a FAILING batch shows the
        // EXACT keys sent to RandGo (Cellphone/UniqueUserKey are the local 0… form), and the error body
        // below shows WHY RandGo rejected it.
        log.info("Member/Import BATCH payload: n={} primaryKeyName=Cellphone clientSchemeGuid={} "
                        + "memberIdentifierGuid={} cellphones={}",
                members.size(), clientSchemeGuid, memberIdentifierGuid,
                members.stream().map(RandgoMemberImportRequest.Member::getCellphone).toList());

        Map response;
        try {
            response = callWithRetry("/api/Member/Import", request, Map.class);
        } catch (WebClientResponseException e) {
            // callWithRetry only handles 401 — a 400/502 etc. propagates. Log RandGo's reason immediately
            // (the response body), then rethrow so the caller's existing handling is unchanged.
            log.error("Member/Import BATCH FAILED status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
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

    /**
     * Normalizes a phone number to the LOCAL South-African MSISDN {@code 0XXXXXXXXX} that RandGo stores
     * members under and that the SSO assertion NameID uses (auth-service {@code toLocalMsisdn}).
     *
     * <p>This is the single choke point for the RandGo member key — BOTH member registration
     * ({@code importMember}: Cellphone + UniqueUserKey) and coupon checkout ({@code checkoutCoupons}:
     * PrimaryKeyValue) route through it, so registration and redemption stay in lockstep. Sending the
     * gateway's {@code 27...} form verbatim is what put existing testers behind the SSO login-wall.
     *
     * <p>Robust to the shapes the phone can arrive in: {@code +27XXXXXXXXX}, {@code 27XXXXXXXXX}, or an
     * already-local {@code 0XXXXXXXXX} (left unchanged). Only the RandGo key is normalized — local DB
     * records, dedup caches and WhatsApp sends keep their existing {@code 27...} form.
     */
    static String toLocalMemberKey(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");     // drop +, spaces, punctuation
        if (digits.startsWith("27") && digits.length() == 11) {
            return "0" + digits.substring(2);            // 27XXXXXXXXX → 0XXXXXXXXX
        }
        if (digits.startsWith("0")) {
            return digits;                               // already local — leave unchanged
        }
        if (digits.length() == 9) {
            return "0" + digits;                         // bare national (no 0/27) → prepend 0
        }
        return digits;                                   // unknown shape → cleaned digits, unchanged
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
        response.setBaskets(List.of(basket));
        return response;
    }
}