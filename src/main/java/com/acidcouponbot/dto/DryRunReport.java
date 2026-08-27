package com.acidcouponbot.dto;

/**
 * Result of a dry run — what the runner WOULD do for a run, having called nothing on RandGo and mutated
 * no state.
 */
public record DryRunReport(
        Long runId,
        String status,
        long wouldImport,
        int importBatches,
        long wouldIssue,
        int bundleCoupons,
        long checkoutDelayMs,
        long estimatedSeconds,
        String note) {}
