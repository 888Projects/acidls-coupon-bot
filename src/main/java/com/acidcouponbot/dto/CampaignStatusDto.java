package com.acidcouponbot.dto;

import java.util.Map;

/**
 * Snapshot of a campaign run for the status endpoint: run-level fields, a histogram of recipient statuses,
 * position, and a rough ETA (remaining-to-issue × checkout delay).
 */
public record CampaignStatusDto(
        Long runId,
        String name,
        String status,
        String pausedReason,
        int totalRecipients,
        int processedCount,
        int failedCount,
        long issued,
        long remaining,
        Map<String, Long> countsByStatus,
        long etaSeconds,
        boolean runnerBusy) {}
