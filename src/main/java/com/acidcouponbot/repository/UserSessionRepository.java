package com.acidcouponbot.repository;

import com.acidcouponbot.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    @Modifying @Transactional
    @Query("DELETE FROM UserSession s WHERE s.lastUpdated < :cutoff")
    void deleteExpiredSessions(LocalDateTime cutoff);
}