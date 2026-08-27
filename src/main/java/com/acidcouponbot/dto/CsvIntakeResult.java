package com.acidcouponbot.dto;

import java.util.List;

/**
 * Outcome of a campaign CSV upload. Import-only — no RandGo calls were made to produce this.
 *
 * @param runId            the created campaign_runs id
 * @param name             the run name
 * @param totalDataLines   data rows read (excludes the header)
 * @param accepted         valid, de-duplicated recipients persisted as PENDING
 * @param duplicatesInFile rows dropped as duplicates of an earlier row in the same file
 * @param rejected         rows dropped for failing ^27[0-9]{9}$ (or a missing phone cell)
 * @param rejectedSamples  a capped sample of rejected values (value + reason) for the admin to eyeball
 */
public record CsvIntakeResult(
        Long runId,
        String name,
        int totalDataLines,
        int accepted,
        int duplicatesInFile,
        int rejected,
        List<String> rejectedSamples) {}
