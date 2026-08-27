package com.acidcouponbot.randgo;

import com.acidcouponbot.randgo.RandgoMemberService.EnsureStatus;
import com.acidcouponbot.repository.RedemptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ensureForSso — the bounded, status-returning JIT path used by the gateway SSO flow. Pins the fast
 * paths (mock / DB cache → ALREADY_REGISTERED), the import outcomes (IMPORTED / IMPORT_PENDING / FAILED),
 * and the in-flight batch reuse so a first-time member converges to IMPORTED on a retry (never a fresh
 * import per tap). ssoPollAttempts=1 keeps the single poll's sleep short.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RandgoMemberService.ensureForSso")
class RandgoMemberServiceTest {

    private static final String PHONE = "27831234567";
    private static final String GUID  = "member-identifier-guid";

    @Mock RandgoApiClient       randgoApiClient;
    @Mock RedemptionRepository  redemptionRepository;

    RandgoMemberService service;

    @BeforeEach
    void setUp() {
        service = new RandgoMemberService(randgoApiClient, redemptionRepository);
        ReflectionTestUtils.setField(service, "memberIdentifierGuid", GUID);
        ReflectionTestUtils.setField(service, "mockMode", false);
        ReflectionTestUtils.setField(service, "ssoPollAttempts", 1);
        ReflectionTestUtils.setField(service, "checkoutPollAttempts", 1);
        ReflectionTestUtils.setField(service, "firstPollDelayMs", 5L);   // keep the single poll's grace tiny
        // minPollIntervalMs left 0 → effectiveMinPollIntervalMs() clamps to the 60_000ms floor (asserted below).
    }

    @Test
    @DisplayName("mock mode → ALREADY_REGISTERED, no RandGo calls")
    void mockMode_alreadyRegistered() {
        ReflectionTestUtils.setField(service, "mockMode", true);

        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.ALREADY_REGISTERED);
        verify(randgoApiClient, never()).importMember(anyString(), anyString());
    }

    @Test
    @DisplayName("DB says registered → ALREADY_REGISTERED, no import")
    void dbCache_alreadyRegistered() {
        when(redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(PHONE)).thenReturn(true);

        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.ALREADY_REGISTERED);
        verify(randgoApiClient, never()).importMember(anyString(), anyString());
    }

    @Test
    @DisplayName("import submit returns null batch → FAILED")
    void nullBatch_failed() {
        when(redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(PHONE)).thenReturn(false);
        when(randgoApiClient.importMember(PHONE, GUID)).thenReturn(null);

        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.FAILED);
    }

    @Test
    @DisplayName("import completes within the budget → IMPORTED")
    void importCompletes_imported() {
        when(redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(PHONE)).thenReturn(false);
        when(randgoApiClient.importMember(PHONE, GUID)).thenReturn("batch-1");
        when(randgoApiClient.isMemberImportComplete("batch-1")).thenReturn(true);

        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.IMPORTED);
    }

    @Test
    @DisplayName("not confirmed in budget → PENDING; an immediate retry is rate-guarded (no 2nd poll); after the 60s floor the SAME batch re-checks → IMPORTED")
    void pendingThenGuardThenConverges() {
        when(redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(PHONE)).thenReturn(false);
        when(randgoApiClient.importMember(PHONE, GUID)).thenReturn("batch-1");
        // The two ACTUAL polls: first (tap 1) not complete, second (post-floor tap) complete.
        when(randgoApiClient.isMemberImportComplete("batch-1")).thenReturn(false, true);

        // Tap 1: submit + one poll → not complete → PENDING.
        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.IMPORT_PENDING);

        // Immediate retry: the per-batch 60s guard SKIPS the poll (no BatchGetByBatchGuid call) → still PENDING.
        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.IMPORT_PENDING);
        verify(randgoApiClient, times(1)).isMemberImportComplete("batch-1");   // only tap 1 actually polled

        // Simulate the 60s floor elapsing, then retry: the same in-flight batch is polled again → IMPORTED.
        @SuppressWarnings("unchecked")
        Map<String, Long> lastPolled =
                (Map<String, Long>) ReflectionTestUtils.getField(service, "lastPolledAtByBatch");
        lastPolled.put("batch-1", System.currentTimeMillis() - 61_000L);

        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.IMPORTED);

        // Still ONE import across every tap; exactly two real polls (tap 1 + post-floor), never a sub-minute call.
        verify(randgoApiClient, times(1)).importMember(PHONE, GUID);
        verify(randgoApiClient, times(2)).isMemberImportComplete("batch-1");
    }

    @Test
    @DisplayName("checkout path (ensureMemberRegistered) stays truthy for a ready/pending member")
    void checkoutPath_backwardCompatible() {
        when(redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(PHONE)).thenReturn(true);
        assertThat(service.ensureMemberRegistered(PHONE)).isTrue();
        verify(randgoApiClient, never()).importMember(anyString(), eq(GUID));
    }
}
