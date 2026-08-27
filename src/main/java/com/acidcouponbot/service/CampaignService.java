package com.acidcouponbot.service;

import com.acidcouponbot.dto.CsvIntakeResult;
import com.acidcouponbot.model.CampaignRecipient;
import com.acidcouponbot.model.CampaignRun;
import com.acidcouponbot.model.CampaignStatus;
import com.acidcouponbot.model.RecipientStatus;
import com.acidcouponbot.repository.CampaignRecipientRepository;
import com.acidcouponbot.repository.CampaignRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Campaign intake — parse a CSV of recipients and materialise a {@link CampaignRun} plus one PENDING
 * {@link CampaignRecipient} per valid, de-duplicated line.
 *
 * <p><b>This class makes NO RandGo calls.</b> It is pure persistence: load, validate, dedup, save. Execution
 * against RandGo lives in the (separate) runner so the import path can never accidentally spend an API call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    private final CampaignRunRepository runRepo;
    private final CampaignRecipientRepository recipientRepo;

    /** Non-SA numbers are excluded before the file arrives, but the job still rejects anything off-shape. */
    private static final Pattern SA_PHONE = Pattern.compile("^27[0-9]{9}$");

    /** Cap on how many rejected values we echo back (avoid a huge response on a badly-formed file). */
    private static final int MAX_REJECTED_SAMPLES = 50;

    /** Cheap protection against a wrong file — reject an upload whose valid recipient count exceeds this.
     *  Applies at INTAKE only (per the "gate the runner, not the upload" decision, this is a size sanity
     *  check, not the run gate). */
    @Value("${campaign.max-recipients-per-run:10000}")
    private int maxRecipientsPerRun;

    /**
     * Parse {@code csv} and create a campaign run with its PENDING recipients. Header row is detected by
     * name (Cellphone / Name / Surname, case-insensitive); if no recognisable header is present the columns
     * are read positionally as [phone, name, surname]. Rejects phones failing {@link #SA_PHONE} and drops
     * within-file duplicates (first occurrence wins). Never calls RandGo.
     */
    @Transactional
    public CsvIntakeResult createRunFromCsv(String name, InputStream csv) throws IOException {
        List<String[]> rows = parse(csv);

        int phoneIdx = 0, nameIdx = 1, surnameIdx = 2;
        int start = 0;
        if (!rows.isEmpty() && isHeader(rows.get(0))) {
            String[] header = rows.get(0);
            phoneIdx   = indexOf(header, "cellphone", "phone", "msisdn", "cell");
            nameIdx    = indexOf(header, "name", "firstname", "first name");
            surnameIdx = indexOf(header, "surname", "lastname", "last name");
            start = 1;
            if (phoneIdx < 0) {
                throw new IllegalArgumentException(
                        "CSV header present but no Cellphone/phone column found");
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String[]> valid = new ArrayList<>();          // [phone, name, surname], validated + de-duped
        List<String> rejectedSamples = new ArrayList<>();
        int dataLines = 0, duplicates = 0, rejected = 0;

        for (int i = start; i < rows.size(); i++) {
            String[] row = rows.get(i);
            dataLines++;

            String phone = cell(row, phoneIdx);
            if (phone == null || !SA_PHONE.matcher(phone).matches()) {
                rejected++;
                if (rejectedSamples.size() < MAX_REJECTED_SAMPLES) {
                    rejectedSamples.add("line " + (i + 1) + ": '" + (phone == null ? "" : phone) + "'");
                }
                continue;
            }
            if (!seen.add(phone)) {
                duplicates++;
                continue;
            }
            valid.add(new String[]{phone, cell(row, nameIdx), cell(row, surnameIdx)});
        }

        // Size guard BEFORE creating anything — a wrong (e.g. entire-DB) file is rejected, no run created.
        if (valid.size() > maxRecipientsPerRun) {
            throw new IllegalArgumentException("file has " + valid.size()
                    + " valid recipients, exceeding campaign.max-recipients-per-run=" + maxRecipientsPerRun
                    + " — refusing (guard against a wrong file). No run created.");
        }

        CampaignRun run = runRepo.save(CampaignRun.builder()
                .name(name)
                .status(CampaignStatus.CREATED)
                .build());

        List<CampaignRecipient> toSave = valid.stream()
                .map(v -> CampaignRecipient.builder()
                        .campaignRunId(run.getId())
                        .phone(v[0])
                        .name(v[1])
                        .surname(v[2])
                        .status(RecipientStatus.PENDING)
                        .build())
                .toList();

        recipientRepo.saveAll(toSave);
        run.setTotalRecipients(toSave.size());
        runRepo.save(run);

        log.info("CAMPAIGN_CSV_INTAKE run={} name='{}' dataLines={} accepted={} dupInFile={} rejected={}",
                run.getId(), name, dataLines, toSave.size(), duplicates, rejected);
        if (rejected > 0) {
            log.warn("CAMPAIGN_CSV_REJECTED run={} rejected={} sample={}", run.getId(), rejected, rejectedSamples);
        }

        return new CsvIntakeResult(run.getId(), name, dataLines, toSave.size(), duplicates, rejected, rejectedSamples);
    }

    /**
     * Export a run's recipients (and their outcomes) as CSV for the admin — phone, name, surname, status,
     * attempts, last attempt, issued codes, last error. Read-only.
     */
    @Transactional(readOnly = true)
    public String exportCsv(Long runId) {
        if (!runRepo.existsById(runId)) {
            throw new IllegalArgumentException("no campaign run " + runId);
        }
        List<CampaignRecipient> rs = recipientRepo.findByCampaignRunIdOrderByIdAsc(runId);
        StringBuilder sb = new StringBuilder("phone,name,surname,status,attempts,last_attempt_at,issued_codes,last_error\n");
        for (CampaignRecipient r : rs) {
            sb.append(q(r.getPhone())).append(',')
              .append(q(r.getName())).append(',')
              .append(q(r.getSurname())).append(',')
              .append(q(r.getStatus() == null ? null : r.getStatus().name())).append(',')
              .append(r.getAttempts()).append(',')
              .append(q(r.getLastAttemptAt() == null ? null : r.getLastAttemptAt().toString())).append(',')
              .append(q(r.getIssuedCodes())).append(',')
              .append(q(r.getLastError())).append('\n');
        }
        return sb.toString();
    }

    /** CSV-quote a field (wrap + double any embedded quotes) when it contains a comma, quote, or newline. */
    private static String q(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ── CSV parsing (minimal, dependency-free; tolerates optional double-quoted fields) ─────────────

    private List<String[]> parse(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                rows.add(splitLine(line));
            }
        }
        return rows;
    }

    private String[] splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    /** A row is a header if it carries ANY recognised column label (phone OR name OR surname). This lets us
     *  reject a header that has name/surname but no phone column (rather than silently mis-parsing it as data),
     *  while a label-free first row is treated as positional data. */
    private boolean isHeader(String[] first) {
        for (String c : first) {
            String v = c.trim().toLowerCase();
            switch (v) {
                case "cellphone", "phone", "msisdn", "cell",
                     "name", "firstname", "first name",
                     "surname", "lastname", "last name" -> { return true; }
                default -> { /* keep scanning */ }
            }
        }
        return false;
    }

    private int indexOf(String[] header, String... names) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim().toLowerCase();
            for (String n : names) {
                if (h.equals(n)) return i;
            }
        }
        return -1;
    }

    private String cell(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return null;
        String v = row[idx].trim();
        return v.isEmpty() ? null : v;
    }
}
