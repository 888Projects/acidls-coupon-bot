package com.acidcouponbot.randgo;

import com.acidcouponbot.model.RandgoSession;
import com.acidcouponbot.randgo.dto.RandgoLoginRequest;
import com.acidcouponbot.randgo.dto.RandgoLoginResponse;
import com.acidcouponbot.repository.RandgoSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Manages the Randgo SessionToken lifecycle.
 *
 * CRITICAL RULES FROM RANDGO DOC:
 * - Login ONCE per day maximum
 * - More than 1 call/day = account suspended for 2 hours on Live
 * - Token is on a sliding 24hr window (using it refreshes it)
 * - On 401 response: re-login once, then retry
 *
 * This class:
 * 1. Stores the token in DB (survives restarts)
 * 2. Returns cached token if still valid
 * 3. Only calls Login when token is expired or missing
 * 4. Detects 401 and triggers re-login
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RandgoSessionManager {

    @Value("${randgo.api.url}")
    private String apiUrl;

    @Value("${randgo.username}")
    private String username;

    @Value("${randgo.password}")
    private String password;

    @Value("${randgo.mock.mode:true}")
    private boolean mockMode;

    private final RandgoSessionRepository sessionRepository;
    private final WebClient.Builder webClientBuilder;

    private static final String MOCK_TOKEN = "MOCK-SESSION-TOKEN-DEV-MODE";

    /**
     * Thread-scoped campaign mode. When set on a thread, {@link #forceRelogin()} on THAT thread refuses to
     * log in (throws {@link CampaignLoginSuppressedException}) so a bulk campaign can never spend the daily
     * Login. Only the campaign-runner thread sets this; live SSO on other threads keeps its normal
     * 401→relogin recovery untouched.
     */
    private final ThreadLocal<Boolean> campaignMode = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Mark the CURRENT thread as in campaign mode (suppresses forceRelogin on this thread only). */
    public void enterCampaignMode() { campaignMode.set(Boolean.TRUE); }

    /** Clear campaign mode on the current thread. */
    public void exitCampaignMode() { campaignMode.remove(); }

    public boolean isCampaignMode() { return Boolean.TRUE.equals(campaignMode.get()); }

    /**
     * Read-only pre-flight for the campaign runner: is a usable cached token present WITHOUT logging in?
     * NEVER calls {@link #login()}. Logs which branch it took — importantly, in mock mode it returns true
     * with no real token, so a misconfigured prod running in mock mode would also pass here; the explicit
     * log line is the operator's signal to check which branch was taken.
     */
    public boolean hasValidCachedToken() {
        if (mockMode) {
            log.warn("CAMPAIGN_PREFLIGHT token=OK branch=MOCK_MODE — no real RandGo token in play "
                    + "(dev convenience; a prod misconfigured into mock mode would ALSO pass this check)");
            return true;
        }
        Optional<RandgoSession> existing = sessionRepository.findTopByActiveTrueOrderByCreatedAtDesc();
        boolean valid = existing.isPresent() && !existing.get().isExpired();
        if (valid) {
            log.info("CAMPAIGN_PREFLIGHT token=OK branch=LIVE — cached token valid (expires {})",
                    existing.get().getExpiresAt());
        } else {
            log.warn("CAMPAIGN_PREFLIGHT token=MISSING branch=LIVE — no valid cached token (present={})",
                    existing.isPresent());
        }
        return valid;
    }

    /**
     * Returns a valid SessionToken.
     * Uses cached token if valid, otherwise logs in.
     */
    public String getSessionToken() {
        if (mockMode) {
            log.debug("Mock mode — returning mock session token");
            return MOCK_TOKEN;
        }

        // Check DB for existing valid token
        Optional<RandgoSession> existing = sessionRepository
                .findTopByActiveTrueOrderByCreatedAtDesc();

        if (existing.isPresent() && !existing.get().isExpired()) {
            RandgoSession session = existing.get();
            session.extendExpiry(); // sliding window
            sessionRepository.save(session);
            log.debug("Using cached Randgo session token");
            return session.getSessionToken();
        }

        log.info("Randgo session token expired or missing — logging in");
        return login();
    }

    /**
     * Force re-login — called when API returns 401.
     * Invalidates existing session and creates new one.
     */
    public String forceRelogin() {
        // Campaign guard (thread-scoped): during a bulk campaign a 401 must NOT trigger a Login — a second
        // Login in a day suspends the production account. Refuse and signal the runner to pause instead.
        if (isCampaignMode()) {
            log.warn("CAMPAIGN_LOGIN_SUPPRESSED — 401 on the campaign thread; refusing to re-login "
                    + "(would risk the 1/day Login cap and suspend the account). Pausing the run.");
            throw new CampaignLoginSuppressedException(
                    "RandGo returned 401 during the campaign; Login suppressed to protect the daily cap.");
        }

        // Daily-Login guard (live paths, incl. SSO ensureMember): a 401 while a Login already happened within
        // the last 24h must NOT trigger a SECOND Login — that is the exact chain that suspends the RandGo
        // account ~2h for EVERYONE while the user gets a working-looking link that dead-ends. Refuse; the
        // caller degrades to FAILED (the gateway is READY-only, so no link is minted and the user is asked to
        // retry). getSessionToken()'s expiry-based Login — the one legitimate daily login — is NOT guarded
        // here, so a genuine daily refresh still works.
        if (!mockMode) {
            Optional<RandgoSession> lastLogin = sessionRepository.findTopByOrderByCreatedAtDesc();
            if (lastLogin.isPresent() && lastLogin.get().getCreatedAt() != null
                    && lastLogin.get().getCreatedAt().isAfter(LocalDateTime.now().minusHours(24))) {
                LocalDateTime last = lastLogin.get().getCreatedAt();
                log.warn("RANDGO_DAILY_LOGIN_EXHAUSTED — RandGo returned 401 but the last Login was at {} "
                                + "(< 24h ago); REFUSING a second Login to avoid the ~2h account suspension. "
                                + "Coupons recover automatically once the 24h Login window clears.", last);
                throw new DailyLoginExhaustedException(
                        "RandGo 401 but the daily Login was already spent at " + last
                                + "; refusing a second Login to protect the 1/day cap.");
            }
        }

        log.warn("Forcing Randgo re-login due to 401 response");

        // Invalidate existing sessions
        sessionRepository.findTopByActiveTrueOrderByCreatedAtDesc()
                .ifPresent(s -> {
                    s.setActive(false);
                    sessionRepository.save(s);
                });

        return login();
    }

    /**
     * Calls POST /api/Account/Login
     * ONLY called when token is expired or on 401.
     */
    private String login() {
        log.info("Calling Randgo Login API...");

        try {
            RandgoLoginResponse response = webClientBuilder.build()
                    .post()
                    .uri(apiUrl + "/api/Account/Login")
                    .header("Content-Type", "application/json")
                    .bodyValue(new RandgoLoginRequest(username, password))
                    .retrieve()
                    .bodyToMono(RandgoLoginResponse.class)
                    .block();

            if (response == null || !response.isSuccess() || response.getSessionToken() == null) {
                String msg = response != null ? response.getMessage() : "null response";
                log.error("Randgo login failed: {}", msg);
                throw new RuntimeException("Randgo login failed: " + msg);
            }

            // Save new session to DB
            RandgoSession session = RandgoSession.builder()
                    .sessionToken(response.getSessionToken())
                    .accountGuid(response.getAccountGuid())
                    .active(true)
                    .build();
            sessionRepository.save(session);

            log.info("Randgo login successful — session token cached");
            return response.getSessionToken();

        } catch (WebClientResponseException e) {
            log.error("Randgo login HTTP error: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Randgo login HTTP error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Randgo login exception: {}", e.getMessage());
            throw new RuntimeException("Randgo login failed", e);
        }
    }
}
