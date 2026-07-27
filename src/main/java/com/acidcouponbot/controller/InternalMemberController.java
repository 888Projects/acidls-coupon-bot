package com.acidcouponbot.controller;

import com.acidcouponbot.randgo.RandgoMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal service-to-service API — NOT for the dashboard or the public internet.
 *
 * <p>Reached only over the {@code acidls} docker network (the service is not published on the host).
 * Authenticated by a shared {@code X-Internal-Api-Key} header, NOT the admin Basic-auth used by
 * {@link AdminController}. Spring Security {@code permitAll}s {@code /internal/**} (see SecurityConfig)
 * precisely so this key check — not a Basic-auth challenge — is the gate.
 *
 * <p>Today it exposes just-in-time member provisioning for the gateway's SSO flow: before the gateway
 * mints a RandGo SSO link, it calls {@code POST /internal/member/ensure} so the member exists in RandGo
 * and the user never lands on a "member not found" wall.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalMemberController {

    /** Shared internal key; the caller (gateway) sends the SAME value in X-Internal-Api-Key. */
    @Value("${internal.api-key:}")
    private String internalApiKey;

    private final RandgoMemberService randgoMemberService;

    /**
     * Ensure a RandGo member exists for {@code phone} (JIT provisioning).
     *
     * <p>Delegates to {@link RandgoMemberService#ensureMemberRegistered(String)}, which is idempotent
     * (memory + DB dedup — repeat calls are cheap) and reuses the DB-cached RandGo session token via
     * {@code RandgoSessionManager} — it NEVER triggers a fresh Login (RandGo's 1/day limit).
     *
     * <p>Fail-open: {@code ensureMemberRegistered} already returns {@code true} even when the import
     * fails, and this endpoint ALWAYS returns 200 (never a 5xx) so the caller's SSO flow is never
     * blocked by us. The {@code ensured} flag is advisory — a {@code false} means "not confirmed",
     * not "sign-in must stop".
     */
    @PostMapping("/member/ensure")
    public ResponseEntity<Map<String, Object>> ensureMember(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestBody(required = false) Map<String, String> body) {

        // A blank configured key must never accept a blank/absent incoming key — fail closed on auth.
        if (internalApiKey == null || internalApiKey.isBlank() || !internalApiKey.equals(apiKey)) {
            log.warn("MEMBER_ENSURE_UNAUTHORIZED — bad or missing internal API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        String phone = body != null ? body.get("phone") : null;
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "phone required"));
        }

        boolean ensured;
        try {
            ensured = randgoMemberService.ensureMemberRegistered(phone);
        } catch (Exception e) {
            // Defensive only — ensureMemberRegistered already swallows its own failures. We still
            // never surface a 5xx to the SSO caller: return ensured=false and 200.
            log.error("MEMBER_ENSURE_ERROR phone={}: {}", mask(phone), e.getMessage());
            ensured = false;
        }
        log.info("MEMBER_ENSURE phone={} ensured={}", mask(phone), ensured);
        return ResponseEntity.ok(Map.of("ensured", ensured));
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 2);
    }
}
