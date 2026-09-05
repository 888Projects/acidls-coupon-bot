package com.acidcouponbot.repository;

import com.acidcouponbot.model.RandgoSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RandgoSessionRepository extends JpaRepository<RandgoSession, Long> {
    Optional<RandgoSession> findTopByActiveTrueOrderByCreatedAtDesc();

    /**
     * The most recent session regardless of active state — used by the daily-Login guard in
     * {@link com.acidcouponbot.randgo.RandgoSessionManager#forceRelogin()}: forceRelogin deactivates the
     * old row before logging in, so an active-only lookup can't see a just-spent Login. Ordered by
     * createdAt so we can tell whether a Login happened within the last 24h.
     */
    Optional<RandgoSession> findTopByOrderByCreatedAtDesc();
}
