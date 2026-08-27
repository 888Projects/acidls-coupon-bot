package com.acidcouponbot.model;

/**
 * Per-recipient progress through the campaign. The status column (with campaign_run_id) is what the
 * resume queries filter on, and — together with the UNIQUE(campaign_run_id, phone) constraint — is the
 * idempotency spine: a re-run picks up PENDING / *_FAILED and skips anything already ISSUED.
 *
 * PENDING       — loaded, not yet touched.
 * IMPORTING     — included in a submitted Member/Import batch, awaiting batch completion.
 * IMPORTED      — RandGo confirmed the member exists (ready to issue).
 * IMPORT_FAILED — import could not be confirmed (retriable on re-run).
 * ISSUING       — checkout in flight.
 * ISSUED        — coupon codes issued and stored (terminal, never re-issued).
 * ISSUE_FAILED  — checkout failed (retriable on re-run).
 * SKIPPED       — deliberately not processed (e.g. excluded after load).
 */
public enum RecipientStatus {
    PENDING,
    IMPORTING,
    IMPORTED,
    IMPORT_FAILED,
    ISSUING,
    ISSUED,
    ISSUE_FAILED,
    SKIPPED
}
