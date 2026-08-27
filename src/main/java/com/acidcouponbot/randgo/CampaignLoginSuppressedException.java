package com.acidcouponbot.randgo;

/**
 * Thrown by {@link RandgoSessionManager#forceRelogin()} when a 401 occurs on a thread that is in campaign
 * mode. Instead of re-authenticating (which would risk RandGo's 1-Login-per-day cap and suspend the
 * production account), the session manager refuses and throws this — the campaign runner catches it and
 * pauses the run. Live SSO on other threads is unaffected and keeps its normal 401→relogin recovery.
 */
public class CampaignLoginSuppressedException extends RuntimeException {
    public CampaignLoginSuppressedException(String message) {
        super(message);
    }
}
