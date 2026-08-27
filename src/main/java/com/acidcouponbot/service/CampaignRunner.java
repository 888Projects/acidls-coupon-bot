package com.acidcouponbot.service;

import com.acidcouponbot.dto.CampaignStatusDto;
import com.acidcouponbot.dto.DryRunReport;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bulk coupon campaign runner — imports recipients into RandGo (batched), then issues the current bundle to
 * each via CouponBasketCheckout, all resumable from the database.
 *
 * <p><b>Login guard (Option B — thread-scoped).</b> The runner executes on a single dedicated thread and
 * marks it {@link RandgoSessionManager#enterCampaignMode()}, so a 401 during the campaign is suppressed
 * (never re-authenticates) and pauses the run instead. Live SSO on other threads keeps its own 401 recovery.
 * Before starting, a valid cached token is asserted — the campaign never spends the daily Login.
 *
 * <p><b>Never parallel.</b> A single-thread executor plus a RUNNING guard enforce one campaign at a time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignRunner {

    private final CampaignRunRepository runRepo;
    private final CampaignRecipientRepository recipientRepo;
    private final RandgoApiClient randgoApiClient;
    private final RandgoSessionManager sessionManager;
    private final CouponService couponService;

    /** Master gate for the RUNNER (not the upload) — false ⇒ CSVs can be loaded/inspected but nothing runs. */
    @Value("${campaign.enabled:false}")
    private boolean enabled;

    @Value("${campaign.import-batch-size:1000}")
    private int importBatchSize;

    @Value("${campaign.checkout-delay-ms:1000}")
    private long checkoutDelayMs;

    @Value("${campaign.batch-poll-interval-ms:60000}")
    private long batchPollIntervalMs;

    /** Max BatchGetByBatchGuid polls per batch before giving up (recipients → IMPORT_FAILED, retriable).
     *  Added so the poll is bounded; not in the original config list. */
    @Value("${campaign.batch-poll-max-attempts:20}")
    private int batchPollMaxAttempts;

    @Value("${campaign.max-consecutive-failures:10}")
    private int maxConsecutiveFailures;

    @Value("${randgo.member.identifier.guid:YOUR_MEMBER_IDENTIFIER_GUID}")
    private String memberIdentifierGuid;

    /** Recipient statuses that still need importing (fresh, previously failed, or interrupted mid-import). */
    private static final List<RecipientStatus> NEEDS_IMPORT =
            List.of(RecipientStatus.PENDING, RecipientStatus.IMPORT_FAILED, RecipientStatus.IMPORTING);

    /** Recipient statuses ready to (re)issue — imported, or a checkout that failed / was interrupted. */
    private static final List<RecipientStatus> NEEDS_ISSUE =
            List.of(RecipientStatus.IMPORTED, RecipientStatus.ISSUE_FAILED, RecipientStatus.ISSUING);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "campaign-runner");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);

    // ── Control ─────────────────────────────────────────────────────────────────

    /**
     * Start (or resume) a run asynchronously on the single runner thread. Validates the run state, enforces
     * one-at-a-time, and asserts a warm token BEFORE dispatching — so a bad token fails fast and synchronously
     * rather than mid-run. Resume is just start() on a PAUSED run: the phases pick up PENDING/failed and skip
     * ISSUED.
     */
    public synchronized void start(Long runId) {
        if (!enabled) {
            throw new IllegalStateException("campaign.enabled=false — the runner is disabled (upload still works)");
        }
        CampaignRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("no campaign run " + runId));
        if (run.getStatus() != CampaignStatus.CREATED && run.getStatus() != CampaignStatus.PAUSED) {
            throw new IllegalStateException("run " + runId + " is " + run.getStatus()
                    + " — only CREATED or PAUSED runs can be started/resumed");
        }
        if (runRepo.existsByStatus(CampaignStatus.RUNNING) || running.get()) {
            throw new IllegalStateException("a campaign is already RUNNING — one at a time");
        }
        // Pre-flight: never let the campaign be the thing that spends the daily Login.
        if (!sessionManager.hasValidCachedToken()) {
            run.setStatus(CampaignStatus.FAILED);
            run.setPausedReason("pre-flight failed: no valid cached RandGo token — refusing to start "
                    + "(starting would spend the daily Login). Warm the token first, then retry.");
            runRepo.save(run);
            throw new IllegalStateException(run.getPausedReason());
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("runner busy");
        }
        pauseRequested.set(false);
        executor.submit(() -> {
            try {
                runToCompletion(runId, false);
            } catch (Exception e) {
                log.error("CAMPAIGN_RUN_UNCAUGHT run={}: {}", runId, e.getMessage(), e);
            } finally {
                running.set(false);
            }
        });
        log.warn("CAMPAIGN_STARTED run={} (async on the campaign-runner thread)", runId);
    }

    /** Cooperative pause — the runner stops after the current recipient/chunk and marks the run PAUSED. */
    public void requestPause() {
        pauseRequested.set(true);
        log.warn("CAMPAIGN_PAUSE_REQUESTED — will pause after the current recipient");
    }

    public boolean isRunnerBusy() {
        return running.get();
    }

    // ── Execution core (package-private so tests can drive it synchronously) ──────

    void runToCompletion(Long runId, boolean dryRun) {
        CampaignRun run = runRepo.findById(runId).orElseThrow();

        if (dryRun) {   // never mutates state, never calls RandGo
            return;
        }

        run.setStatus(CampaignStatus.RUNNING);
        if (run.getStartedAt() == null) run.setStartedAt(LocalDateTime.now());
        run.setPausedReason(null);
        runRepo.save(run);

        sessionManager.enterCampaignMode();   // thread-scoped: suppress forceRelogin on THIS thread only
        try {
            importPhase(run);
            issuePhase(run);
            finalizeRun(run);
        } catch (CampaignLoginSuppressedException e) {
            pause(run, e.getMessage());
        } catch (CampaignPausedException e) {
            pause(run, e.getMessage());
        } catch (Exception e) {
            log.error("CAMPAIGN_RUN_ERROR run={}: {}", run.getId(), e.getMessage(), e);
            pause(run, "unexpected error: " + e.getMessage());
        } finally {
            sessionManager.exitCampaignMode();
        }
    }

    // ── Import phase (batched) ────────────────────────────────────────────────────

    private void importPhase(CampaignRun run) {
        List<CampaignRecipient> toImport = recipientRepo.findByCampaignRunIdAndStatusInOrderByIdAsc(
                run.getId(), NEEDS_IMPORT, Pageable.unpaged());
        if (toImport.isEmpty()) {
            log.info("CAMPAIGN_IMPORT run={} nothing to import", run.getId());
            return;
        }
        log.warn("CAMPAIGN_IMPORT run={} importing {} recipient(s) in batches of {}",
                run.getId(), toImport.size(), importBatchSize);

        for (int i = 0; i < toImport.size(); i += importBatchSize) {
            checkPauseRequested();
            List<CampaignRecipient> chunk =
                    new ArrayList<>(toImport.subList(i, Math.min(i + importBatchSize, toImport.size())));
            importChunk(run, chunk);
        }
    }

    private void importChunk(CampaignRun run, List<CampaignRecipient> chunk) {
        List<String> phones = chunk.stream().map(CampaignRecipient::getPhone).toList();
        LocalDateTime now = LocalDateTime.now();
        chunk.forEach(r -> {
            r.setStatus(RecipientStatus.IMPORTING);
            r.setAttempts(r.getAttempts() + 1);
            r.setLastAttemptAt(now);
        });
        recipientRepo.saveAll(chunk);

        String batchGuid;
        try {
            batchGuid = randgoApiClient.importMembers(phones, memberIdentifierGuid);
        } catch (CampaignLoginSuppressedException e) {
            throw e;   // → pause (leaves the chunk IMPORTING, retriable on resume)
        } catch (WebClientResponseException.Unauthorized e) {
            throw new CampaignLoginSuppressedException("401 during Member/Import — token invalid; pausing run");
        } catch (Exception e) {
            markFailed(run, chunk, RecipientStatus.IMPORT_FAILED, "import submit failed: " + e.getMessage());
            return;
        }
        if (batchGuid == null) {
            markFailed(run, chunk, RecipientStatus.IMPORT_FAILED, "import returned null batch GUID");
            return;
        }

        chunk.forEach(r -> r.setRandgoBatchGuid(batchGuid));
        recipientRepo.saveAll(chunk);

        if (pollBatch(batchGuid)) {
            setStatus(chunk, RecipientStatus.IMPORTED, null);
            log.info("CAMPAIGN_IMPORT run={} batch={} → IMPORTED ({} members)", run.getId(), batchGuid, chunk.size());
        } else {
            markFailed(run, chunk, RecipientStatus.IMPORT_FAILED,
                    "batch not confirmed within poll budget (batch " + batchGuid + ")");
            log.warn("CAMPAIGN_IMPORT run={} batch={} NOT confirmed — {} recipient(s) IMPORT_FAILED (retriable)",
                    run.getId(), batchGuid, chunk.size());
        }
    }

    /** Poll BatchGetByBatchGuid at ≥60s intervals; stop on Completed; never re-poll a completed batch. */
    private boolean pollBatch(String batchGuid) {
        for (int attempt = 1; attempt <= batchPollMaxAttempts; attempt++) {
            if (!sleep(batchPollIntervalMs)) return false;   // ≥60s spacing; interrupted → bail (retriable)
            boolean complete;
            try {
                complete = randgoApiClient.isMemberImportComplete(batchGuid);
            } catch (CampaignLoginSuppressedException e) {
                throw e;
            } catch (WebClientResponseException.Unauthorized e) {
                throw new CampaignLoginSuppressedException("401 during batch poll — token invalid; pausing run");
            } catch (Exception e) {
                log.warn("CAMPAIGN_BATCH_POLL batch={} error: {}", batchGuid, e.getMessage());
                return false;
            }
            if (complete) return true;
            log.info("CAMPAIGN_BATCH_POLL batch={} not complete (attempt {}/{})",
                    batchGuid, attempt, batchPollMaxAttempts);
        }
        return false;
    }

    // ── Issue phase (per recipient) ───────────────────────────────────────────────

    private void issuePhase(CampaignRun run) {
        List<String> bundle = couponService.selectBundleVoucherGuids();
        if (bundle.isEmpty()) {
            throw new CampaignPausedException(
                    "no coupon bundle configured (no active campaign or no enabled vouchers) — cannot issue");
        }
        List<CampaignRecipient> toIssue = recipientRepo.findByCampaignRunIdAndStatusInOrderByIdAsc(
                run.getId(), NEEDS_ISSUE, Pageable.unpaged());
        if (toIssue.isEmpty()) {
            log.info("CAMPAIGN_ISSUE run={} nothing to issue", run.getId());
            return;
        }
        log.warn("CAMPAIGN_ISSUE run={} issuing to {} recipient(s); bundle={} coupon(s); delay={}ms",
                run.getId(), toIssue.size(), bundle.size(), checkoutDelayMs);

        int consecutiveFailures = 0;
        int processed = 0;
        for (CampaignRecipient r : toIssue) {
            checkPauseRequested();

            r.setStatus(RecipientStatus.ISSUING);
            r.setAttempts(r.getAttempts() + 1);
            r.setLastAttemptAt(LocalDateTime.now());
            recipientRepo.save(r);

            try {
                RandgoCouponCheckoutResponse resp = randgoApiClient.checkoutCoupons(r.getPhone(), bundle);
                List<String> codes = extractCodes(resp);
                if (codes.isEmpty()) {
                    fail(run, r, "checkout returned no codes");
                    consecutiveFailures++;
                } else {
                    r.setIssuedCodes(String.join(",", codes));
                    r.setStatus(RecipientStatus.ISSUED);
                    r.setLastError(null);
                    recipientRepo.save(r);
                    run.setProcessedCount(run.getProcessedCount() + 1);
                    consecutiveFailures = 0;
                }
            } catch (WebClientResponseException.Unauthorized e) {
                // Addition #1: a raw checkout 401 (checkout bypasses callWithRetry, so it never self-relogins)
                // means the token is dead — pause the whole run with the SAME handling as login-suppression.
                r.setStatus(RecipientStatus.ISSUE_FAILED);
                r.setLastError("401 Unauthorized during checkout");
                recipientRepo.save(r);
                throw new CampaignLoginSuppressedException(
                        "401 during checkout — token invalid; pausing (continuing would burn recipients on a dead token)");
            } catch (CampaignLoginSuppressedException e) {
                throw e;
            } catch (Exception e) {
                fail(run, r, "checkout failed: " + e.getMessage());
                consecutiveFailures++;
            }

            runRepo.save(run);   // persist counters as we go (resume + live status)

            if (++processed % 100 == 0) {
                log.info("CAMPAIGN_ISSUE run={} progress {}/{} (failed so far={})",
                        run.getId(), processed, toIssue.size(), run.getFailedCount());
            }

            if (consecutiveFailures >= maxConsecutiveFailures) {
                throw new CampaignPausedException(maxConsecutiveFailures
                        + " consecutive checkout failures — systemic problem; pausing to avoid burning through recipients");
            }

            if (!sleep(checkoutDelayMs)) {
                throw new CampaignPausedException("interrupted during throttle delay — pausing");
            }
        }
    }

    // ── Status / dry-run (Phase 4) ────────────────────────────────────────────────

    public CampaignStatusDto status(Long runId) {
        CampaignRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("no campaign run " + runId));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : recipientRepo.countGroupedByStatus(runId)) {
            counts.put(String.valueOf(row[0]), (Long) row[1]);
        }
        long issued = counts.getOrDefault(RecipientStatus.ISSUED.name(), 0L);
        long total = recipientRepo.countByCampaignRunId(runId);
        long remaining = total - issued;
        long etaSeconds = Math.max(0, remaining) * Math.max(0, checkoutDelayMs) / 1000;
        return new CampaignStatusDto(
                run.getId(), run.getName(), run.getStatus().name(), run.getPausedReason(),
                run.getTotalRecipients(), run.getProcessedCount(), run.getFailedCount(),
                issued, remaining, counts, etaSeconds, running.get());
    }

    /** Walk the whole flow and call NOTHING — reports what a real run would do. No state change, no RandGo. */
    public DryRunReport dryRun(Long runId) {
        CampaignRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("no campaign run " + runId));

        long wouldImport = 0;
        for (RecipientStatus s : NEEDS_IMPORT) {
            wouldImport += recipientRepo.countByCampaignRunIdAndStatus(runId, s);
        }
        long wouldIssueExisting = 0;   // already-imported recipients ready to issue right now
        for (RecipientStatus s : NEEDS_ISSUE) {
            wouldIssueExisting += recipientRepo.countByCampaignRunIdAndStatus(runId, s);
        }
        // Everything not yet ISSUED/SKIPPED would ultimately be issued if imports succeed.
        long total = recipientRepo.countByCampaignRunId(runId);
        long alreadyIssued = recipientRepo.countByCampaignRunIdAndStatus(runId, RecipientStatus.ISSUED);
        long skipped = recipientRepo.countByCampaignRunIdAndStatus(runId, RecipientStatus.SKIPPED);
        long wouldIssue = total - alreadyIssued - skipped;

        int importBatches = (int) Math.ceil(wouldImport / (double) Math.max(1, importBatchSize));
        int bundle = couponService.selectBundleVoucherGuids().size();   // read-only query, no RandGo call
        long estSeconds = Math.max(0, wouldIssue) * Math.max(0, checkoutDelayMs) / 1000;

        log.warn("CAMPAIGN_DRY_RUN run={} wouldImport={} in {} batch(es) of {}, wouldIssue={} "
                        + "(ready now={}), bundle={} coupon(s), checkoutDelay={}ms — CALLED NOTHING",
                runId, wouldImport, importBatches, importBatchSize, wouldIssue, wouldIssueExisting, bundle, checkoutDelayMs);

        String note = bundle == 0
                ? "WARNING: no bundle configured — a real run would pause at the issue phase"
                : "dry run only — no RandGo calls, no state changed";
        return new DryRunReport(runId, run.getStatus().name(),
                wouldImport, importBatches, wouldIssue, bundle, checkoutDelayMs, estSeconds, note);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private void finalizeRun(CampaignRun run) {
        long unfinished = 0;
        for (RecipientStatus s : List.of(RecipientStatus.PENDING, RecipientStatus.IMPORTING,
                RecipientStatus.IMPORTED, RecipientStatus.ISSUING)) {
            unfinished += recipientRepo.countByCampaignRunIdAndStatus(run.getId(), s);
        }
        long failed = recipientRepo.countByCampaignRunIdAndStatus(run.getId(), RecipientStatus.IMPORT_FAILED)
                + recipientRepo.countByCampaignRunIdAndStatus(run.getId(), RecipientStatus.ISSUE_FAILED);

        if (unfinished == 0 && failed == 0) {
            run.setStatus(CampaignStatus.COMPLETED);
            run.setCompletedAt(LocalDateTime.now());
            run.setPausedReason(null);
            runRepo.save(run);
            log.warn("CAMPAIGN_COMPLETED run={} processed={} failed=0", run.getId(), run.getProcessedCount());
        } else {
            // A full pass finished but some recipients failed/unfinished → PAUSED so a re-trigger resumes them.
            run.setStatus(CampaignStatus.PAUSED);
            run.setPausedReason(failed + " failed and " + unfinished
                    + " unfinished after a full pass — re-run to retry");
            runRepo.save(run);
            log.warn("CAMPAIGN_FINISHED_WITH_REMAINDER run={} failed={} unfinished={} → PAUSED for retry",
                    run.getId(), failed, unfinished);
        }
    }

    private void pause(CampaignRun run, String reason) {
        run.setStatus(CampaignStatus.PAUSED);
        run.setPausedReason(truncate(reason));
        runRepo.save(run);
        log.error("CAMPAIGN_PAUSED run={} reason='{}'", run.getId(), reason);   // loud, per the brief
    }

    private void checkPauseRequested() {
        if (pauseRequested.get()) {
            throw new CampaignPausedException("paused by admin request");
        }
    }

    private void fail(CampaignRun run, CampaignRecipient r, String error) {
        r.setStatus(RecipientStatus.ISSUE_FAILED);
        r.setLastError(truncate(error));
        recipientRepo.save(r);
        run.setFailedCount(run.getFailedCount() + 1);
        log.warn("CAMPAIGN_ISSUE_FAIL run={} phone={} error='{}'", run.getId(), r.getPhone(), error);
    }

    private void setStatus(List<CampaignRecipient> rs, RecipientStatus status, String error) {
        LocalDateTime now = LocalDateTime.now();
        for (CampaignRecipient r : rs) {
            r.setStatus(status);
            r.setLastAttemptAt(now);
            r.setLastError(error == null ? null : truncate(error));
        }
        recipientRepo.saveAll(rs);
    }

    private void markFailed(CampaignRun run, List<CampaignRecipient> rs, RecipientStatus status, String error) {
        setStatus(rs, status, error);
        run.setFailedCount(run.getFailedCount() + rs.size());
        runRepo.save(run);
        log.warn("CAMPAIGN_CHUNK_FAILED run={} n={} status={} error='{}'", run.getId(), rs.size(), status, error);
    }

    private List<String> extractCodes(RandgoCouponCheckoutResponse resp) {
        if (resp == null || !resp.isSuccess() || resp.getBasket() == null || resp.getBasket().getCodes() == null) {
            return List.of();
        }
        return resp.getBasket().getCodes().stream()
                .map(RandgoCouponCheckoutResponse.IssuedCode::getIssuedCode)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private boolean sleep(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
