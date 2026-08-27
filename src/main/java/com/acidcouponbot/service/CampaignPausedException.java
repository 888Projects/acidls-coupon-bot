package com.acidcouponbot.service;

/**
 * Internal signal used by the campaign runner to pause a run for a NON-401 reason — an admin pause request,
 * too many consecutive checkout failures, or a missing bundle. Carries the human-readable paused_reason.
 * (A 401 / login-suppression pauses via {@code CampaignLoginSuppressedException}; both end in the same
 * PAUSED handling.)
 */
public class CampaignPausedException extends RuntimeException {
    public CampaignPausedException(String message) {
        super(message);
    }
}
