package com.acidcouponbot.model;

/**
 * Lifecycle of a bulk coupon campaign run.
 *
 * CREATED   — recipients loaded from CSV, nothing sent to RandGo yet.
 * RUNNING   — the runner is actively importing/issuing.
 * PAUSED    — halted mid-run (a 401 during the campaign, or too many consecutive failures);
 *             carries a paused_reason and is resumable.
 * COMPLETED — every recipient reached a terminal state (ISSUED / ISSUE_FAILED / SKIPPED).
 * FAILED    — the run could not start or was abandoned (e.g. no valid RandGo token to begin with).
 */
public enum CampaignStatus {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}
