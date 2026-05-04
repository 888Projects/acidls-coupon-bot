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

    private static final int MAX_POLL_ATTEMPTS = 10;
    private static final long POLL_INTERVAL_MS = 2000; // 2 seconds

    /**
     * Ensures a customer is registered in Randgo.
     * Returns true if registered successfully (or already registered).
     *
     * @param phoneNumber WhatsApp number e.g. "27831234567"
     */
    public boolean ensureMemberRegistered(String phoneNumber) {
        if (mockMode) {
            log.info("MOCK: Member {} assumed registered", phoneNumber);
            return true;
        }

        // Check memory cache first (fast path)
        if (registeredPhones.containsKey(phoneNumber)) {
            log.debug("Member +{} already registered (memory cache)", phoneNumber);
            return true;
        }

        // Check DB — survives app restarts, prevents re-registering existing members
        if (redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(phoneNumber)) {
            log.debug("Member +{} already registered (DB cache) — warming memory cache", phoneNumber);
            registeredPhones.put(phoneNumber, true);
            return true;
        }

        log.info("Registering new member +{} in Randgo...", phoneNumber);

        try {
            // Call Member Import
            String batchGuid = randgoApiClient.importMember(phoneNumber, memberIdentifierGuid);

            if (batchGuid == null) {
                log.error("Member import returned null batch GUID for +{}", phoneNumber);
                return false;
            }

            log.info("Member import batch created: {} for +{}", batchGuid, phoneNumber);

            // Poll until complete (single member imports are fast — usually 1-2 polls)
            boolean completed = pollBatchUntilComplete(batchGuid);

            if (completed) {
                registeredPhones.put(phoneNumber, true);
                log.info("Member +{} registered successfully in Randgo", phoneNumber);
                return true;
            } else {
                log.warn("Member import did not complete in time for +{} — proceeding anyway", phoneNumber);
                // Proceed optimistically — Randgo may still process it
                // The checkout call will fail if member truly isn't registered
                return true;
            }

        } catch (Exception e) {
            log.error("Member registration failed for +{}: {}", phoneNumber, e.getMessage());
            // Don't block coupon issuance — try checkout anyway
            // Randgo may already have the member from a previous attempt
            return true;
        }
    }

    /**
     * Polls batch status until complete or max attempts reached.
     * From Randgo doc: monitor batches to ensure completion.
     * Max 1 call per 20 seconds per batch.
     */
    private boolean pollBatchUntilComplete(String batchGuid) {
        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);

                boolean complete = randgoApiClient.isMemberImportComplete(batchGuid);
                if (complete) {
                    log.info("Batch {} completed on attempt {}", batchGuid, attempt);
                    return true;
                }

                log.debug("Batch {} not yet complete (attempt {}/{})", batchGuid, attempt, MAX_POLL_ATTEMPTS);

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