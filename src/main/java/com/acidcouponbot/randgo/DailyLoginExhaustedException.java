package com.acidcouponbot.randgo;

/**
 * Thrown by {@link RandgoSessionManager#forceRelogin()} on a LIVE (non-campaign) thread when a 401 occurs
 * but a RandGo Login already happened within the last 24h. Re-authenticating would be a SECOND Login in the
 * day — which suspends the production account for ~2h and breaks coupons for everyone. So the session
 * manager refuses and throws this; the caller degrades to a FAILED ensure (the gateway is READY-only, so it
 * mints no link and asks the user to retry). Coupons recover on their own once the 24h Login window clears.
 *
 * <p>Distinct from {@link CampaignLoginSuppressedException} (which fires on the bulk-campaign thread): this
 * one guards the everyday SSO/ensureMember path.
 */
public class DailyLoginExhaustedException extends RuntimeException {
    public DailyLoginExhaustedException(String message) {
        super(message);
    }
}
