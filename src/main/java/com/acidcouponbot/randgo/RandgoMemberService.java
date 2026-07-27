package com.acidcouponbot.randgo;

import com.acidcouponbot.randgo.dto.RandgoMemberImportRequest;
import com.acidcouponbot.repository.RedemptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Randgo member registration.
 *
 * Before we can issue coupons to a customer via CouponBasketCheckout,
 * they must exist as a member in Randgo.
 *
 * We use phone number as the unique identifier (PrimaryKeyName = "Cell Phone").
 *
 * FLOW:
 * 1. Check local memory cache (phone already registered this session?)
 * 2. Call Randgo Member Import
 * 3. Poll batch status until complete (async process)
 * 4. Cache the phone so we don't re-register next time
 *
 * NOTE: Member imports < 10 members are processed directly (no queue).
 * Single member imports (our use case) complete quickly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RandgoMemberService {

    @Value("${randgo.member.identifier.guid:YOUR_MEMBER_IDENTIFIER_GUID}")
    private String memberIdentifierGuid;

    @Value("${randgo.mock.mode:true}")
    private boolean mockMode;

    private final RandgoApiClient randgoApiClient;
    private final RedemptionRepository redemptionRepository;

    // In-memory cache of registered phone numbers
    // Avoids redundant import calls for repeat customers
    // Clears on restart (acceptable — Randgo handles duplicates gracefully)
    private final ConcurrentHashMap<String, Boolean> registeredPhones = new ConcurrentHashMap<>();

    // Phones with an import batch submitted but not yet confirmed complete, keyed to their batch GUID.
    // Lets a follow-up call (e.g. the user tapping Coupons again after an IMPORT_PENDING) re-check the
    // SAME batch instead of submitting a fresh import, so a first-time member converges to IMPORTED.
    private final ConcurrentHashMap<String, String> pendingBatches = new ConcurrentHashMap<>();

    private static final int MAX_POLL_ATTEMPTS = 10;   // full budget for the blocking checkout path
    private static final long POLL_INTERVAL_MS = 2000; // 2 seconds

    /** Poll attempts for the JIT SSO path. Sized so a first-time import usually completes IN-BUDGET →
     *  IMPORTED (single-tap sign-in behind the gateway's holding message), while staying comfortably under
     *  the gateway's response timeout so the status is returned rather than cut off. 3 × 2s ≈ up to ~7s of
     *  polling; if the import genuinely exceeds it we return IMPORT_PENDING (rare) and the user retries. */
    @Value("${randgo.member.sso-poll-attempts:3}")
    private int ssoPollAttempts;

    /** Outcome of a member-ensure attempt, so callers can act on ready vs still-importing vs failed. */
    public enum EnsureStatus { ALREADY_REGISTERED, IMPORTED, IMPORT_PENDING, FAILED }

    /**
     * Checkout path — ensures the member exists before coupon issuance, blocking up to the full poll
     * budget. Returns false only when we could not even submit the import; a submitted-but-unconfirmed
     * import still returns true (the checkout call itself is the backstop). Preserves the prior
     * contract; the caller may ignore the result.
     *
     * @param phoneNumber WhatsApp number e.g. "27831234567"
     */
    public boolean ensureMemberRegistered(String phoneNumber) {
        return ensureInternal(phoneNumber, MAX_POLL_ATTEMPTS) != EnsureStatus.FAILED;
    }

    /**
     * JIT SSO path — returns a bounded, fast status so the gateway can mint on ready, nudge the user to
     * retry on IMPORT_PENDING, or fail-open on FAILED, WITHOUT stalling sign-in. Reuses the DB-cached
     * RandGo session token (never a fresh Login — Randgo's 1/day limit).
     *
     * @param phoneNumber WhatsApp number e.g. "27831234567"
     */
    public EnsureStatus ensureForSso(String phoneNumber) {
        return ensureInternal(phoneNumber, ssoPollAttempts);
    }

    /**
     * Ensures a customer is registered in Randgo, polling the import batch up to {@code maxAttempts}
     * times. Fast paths (mock / memory / DB cache) return ALREADY_REGISTERED immediately; a fresh
     * import that completes in-budget returns IMPORTED, one that does not returns IMPORT_PENDING, and a
     * submit failure returns FAILED.
     */
    private EnsureStatus ensureInternal(String phoneNumber, int maxAttempts) {
        if (mockMode) {
            log.info("MOCK: Member {} assumed registered", phoneNumber);
            return EnsureStatus.ALREADY_REGISTERED;
        }

        // Check memory cache first (fast path)
        if (registeredPhones.containsKey(phoneNumber)) {
            log.debug("Member +{} already registered (memory cache)", phoneNumber);
            return EnsureStatus.ALREADY_REGISTERED;
        }

        // Check DB — survives app restarts, prevents re-registering existing members
        if (redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(phoneNumber)) {
            log.debug("Member +{} already registered (DB cache) — warming memory cache", phoneNumber);
            registeredPhones.put(phoneNumber, true);
            return EnsureStatus.ALREADY_REGISTERED;
        }

        try {
            // Reuse an in-flight batch from a prior tap so a retry re-checks the SAME import.
            String batchGuid = pendingBatches.get(phoneNumber);
            if (batchGuid == null) {
                log.info("Registering new member +{} in Randgo...", phoneNumber);
                batchGuid = randgoApiClient.importMember(phoneNumber, memberIdentifierGuid);
                if (batchGuid == null) {
                    log.error("Member import returned null batch GUID for +{}", phoneNumber);
                    return EnsureStatus.FAILED;
                }
                pendingBatches.put(phoneNumber, batchGuid);
                log.info("Member import batch created: {} for +{}", batchGuid, phoneNumber);
            } else {
                log.info("Re-checking in-flight import batch {} for +{}", batchGuid, phoneNumber);
            }

            // Poll until complete (single member imports are fast — usually 1-2 polls)
            boolean completed = pollBatchUntilComplete(batchGuid, maxAttempts);

            if (completed) {
                registeredPhones.put(phoneNumber, true);
                pendingBatches.remove(phoneNumber);
                log.info("Member +{} registered successfully in Randgo", phoneNumber);
                return EnsureStatus.IMPORTED;
            }

            // Not confirmed within the budget. The import continues on Randgo's side; a follow-up call
            // re-checks the same batch. Checkout treats this optimistically; SSO asks the user to retry.
            log.warn("Member import not yet complete for +{} — pending (batch {})", phoneNumber, batchGuid);
            return EnsureStatus.IMPORT_PENDING;

        } catch (Exception e) {
            log.error("Member registration failed for +{}: {}", phoneNumber, e.getMessage());
            return EnsureStatus.FAILED;
        }
    }

    /**
     * Polls batch status until complete or {@code maxAttempts} reached (each attempt waits
     * {@link #POLL_INTERVAL_MS}). From Randgo doc: monitor batches to ensure completion.
     */
    private boolean pollBatchUntilComplete(String batchGuid, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);

                boolean complete = randgoApiClient.isMemberImportComplete(batchGuid);
                if (complete) {
                    log.info("Batch {} completed on attempt {}", batchGuid, attempt);
                    return true;
                }

                log.debug("Batch {} not yet complete (attempt {}/{})", batchGuid, attempt, maxAttempts);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Error polling batch {}: {}", batchGuid, e.getMessage());
            }
        }

        return false;
    }

    /**
     * Pre-warm the cache with a known registered phone number.
     * Called from DataSeeder in dev mode.
     */
    public void markAsRegistered(String phoneNumber) {
        registeredPhones.put(phoneNumber, true);
    }

    public int getRegisteredCount() {
        return registeredPhones.size();
    }
}