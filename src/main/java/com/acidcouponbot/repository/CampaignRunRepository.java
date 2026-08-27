package com.acidcouponbot.repository;

import com.acidcouponbot.model.CampaignRun;
import com.acidcouponbot.model.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignRunRepository extends JpaRepository<CampaignRun, Long> {

    List<CampaignRun> findByStatus(CampaignStatus status);

    long countByStatus(CampaignStatus status);

    /** Guard for "one campaign at a time" — is any run currently RUNNING? */
    boolean existsByStatus(CampaignStatus status);
}
