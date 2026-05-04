package com.acidcouponbot.repository;

import com.acidcouponbot.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedemptionRepository extends JpaRepository<Redemption, Long> {
    boolean existsByPhoneNumberAndCampaignId(String phoneNumber, Long campaignId);
    boolean existsByPhoneNumberAndMemberRegisteredInRandgoTrue(String phoneNumber);
    Optional<Redemption> findByPhoneNumberAndCampaignId(String phoneNumber, Long campaignId);
    List<Redemption> findByMessageSentSuccessfullyFalseAndSendAttemptsLessThan(int maxAttempts);
    List<Redemption> findAllByOrderByRedeemedAtDesc();
    List<Redemption> findByPhoneNumberOrderByRedeemedAtDesc(String phoneNumber);
    List<Redemption> findByPhoneNumberAndRedeemedAtTillTrueOrderByRedeemedAtDesc(String phoneNumber);
    long countByCampaignId(Long campaignId);
    long countByRedeemedAtTillTrue();

    @Query("SELECT r FROM Redemption r WHERE r.memberRegisteredInRandgo = true AND r.redeemedAtTill = false ORDER BY r.redeemedAt DESC")
    List<Redemption> findUnconfirmedTillRedemptions();
}