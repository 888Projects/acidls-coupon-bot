package com.acidcouponbot.campaign;

import com.acidcouponbot.dto.CsvIntakeResult;
import com.acidcouponbot.model.CampaignRun;
import com.acidcouponbot.model.CampaignStatus;
import com.acidcouponbot.model.RecipientStatus;
import com.acidcouponbot.repository.CampaignRecipientRepository;
import com.acidcouponbot.repository.CampaignRunRepository;
import com.acidcouponbot.service.CampaignService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CSV intake logic: header + positional parsing, ^27[0-9]{9}$ validation with rejection reporting,
 * within-file dedup, and that intake persists PENDING recipients and makes NO RandGo calls (the service
 * has no RandGo collaborators at all — enforced structurally).
 */
@DataJpaTest
@Import(CampaignService.class)
@DisplayName("Campaign CSV intake")
class CampaignIntakeTest {

    @Autowired CampaignService service;
    @Autowired CampaignRunRepository runs;
    @Autowired CampaignRecipientRepository recipients;

    private InputStream csv(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("valid CSV with header → run created, recipients PENDING, counts correct")
    void validHeaderCsv() throws IOException {
        String body = """
                Cellphone,Name,Surname
                27831234567,Thabo,Mokoena
                27829876543,Lerato,Nkosi
                """;

        CsvIntakeResult r = service.createRunFromCsv("aug-campaign", csv(body));

        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).isZero();
        assertThat(r.duplicatesInFile()).isZero();
        assertThat(r.totalDataLines()).isEqualTo(2);

        CampaignRun run = runs.findById(r.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(CampaignStatus.CREATED);
        assertThat(run.getTotalRecipients()).isEqualTo(2);
        assertThat(recipients.countByCampaignRunIdAndStatus(r.runId(), RecipientStatus.PENDING)).isEqualTo(2);
    }

    @Test
    @DisplayName("rejects non-27 numbers and reports them; keeps the valid ones")
    void rejectsInvalidNumbers() throws IOException {
        String body = """
                Cellphone,Name,Surname
                27831234567,Ok,One
                0831234567,Bad,LocalForm
                27831,Bad,TooShort
                +27831234567,Bad,PlusPrefix
                27831234568,Ok,Two
                """;

        CsvIntakeResult r = service.createRunFromCsv("mixed", csv(body));

        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).isEqualTo(3);
        assertThat(r.rejectedSamples()).anyMatch(s -> s.contains("0831234567"))
                .anyMatch(s -> s.contains("+27831234567"))
                .anyMatch(s -> s.contains("27831"));
        assertThat(recipients.countByCampaignRunId(r.runId())).isEqualTo(2);
    }

    @Test
    @DisplayName("de-duplicates within the file (first occurrence wins)")
    void dedupWithinFile() throws IOException {
        String body = """
                Cellphone,Name,Surname
                27831234567,First,Win
                27831234567,Second,Drop
                27839999999,Other,Person
                """;

        CsvIntakeResult r = service.createRunFromCsv("dupes", csv(body));

        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.duplicatesInFile()).isEqualTo(1);
        assertThat(recipients.findByCampaignRunIdAndStatus(r.runId(), RecipientStatus.PENDING))
                .filteredOn(x -> x.getPhone().equals("27831234567"))
                .singleElement()
                .satisfies(x -> assertThat(x.getName()).isEqualTo("First"));
    }

    @Test
    @DisplayName("headerless CSV is read positionally [phone, name, surname]")
    void headerlessPositional() throws IOException {
        String body = "27831234567,Thabo,Mokoena\n27829876543,Lerato,Nkosi\n";

        CsvIntakeResult r = service.createRunFromCsv("no-header", csv(body));

        assertThat(r.accepted()).isEqualTo(2);
        assertThat(recipients.findByCampaignRunIdAndStatus(r.runId(), RecipientStatus.PENDING))
                .anyMatch(x -> "Thabo".equals(x.getName()) && "Mokoena".equals(x.getSurname()));
    }

    @Test
    @DisplayName("extra columns ignored; quoted fields tolerated")
    void extraColumnsAndQuotes() throws IOException {
        String body = """
                Cellphone,Name,Surname,Extra
                27831234567,"Thabo","Mokoena","ignored,with,commas"
                """;

        CsvIntakeResult r = service.createRunFromCsv("extras", csv(body));

        assertThat(r.accepted()).isEqualTo(1);
        assertThat(recipients.findByCampaignRunIdAndStatus(r.runId(), RecipientStatus.PENDING))
                .singleElement()
                .satisfies(x -> {
                    assertThat(x.getName()).isEqualTo("Thabo");
                    assertThat(x.getSurname()).isEqualTo("Mokoena");
                });
    }

    @Test
    @DisplayName("header present but no phone column → rejected with a clear error")
    void headerWithoutPhoneColumn() {
        String body = "Foo,Name,Surname\nbar,Thabo,Mokoena\n";

        assertThatThrownBy(() -> service.createRunFromCsv("bad-header", csv(body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cellphone");
    }

    @Test
    @DisplayName("exceeding max-recipients-per-run → rejected, NO run created")
    void exceedsMaxRecipients_rejectedNoRunCreated() {
        ReflectionTestUtils.setField(service, "maxRecipientsPerRun", 2);
        long before = runs.count();
        String body = """
                Cellphone,Name,Surname
                27830000001,A,A
                27830000002,B,B
                27830000003,C,C
                """;

        assertThatThrownBy(() -> service.createRunFromCsv("too-big", csv(body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-recipients-per-run");
        assertThat(runs.count()).isEqualTo(before);   // guard fired before any run was created
    }

    @Test
    @DisplayName("export CSV emits a header + one row per recipient with status")
    void exportCsv_roundTrips() throws IOException {
        CsvIntakeResult r = service.createRunFromCsv("exp", csv("""
                Cellphone,Name,Surname
                27831234567,Thabo,Mokoena
                """));

        String out = service.exportCsv(r.runId());

        assertThat(out).startsWith("phone,name,surname,status,attempts,last_attempt_at,issued_codes,last_error");
        assertThat(out).contains("27831234567,Thabo,Mokoena,PENDING");
    }
}
