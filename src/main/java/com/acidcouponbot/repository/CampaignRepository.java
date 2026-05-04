package com.acidcouponbot.repository;

import com.acidcouponbot.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    Optional<Campaign> findByActiveTrue();

    boolean existsByActiveTrue();
}
