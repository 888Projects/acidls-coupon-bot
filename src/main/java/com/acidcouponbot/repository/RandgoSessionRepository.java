package com.acidcouponbot.repository;

import com.acidcouponbot.model.RandgoSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RandgoSessionRepository extends JpaRepository<RandgoSession, Long> {
    Optional<RandgoSession> findTopByActiveTrueOrderByCreatedAtDesc();
}
