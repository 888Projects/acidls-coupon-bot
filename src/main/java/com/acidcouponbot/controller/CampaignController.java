package com.acidcouponbot.controller;

import com.acidcouponbot.dto.CampaignStatusDto;
import com.acidcouponbot.dto.CsvIntakeResult;
import com.acidcouponbot.dto.DryRunReport;
import com.acidcouponbot.service.CampaignRunner;
import com.acidcouponbot.service.CampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Admin API for the bulk coupon campaign, under the existing authenticated {@code /api} mapping (Basic auth /
 * form session; CSRF already ignored for {@code /api/**}).
 *
 * <p>Upload is always available (load + inspect before anything runs). Start/resume are gated by
 * {@code campaign.enabled} inside the runner. Dry-run and status call nothing on RandGo.
 */
@RestController
@RequestMapping("/api/campaign")
@RequiredArgsConstructor
@Slf4j
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignRunner campaignRunner;

    // ── Intake (never calls RandGo; not gated by campaign.enabled) ────────────────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("name") String name,
                                    @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required and must be non-empty"));
        }
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        log.info("CAMPAIGN_CSV_UPLOAD name='{}' file='{}' size={}",
                name, file.getOriginalFilename(), file.getSize());
        try {
            CsvIntakeResult result = campaignService.createRunFromCsv(name, file.getInputStream());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException bad) {
            return ResponseEntity.badRequest().body(Map.of("error", bad.getMessage()));
        } catch (IOException io) {
            log.error("CAMPAIGN_CSV_READ_FAILED name='{}': {}", name, io.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "could not read CSV: " + io.getMessage()));
        }
    }

    // ── Control ───────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable Long id) {
        return dispatch(id, () -> {
            campaignRunner.start(id);
            return ResponseEntity.accepted().body(Map.of("message", "campaign run " + id + " started"));
        });
    }

    /** Resume is start() on a PAUSED run — picks up PENDING/failed, skips ISSUED. */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        return dispatch(id, () -> {
            campaignRunner.start(id);
            return ResponseEntity.accepted().body(Map.of("message", "campaign run " + id + " resumed"));
        });
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable Long id) {
        campaignRunner.requestPause();
        return ResponseEntity.ok(Map.of("message", "pause requested — run will pause after the current recipient"));
    }

    // ── Observability ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable Long id) {
        return dispatch(id, () -> {
            CampaignStatusDto s = campaignRunner.status(id);
            return ResponseEntity.ok(s);
        });
    }

    @PostMapping("/{id}/dry-run")
    public ResponseEntity<?> dryRun(@PathVariable Long id) {
        return dispatch(id, () -> {
            DryRunReport report = campaignRunner.dryRun(id);
            return ResponseEntity.ok(report);
        });
    }

    @GetMapping(value = "/{id}/export", produces = "text/csv")
    public ResponseEntity<?> export(@PathVariable Long id) {
        try {
            String csv = campaignService.exportCsv(id);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"campaign-" + id + "-recipients.csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(csv);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound.getMessage());
        }
    }

    // ── Shared error mapping ────────────────────────────────────────────────────────

    /** Maps not-found → 404, and state/gate conflicts (disabled, wrong status, already running, bad token)
     *  → 409 CONFLICT, so the admin gets a clear signal instead of a 500. */
    private ResponseEntity<?> dispatch(Long id, java.util.function.Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", notFound.getMessage()));
        } catch (IllegalStateException conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", conflict.getMessage()));
        }
    }
}
