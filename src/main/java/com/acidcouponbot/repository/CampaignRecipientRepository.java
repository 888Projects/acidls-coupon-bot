package com.acidcouponbot.repository;

import com.acidcouponbot.model.CampaignRecipient;
import com.acidcouponbot.model.RecipientStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    long countByCampaignRunId(Long campaignRunId);

    long countByCampaignRunIdAndStatus(Long campaignRunId, RecipientStatus status);

    boolean existsByCampaignRunIdAndPhone(Long campaignRunId, String phone);

    List<CampaignRecipient> findByCampaignRunIdAndStatus(Long campaignRunId, RecipientStatus status);

    /** Resume/import fetch — a bounded page of recipients in the given statuses (e.g. PENDING + *_FAILED),
     *  ordered by id so processing is deterministic across restarts. */
    List<CampaignRecipient> findByCampaignRunIdAndStatusInOrderByIdAsc(
            Long campaignRunId, Collection<RecipientStatus> statuses, Pageable pageable);

    List<CampaignRecipient> findByCampaignRunIdOrderByIdAsc(Long campaignRunId);

    /** Status histogram for the status endpoint: rows of [RecipientStatus, count]. */
    @Query("select r.status, count(r) from CampaignRecipient r where r.campaignRunId = :runId group by r.status")
    List<Object[]> countGroupedByStatus(@Param("runId") Long runId);
}
