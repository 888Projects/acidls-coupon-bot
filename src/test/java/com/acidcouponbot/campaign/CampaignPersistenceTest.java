package com.acidcouponbot.campaign;

import com.acidcouponbot.model.CampaignRecipient;
import com.acidcouponbot.model.CampaignRun;
import com.acidcouponbot.model.CampaignStatus;
import com.acidcouponbot.model.RecipientStatus;
import com.acidcouponbot.repository.CampaignRecipientRepository;
import com.acidcouponbot.repository.CampaignRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence-layer proof for the campaign entities: the UNIQUE(campaign_run_id, phone) idempotency spine,
 * and the resume / histogram queries the runner and status endpoint depend on. JPA auto-DDL against H2.
 */
@DataJpaTest
@DisplayName("Campaign persistence")
class CampaignPersistenceTest {

    @Autowired CampaignRunRepository runs;
    @Autowired CampaignRecipientRepository recipients;

    private CampaignRun newRun() {
        return runs.save(CampaignRun.builder().name("test-run").status(CampaignStatus.CREATED).build());
    }

    private CampaignRecipient recipient(Long runId, String phone, RecipientStatus status) {
        return CampaignRecipient.builder()
                .campaignRunId(runId).phone(phone).name("A").surname("B").status(status).build();
    }

    @Test
    @DisplayName("UNIQUE(campaign_run_id, phone) blocks a duplicate recipient in the same run")
    void uniqueConstraint_blocksDuplicateInSameRun() {
        Long runId = newRun().getId();
        recipients.saveAndFlush(recipient(runId, "27831234567", RecipientStatus.PENDING));

        assertThatThrownBy(() ->
                recipients.saveAndFlush(recipient(runId, "27831234567", RecipientStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the SAME phone is allowed under a DIFFERENT run (uniqueness is per-run)")
    void samePhoneAllowedAcrossRuns() {
        Long runA = newRun().getId();
        Long runB = newRun().getId();
        recipients.saveAndFlush(recipient(runA, "27831234567", RecipientStatus.PENDING));

        // Must not throw — different campaign_run_id.
        recipients.saveAndFlush(recipient(runB, "27831234567", RecipientStatus.PENDING));

        assertThat(recipients.countByCampaignRunId(runA)).isEqualTo(1);
        assertThat(recipients.countByCampaignRunId(runB)).isEqualTo(1);
    }

    @Test
    @DisplayName("resume fetch returns only PENDING + *_FAILED, id-ordered, and skips ISSUED")
    void resumeFetch_selectsRetriableSkipsIssued() {
        Long runId = newRun().getId();
        recipients.saveAll(List.of(
                recipient(runId, "27830000001", RecipientStatus.PENDING),
                recipient(runId, "27830000002", RecipientStatus.ISSUED),
                recipient(runId, "27830000003", RecipientStatus.ISSUE_FAILED),
                recipient(runId, "27830000004", RecipientStatus.IMPORT_FAILED)));

        List<CampaignRecipient> retriable = recipients.findByCampaignRunIdAndStatusInOrderByIdAsc(
                runId,
                List.of(RecipientStatus.PENDING, RecipientStatus.ISSUE_FAILED, RecipientStatus.IMPORT_FAILED),
                PageRequest.of(0, 100));

        assertThat(retriable).extracting(CampaignRecipient::getPhone)
                .containsExactly("27830000001", "27830000003", "27830000004");
    }

    @Test
    @DisplayName("status histogram groups counts by recipient status")
    void histogram_groupsByStatus() {
        Long runId = newRun().getId();
        recipients.saveAll(List.of(
                recipient(runId, "27830000001", RecipientStatus.PENDING),
                recipient(runId, "27830000002", RecipientStatus.PENDING),
                recipient(runId, "27830000003", RecipientStatus.ISSUED)));

        long pending = recipients.countByCampaignRunIdAndStatus(runId, RecipientStatus.PENDING);
        long issued = recipients.countByCampaignRunIdAndStatus(runId, RecipientStatus.ISSUED);

        assertThat(pending).isEqualTo(2);
        assertThat(issued).isEqualTo(1);
        assertThat(recipients.countGroupedByStatus(runId)).hasSize(2);   // PENDING + ISSUED buckets
    }
}
