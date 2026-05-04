package com.acidcouponbot.controller;

import com.acidcouponbot.model.*;
import com.acidcouponbot.randgo.RandgoSessionManager;
import com.acidcouponbot.randgo.RandgoSyncService;
import com.acidcouponbot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Admin REST API — used by the dashboard UI and Postman.
 * All endpoints require authentication (Basic Auth or form session).
 * Exception: /api/health is public for uptime monitoring.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final CampaignRepository campaignRepository;
    private final RandgoVoucherRepository randgoVoucherRepository;
    private final RedemptionRepository redemptionRepository;
    private final CachedCouponCodeRepository cachedCouponCodeRepository;
    private final RandgoSyncService randgoSyncService;
    private final RandgoSessionManager randgoSessionManager;

    // ─── Health (public) ──────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "UP");
        h.put("app", "AcidCouponBot");
        h.put("version", "2.1.0");
        h.put("timestamp", LocalDateTime.now().toString());
        h.put("profile", System.getProperty("spring.profiles.active", "dev"));
        return ResponseEntity.ok(h);
    }

    // ─── Dashboard Stats ──────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Optional<Campaign> campaignOpt = campaignRepository.findByActiveTrue();
        Map<String, Object> stats = new LinkedHashMap<>();

        if (campaignOpt.isEmpty()) {
            stats.put("activeCampaign", false);
            stats.put("message", "No active campaign. Create one in the Campaigns tab.");
            return ResponseEntity.ok(stats);
        }

        Campaign campaign = campaignOpt.get();
        List<Object[]> stockCounts = cachedCouponCodeRepository.countAvailableByVoucher();
        Map<String, Long> stockByVoucher = new LinkedHashMap<>();
        long totalFallbackCodes = 0;
        for (Object[] row : stockCounts) {
            stockByVoucher.put((String) row[0], ((Number) row[1]).longValue());
            totalFallbackCodes += ((Number) row[1]).longValue();
        }

        long totalRedemptions = redemptionRepository.countByCampaignId(campaign.getId());
        long tillRedeemed = redemptionRepository.countByRedeemedAtTillTrue();
        long failedSends = redemptionRepository
                .findByMessageSentSuccessfullyFalseAndSendAttemptsLessThan(3).size();
        long activeVouchers = randgoVoucherRepository.countByActiveTrue();

        stats.put("activeCampaign", true);
        stats.put("campaignId", campaign.getId());
        stats.put("campaignName", campaign.getName());
        stats.put("retailerName", campaign.getRetailerName());
        stats.put("retailerEmoji", campaign.getRetailerEmoji());
        stats.put("bundleSize", campaign.getBundleSize());
        stats.put("totalRedemptions", totalRedemptions);
        stats.put("tillRedeemed", tillRedeemed);
        stats.put("failedSends", failedSends);
        stats.put("activeVouchers", activeVouchers);
        stats.put("totalFallbackCodes", totalFallbackCodes);
        stats.put("stockByVoucher", stockByVoucher);
        stats.put("campaignExpired", campaign.isExpired());
        stats.put("expiryDate", campaign.getExpiryDate() != null
                ? campaign.getExpiryDate().toLocalDate().toString() : null);
        return ResponseEntity.ok(stats);
    }

    // ─── Campaigns ────────────────────────────────────────────────────────────

    @GetMapping("/campaigns")
    public ResponseEntity<List<Campaign>> getCampaigns() {
        return ResponseEntity.ok(campaignRepository.findAll());
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<Campaign> getCampaign(@PathVariable Long id) {
        return campaignRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/campaigns")
    public ResponseEntity<Campaign> createCampaign(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("name") || body.get("name") == null) {
            return ResponseEntity.badRequest().build();
        }

        boolean isActive = Boolean.parseBoolean(
                body.getOrDefault("active", "false").toString());

        // Deactivate all existing campaigns if setting this one active
        if (isActive) {
            campaignRepository.findAll().forEach(c -> {
                c.setActive(false);
                campaignRepository.save(c);
            });
        }

        LocalDateTime expiryDate = null;
        if (body.containsKey("expiryDate") && body.get("expiryDate") != null
                && !body.get("expiryDate").toString().isBlank()) {
            try {
                String raw = body.get("expiryDate").toString();
                // Handle both "2025-12-31" and "2025-12-31T23:59:59"
                expiryDate = raw.length() == 10
                        ? LocalDateTime.parse(raw + "T23:59:59")
                        : LocalDateTime.parse(raw);
            } catch (Exception e) {
                log.warn("Could not parse expiryDate: {}", body.get("expiryDate"));
            }
        }

        Campaign campaign = Campaign.builder()
                .name(body.get("name").toString().trim())
                .description(body.getOrDefault("description", "").toString())
                .retailerName(body.getOrDefault("retailerName", "").toString())
                .retailerEmoji(body.getOrDefault("retailerEmoji", "🛒").toString())
                .bundleSize(Integer.parseInt(
                        body.getOrDefault("bundleSize", "5").toString()))
                .active(isActive)
                .expiryDate(expiryDate)
                .lowStockThreshold(Integer.parseInt(
                        body.getOrDefault("lowStockThreshold", "10").toString()))
                .build();

        Campaign saved = campaignRepository.save(campaign);
        log.info("Campaign created: id={} name={} active={}", saved.getId(), saved.getName(), saved.isActive());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/campaigns/{id}")
    public ResponseEntity<Campaign> updateCampaign(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {

        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + id));

        if (body.containsKey("name")) campaign.setName(body.get("name").toString());
        if (body.containsKey("description")) campaign.setDescription(body.get("description").toString());
        if (body.containsKey("retailerName")) campaign.setRetailerName(body.get("retailerName").toString());
        if (body.containsKey("retailerEmoji")) campaign.setRetailerEmoji(body.get("retailerEmoji").toString());
        if (body.containsKey("bundleSize"))
            campaign.setBundleSize(Integer.parseInt(body.get("bundleSize").toString()));
        if (body.containsKey("lowStockThreshold"))
            campaign.setLowStockThreshold(Integer.parseInt(body.get("lowStockThreshold").toString()));

        return ResponseEntity.ok(campaignRepository.save(campaign));
    }

    @PutMapping("/campaigns/{id}/activate")
    public ResponseEntity<Campaign> activateCampaign(@PathVariable Long id) {
        campaignRepository.findAll().forEach(c -> {
            c.setActive(false);
            campaignRepository.save(c);
        });
        Campaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + id));
        c.setActive(true);
        Campaign saved = campaignRepository.save(c);
        log.info("Campaign activated: id={} name={}", saved.getId(), saved.getName());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/campaigns/{id}/deactivate")
    public ResponseEntity<Campaign> deactivateCampaign(@PathVariable Long id) {
        Campaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + id));
        c.setActive(false);
        return ResponseEntity.ok(campaignRepository.save(c));
    }

    @DeleteMapping("/campaigns/{id}")
    public ResponseEntity<Void> deleteCampaign(@PathVariable Long id) {
        if (!campaignRepository.existsById(id)) return ResponseEntity.notFound().build();
        campaignRepository.deleteById(id);
        log.info("Campaign deleted: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ─── Vouchers ─────────────────────────────────────────────────────────────

    @GetMapping("/vouchers")
    public ResponseEntity<List<RandgoVoucher>> getVouchers() {
        return ResponseEntity.ok(randgoVoucherRepository.findAll());
    }

    @GetMapping("/vouchers/active")
    public ResponseEntity<List<RandgoVoucher>> getActiveVouchers() {
        return ResponseEntity.ok(
            randgoVoucherRepository
                .findByActiveTrueAndIncludeInBundleTrueOrderByDisplayOrderAsc());
    }

    @PutMapping("/vouchers/{id}/toggle")
    public ResponseEntity<RandgoVoucher> toggleVoucher(@PathVariable Long id) {
        RandgoVoucher v = randgoVoucherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Voucher not found: " + id));
        v.setIncludeInBundle(!v.isIncludeInBundle());
        return ResponseEntity.ok(randgoVoucherRepository.save(v));
    }

    @PutMapping("/vouchers/{id}/order")
    public ResponseEntity<RandgoVoucher> updateVoucherOrder(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        RandgoVoucher v = randgoVoucherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Voucher not found: " + id));
        if (body.containsKey("displayOrder"))
            v.setDisplayOrder(Integer.parseInt(body.get("displayOrder").toString()));
        if (body.containsKey("emoji"))
            v.setEmoji(body.get("emoji").toString());
        return ResponseEntity.ok(randgoVoucherRepository.save(v));
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @PostMapping("/sync/vouchers")
    public ResponseEntity<RandgoSyncService.VoucherSyncResult> syncVouchers() {
        log.info("Manual voucher sync triggered");
        return ResponseEntity.ok(randgoSyncService.syncVouchers());
    }

    @PostMapping("/sync/codes")
    public ResponseEntity<RandgoSyncService.CodeSyncResult> syncCodes() {
        log.info("Manual code cache sync triggered");
        return ResponseEntity.ok(randgoSyncService.syncCodesCache());
    }

    // ─── Redemptions ──────────────────────────────────────────────────────────

    @GetMapping("/redemptions")
    public ResponseEntity<List<Redemption>> getRedemptions(
            @RequestParam(required = false, defaultValue = "100") int limit) {
        List<Redemption> all = redemptionRepository.findAllByOrderByRedeemedAtDesc();
        return ResponseEntity.ok(all.size() > limit ? all.subList(0, limit) : all);
    }

    @GetMapping("/redemptions/{id}")
    public ResponseEntity<Redemption> getRedemption(@PathVariable Long id) {
        return redemptionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/redemptions/failed")
    public ResponseEntity<List<Redemption>> getFailedRedemptions() {
        return ResponseEntity.ok(
                redemptionRepository
                        .findByMessageSentSuccessfullyFalseAndSendAttemptsLessThan(3));
    }

    @GetMapping("/redemptions/phone/{phone}")
    public ResponseEntity<List<Redemption>> getRedemptionsByPhone(
            @PathVariable String phone) {
        List<Redemption> results = redemptionRepository.findAllByOrderByRedeemedAtDesc()
                .stream()
                .filter(r -> r.getPhoneNumber().equals(phone))
                .toList();
        return ResponseEntity.ok(results);
    }

    // ─── Randgo Status ────────────────────────────────────────────────────────

    @GetMapping("/randgo/status")
    public ResponseEntity<Map<String, Object>> getRandgoStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            String token = randgoSessionManager.getSessionToken();
            boolean isMock = token != null && token.startsWith("MOCK");
            status.put("connected", token != null);
            status.put("mockMode", isMock);
            status.put("sessionActive", true);
            status.put("environment", isMock ? "mock" : "qa");
            status.put("apiUrl", System.getProperty("randgo.api.url", "https://api.randgoqa.dev"));
        } catch (Exception e) {
            status.put("connected", false);
            status.put("sessionActive", false);
            status.put("error", e.getMessage());
        }
        return ResponseEntity.ok(status);
    }

    // ─── CSRF token (for dashboard JS) ───────────────────────────────────────

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> getCsrf(
            jakarta.servlet.http.HttpServletRequest request) {
        var csrf = (org.springframework.security.web.csrf.CsrfToken)
                request.getAttribute(
                        org.springframework.security.web.csrf.CsrfToken.class.getName());
        return ResponseEntity.ok(Map.of("token", csrf != null ? csrf.getToken() : ""));
    }
}
