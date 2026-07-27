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
    @DisplayName("import not confirmed in budget → IMPORT_PENDING; retry re-checks the SAME batch → IMPORTED")
    void pendingThenConverges() {
        when(redemptionRepository.existsByPhoneNumberAndMemberRegisteredInRandgoTrue(PHONE)).thenReturn(false);
        when(randgoApiClient.importMember(PHONE, GUID)).thenReturn("batch-1");
        // First tap: not complete yet → PENDING. Second tap: complete → IMPORTED.
        when(randgoApiClient.isMemberImportComplete("batch-1")).thenReturn(false, true);

        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.IMPORT_PENDING);
        assertThat(service.ensureForSso(PHONE)).isEqualTo(EnsureStatus.IMPORTED);

        // Only ONE import was submitted across both taps — the retry re-checked the in-flight batch.
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
