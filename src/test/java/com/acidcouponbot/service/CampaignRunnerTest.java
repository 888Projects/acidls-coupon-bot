package com.acidcouponbot.service;

import com.acidcouponbot.model.CampaignRun;
import com.acidcouponbot.model.CampaignStatus;
import com.acidcouponbot.model.CampaignRecipient;
import com.acidcouponbot.model.RecipientStatus;
import com.acidcouponbot.randgo.CampaignLoginSuppressedException;
import com.acidcouponbot.randgo.RandgoApiClient;
import com.acidcouponbot.randgo.RandgoSessionManager;
import com.acidcouponbot.randgo.dto.RandgoCouponCheckoutResponse;
import com.acidcouponbot.repository.CampaignRecipientRepository;
import com.acidcouponbot.repository.CampaignRunRepository;
import com.acidcouponbot.service.CampaignRunner;
import com.acidcouponbot.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Campaign runner — the RandGo execution core, driven synchronously via {@code runToCompletion} with mocked
 * RandGo collaborators and real H2 repos. Covers: import→issue happy path, the thread-scoped login guard
 * (a 401/suppression pauses), a raw checkout 401 pausing with the same handling, the consecutive-failure
 * pause, resume skipping ISSUED, and the pre-flight/enabled gates on start().
 */
@DataJpaTest
@Import(CampaignRunner.class)
@TestPropertySource(properties = {
        "campaign.enabled=true",
        "campaign.import-batch-size=1000",
        "campaign.checkout-delay-ms=0",
        "campaign.batch-poll-interval-ms=1",
        "campaign.batch-poll-max-attempts=3",
        "campaign.max-consecutive-failures=3",
        "randgo.member.identifier.guid=test-guid"
})
@DisplayName("CampaignRunner")
class CampaignRunnerTest {

    @Autowired CampaignRunner runner;
    @Autowired CampaignRunRepository runs;
    @Autowired CampaignRecipientRepository recipients;

    @MockBean RandgoApiClient randgoApiClient;
    @MockBean RandgoSessionManager sessionManager;
    @MockBean CouponService couponService;

    private Long runId;

    @BeforeEach
    void setUp() {
        // The runner bean is shared across tests (cached context); reset the mutable gate so a prior test's
        // ReflectionTestUtils tweak (e.g. enabled=false) can't leak into the next test.
        ReflectionTestUtils.setField(runner, "enabled", true);
        CampaignRun run = runs.save(CampaignRun.builder().name("t").status(CampaignStatus.CREATED).build());
        runId = run.getId();
        when(couponService.selectBundleVoucherGuids()).thenReturn(List.of("v1"));
    }

    private void seed(String phone, RecipientStatus status) {
        recipients.save(CampaignRecipient.builder()
                .campaignRunId(runId).phone(phone).name("N").surname("S").status(status).build());
    }

    private RandgoCouponCheckoutResponse withCodes(String... codes) {
        RandgoCouponCheckoutResponse resp = new RandgoCouponCheckoutResponse();
        resp.setSuccess(true);
        RandgoCouponCheckoutResponse.Basket basket = new RandgoCouponCheckoutResponse.Basket();
        basket.setGuid("b");
        basket.setCodes(List.of(codes).stream().map(c -> {
            RandgoCouponCheckoutResponse.IssuedCode ic = new RandgoCouponCheckoutResponse.IssuedCode();
            ic.setIssuedCode(c);
            return ic;
        }).toList());
        resp.setBaskets(List.of(basket));
        return resp;
    }

    private RandgoCouponCheckoutResponse noCodes() {
        RandgoCouponCheckoutResponse resp = new RandgoCouponCheckoutResponse();
        resp.setSuccess(true);
        RandgoCouponCheckoutResponse.Basket basket = new RandgoCouponCheckoutResponse.Basket();
        basket.setCodes(List.of());
        resp.setBaskets(List.of(basket));
        return resp;
    }

    private CampaignRun reload() {
        return runs.findById(runId).orElseThrow();
    }

    // ── Happy path ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("import (batched, confirmed) → issue → all ISSUED, run COMPLETED, campaign mode entered/exited")
    void happyPath() {
        seed("27830000001", RecipientStatus.PENDING);
        seed("27830000002", RecipientStatus.PENDING);
        when(randgoApiClient.importMembers(anyList(), eq("test-guid"))).thenReturn("batch-1");
        when(randgoApiClient.isMemberImportComplete("batch-1")).thenReturn(true);
        when(randgoApiClient.checkoutCoupons(anyString(), anyList())).thenReturn(withCodes("CODE-X"));

        runner.runToCompletion(runId, false);

        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        assertThat(recipients.countByCampaignRunIdAndStatus(runId, RecipientStatus.ISSUED)).isEqualTo(2);
        assertThat(reload().getProcessedCount()).isEqualTo(2);
        verify(sessionManager).enterCampaignMode();
        verify(sessionManager).exitCampaignMode();
    }

    // ── Login guard ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login suppressed during import → run PAUSED, recipients left IMPORTING (retriable), mode exited")
    void loginSuppressed_duringImport_pauses() {
        seed("27830000001", RecipientStatus.PENDING);
        when(randgoApiClient.importMembers(anyList(), anyString()))
                .thenThrow(new CampaignLoginSuppressedException("Login suppressed to protect the daily cap."));

        runner.runToCompletion(runId, false);

        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(reload().getPausedReason()).contains("Login suppressed");
        assertThat(recipients.countByCampaignRunIdAndStatus(runId, RecipientStatus.IMPORTING)).isEqualTo(1);
        verify(sessionManager).exitCampaignMode();
        verify(randgoApiClient, never()).checkoutCoupons(anyString(), anyList());
    }

    @Test
    @DisplayName("raw checkout 401 → run PAUSED (same handling), that recipient ISSUE_FAILED")
    void rawCheckout401_pauses() {
        seed("27830000001", RecipientStatus.IMPORTED);
        when(randgoApiClient.checkoutCoupons(anyString(), anyList()))
                .thenThrow(WebClientResponseException.create(
                        401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        runner.runToCompletion(runId, false);

        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(reload().getPausedReason()).contains("401 during checkout");
        assertThat(recipients.countByCampaignRunIdAndStatus(runId, RecipientStatus.ISSUE_FAILED)).isEqualTo(1);
    }

    // ── Consecutive-failure circuit breaker ──────────────────────────────────────────

    @Test
    @DisplayName("N consecutive checkout failures → run PAUSED before burning the rest")
    void consecutiveFailures_pause() {
        for (int i = 1; i <= 5; i++) seed("2783000000" + i, RecipientStatus.IMPORTED);
        when(randgoApiClient.checkoutCoupons(anyString(), anyList())).thenReturn(noCodes());   // always fails

        runner.runToCompletion(runId, false);

        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(reload().getPausedReason()).contains("consecutive checkout failures");
        // Stopped at the 3rd failure — the 4th/5th recipients were never attempted.
        verify(randgoApiClient, times(3)).checkoutCoupons(anyString(), anyList());
    }

    // ── Resume ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resume issues IMPORTED recipients and NEVER re-touches ISSUED")
    void resume_skipsIssued() {
        seed("27830000001", RecipientStatus.ISSUED);
        seed("27830000002", RecipientStatus.ISSUED);
        seed("27830000003", RecipientStatus.IMPORTED);
        seed("27830000004", RecipientStatus.IMPORTED);
        runs.findById(runId).ifPresent(r -> { r.setStatus(CampaignStatus.PAUSED); runs.save(r); });
        when(randgoApiClient.checkoutCoupons(anyString(), anyList())).thenReturn(withCodes("CODE-R"));

        runner.runToCompletion(runId, false);

        assertThat(recipients.countByCampaignRunIdAndStatus(runId, RecipientStatus.ISSUED)).isEqualTo(4);
        // Only the two IMPORTED were checked out — the two already-ISSUED were skipped.
        verify(randgoApiClient, times(2)).checkoutCoupons(anyString(), anyList());
        verify(randgoApiClient, never()).importMembers(anyList(), anyString());   // nothing needed importing
    }

    // ── start() gates (synchronous, before async dispatch) ───────────────────────────

    @Test
    @DisplayName("start() with campaign.enabled=false → rejected, no state change")
    void start_disabled_rejected() {
        ReflectionTestUtils.setField(runner, "enabled", false);

        assertThatThrownBy(() -> runner.start(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaign.enabled=false");
        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.CREATED);
    }

    @Test
    @DisplayName("start() pre-flight: no valid cached token → run FAILED, never dispatched")
    void start_noToken_failsFast() {
        when(sessionManager.hasValidCachedToken()).thenReturn(false);

        assertThatThrownBy(() -> runner.start(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no valid cached RandGo token");
        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.FAILED);
        verify(sessionManager, never()).enterCampaignMode();
    }

    // ── Dry run ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dry-run reports the plan, calls NOTHING on RandGo, and changes no state")
    void dryRun_callsNothing() {
        seed("27830000001", RecipientStatus.PENDING);
        seed("27830000002", RecipientStatus.PENDING);

        var report = runner.dryRun(runId);

        assertThat(report.wouldImport()).isEqualTo(2);
        assertThat(report.wouldIssue()).isEqualTo(2);
        assertThat(report.bundleCoupons()).isEqualTo(1);
        assertThat(reload().getStatus()).isEqualTo(CampaignStatus.CREATED);   // untouched
        verify(randgoApiClient, never()).importMembers(anyList(), anyString());
        verify(randgoApiClient, never()).checkoutCoupons(anyString(), anyList());
        verify(sessionManager, never()).enterCampaignMode();
    }
}
