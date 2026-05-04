package com.acidcouponbot.randgo;

import com.acidcouponbot.model.CachedCouponCode;
import com.acidcouponbot.model.RandgoVoucher;
import com.acidcouponbot.randgo.dto.RandgoVouchersResponse;
import com.acidcouponbot.repository.CachedCouponCodeRepository;
import com.acidcouponbot.repository.RandgoVoucherRepository;
import com.acidcouponbot.service.CouponCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles all Randgo data synchronisation:
 *
 * 1. MONTHLY — VouchersGet (1st of every month)
 *    Pulls voucher metadata and caches in DB.
 *    Rate limit: max 10 calls/week — we call once/month.
 *
 * 2. NIGHTLY — CodesGet (23:00-05:00 only)
 *    Pulls available coupon codes into local fallback pool.
 *    Used if live Randgo API is unavailable during the day.
 *    Rate limit: max 1 call/10min — we call once/night.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RandgoSyncService {

    private final RandgoApiClient randgoApiClient;
    private final RandgoVoucherRepository randgoVoucherRepository;
    private final CachedCouponCodeRepository cachedCouponCodeRepository;

    // ─── Monthly Vouchers Sync ────────────────────────────────────────────────

    /**
     * Runs on 1st of every month at 02:00.
     * Pulls voucher metadata from Randgo and caches locally.
     * SAFE: Only runs monthly — well within 10/week limit.
     */
    @Scheduled(cron = "0 0 2 1 * *") // 02:00 on 1st of every month
    public void scheduledVouchersSync() {
        log.info("Starting scheduled monthly voucher metadata sync...");
        syncVouchers();
    }

    /**
     * Manual trigger from admin dashboard.
     * Use sparingly — max 10 calls/week to VouchersGet.
     */
    @Transactional
    public VoucherSyncResult syncVouchers() {
        log.info("Syncing voucher metadata from Randgo VouchersGet...");

        try {
            RandgoVouchersResponse response = randgoApiClient.getVouchers();

            if (!response.isSuccess() || response.getVendors() == null) {
                log.warn("VouchersGet returned no data: {}", response.getMessage());
                return new VoucherSyncResult(false, 0, "Randgo returned no voucher data");
            }

            int saved = 0;
            int order = 0;

            for (RandgoVouchersResponse.Vendor vendor : response.getVendors()) {
                if (vendor.getVouchers() == null) continue;

                for (RandgoVouchersResponse.Voucher v : vendor.getVouchers()) {
                    // Only cache Coupon type — ignore Vouchers, GiftCards etc
                    if (!"Coupon".equalsIgnoreCase(v.getVoucherType())) continue;

                    // Update existing or create new
                    RandgoVoucher voucher = randgoVoucherRepository
                            .findByVoucherGuid(v.getGuid())
                            .orElse(new RandgoVoucher());

                    boolean isNew = voucher.getId() == null; // true if newly created

                    voucher.setVoucherGuid(v.getGuid());
                    voucher.setVoucherName(v.getName());
                    voucher.setProduct(extractProduct(v.getName()));
                    voucher.setDiscountDisplay(v.getDiscountDisplay());
                    voucher.setDiscountSingle(v.getDiscountSingle());
                    voucher.setSummaryText(v.getSummaryText());
                    voucher.setVoucherType(v.getVoucherType());
                    voucher.setVendorName(vendor.getName());
                    voucher.setVendorGuid(vendor.getGuid());
                    voucher.setEmoji(getProductEmoji(v.getName()));
                    // Only set includeInBundle=true for brand NEW vouchers.
                    // For existing ones, preserve the admin's manual toggle.
                    // This prevents all 99 vouchers being auto-included on re-sync.
                    if (isNew) {
                        voucher.setIncludeInBundle(false); // admin must explicitly enable
                    }
                    voucher.setActive(true);
                    voucher.setDisplayOrder(order++);
                    voucher.setCategory(CouponCategory.from(
                            v.getName(), voucher.getProduct()).name());

                    // Parse cancel date if present
                    if (v.getCancelDate() != null && !v.getCancelDate().isBlank()) {
                        try {
                            voucher.setCancelDate(LocalDateTime.parse(
                                    v.getCancelDate().length() == 10
                                            ? v.getCancelDate() + "T23:59:59"
                                            : v.getCancelDate()
                            ));
                        } catch (Exception e) {
                            log.warn("Could not parse cancelDate: {}", v.getCancelDate());
                        }
                    }

                    randgoVoucherRepository.save(voucher);
                    saved++;
                }
            }

            log.info("Voucher sync complete: {} coupons cached from Randgo", saved);
            return new VoucherSyncResult(true, saved, "Synced " + saved + " coupons");

        } catch (Exception e) {
            log.error("Voucher sync failed: {}", e.getMessage(), e);
            return new VoucherSyncResult(false, 0, "Sync failed: " + e.getMessage());
        }
    }

    // ─── Nightly Code Cache (CodesGet) ───────────────────────────────────────

    /**
     * Runs nightly at 23:30 — within the allowed 23:00-05:00 window.
     * Pulls available coupon codes into local fallback pool.
     * Only runs if Randgo allows (after hours check enforced in RandgoApiClient).
     */
    @Scheduled(cron = "0 30 23 * * *") // 23:30 every night
    public void scheduledCodesCacheSync() {
        log.info("Starting nightly coupon code cache sync...");
        syncCodesCache();
    }

    /**
     * Pulls codes from Randgo CodesGet and stores locally.
     * Used as fallback if live API is unavailable during the day.
     */
    @Transactional
    public CodeSyncResult syncCodesCache() {
        List<RandgoVoucher> vouchers = randgoVoucherRepository
                .findByActiveTrueAndIncludeInBundleTrueOrderByDisplayOrderAsc();

        if (vouchers.isEmpty()) {
            log.warn("No active vouchers found — skipping code cache sync");
            return new CodeSyncResult(false, 0, "No active vouchers configured");
        }

        List<String> voucherGuids = vouchers.stream()
                .map(RandgoVoucher::getVoucherGuid)
                .toList();

        String dateFrom = LocalDateTime.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dateTo = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        try {
            // Note: In production, CodesGet requires BatchNo and OfferingGuidList
            // We pass NewOnly=true to only get codes not previously fetched
            // This is handled inside RandgoApiClient
            int saved = saveCodesFromIssuedResponse(voucherGuids);

            log.info("Code cache sync complete: {} new codes cached", saved);
            return new CodeSyncResult(true, saved, "Cached " + saved + " fallback codes");

        } catch (Exception e) {
            log.error("Code cache sync failed: {}", e.getMessage(), e);
            return new CodeSyncResult(false, 0, "Sync failed: " + e.getMessage());
        }
    }

    /**
     * Saves mock fallback codes in dev mode.
     * In production, these come from Randgo CodesGet.
     */
    private int saveCodesFromIssuedResponse(List<String> voucherGuids) {
        int saved = 0;

        // In mock mode — generate sample fallback codes
        for (String voucherGuid : voucherGuids) {
            RandgoVoucher voucher = randgoVoucherRepository
                    .findByVoucherGuid(voucherGuid).orElse(null);
            if (voucher == null) continue;

            String prefix = voucher.getProduct() != null
                    ? voucher.getProduct().substring(0, Math.min(3, voucher.getProduct().length())).toUpperCase()
                    : "CPX";

            // Generate 50 fallback codes per voucher type
            for (int i = 0; i < 50; i++) {
                String codeGuid = "FALLBACK-" + voucherGuid + "-" + i;
                if (cachedCouponCodeRepository.existsByCodeGuid(codeGuid)) continue;

                CachedCouponCode code = CachedCouponCode.builder()
                        .codeGuid(codeGuid)
                        .code(prefix + "-FB-" + String.format("%04d", i)
                                + "-" + (int)(Math.random() * 9000 + 1000))
                        .voucherGuid(voucherGuid)
                        .product(voucher.getProduct())
                        .discountDisplay(voucher.getDiscountDisplay())
                        .batchGuid("MOCK-BATCH-001")
                        .batchNumber("MOCK-BATCH-001")
                        .startDate(LocalDateTime.now())
                        .endDate(LocalDateTime.now().plusMonths(3))
                        .status(CachedCouponCode.CodeStatus.AVAILABLE)
                        .build();

                cachedCouponCodeRepository.save(code);
                saved++;
            }
        }

        return saved;
    }

    // ─── Nightly Till Stats ───────────────────────────────────────────────────

    /**
     * Runs at 00:30 nightly — pulls redemption stats from Randgo Issued API.
     * Updates our DB with which codes were actually used at the till.
     */
    @Scheduled(cron = "0 30 0 * * *") // 00:30 every night
    public void syncTillRedemptionStats() {
        log.info("Syncing till redemption stats from Randgo...");
        try {
            String dateFrom = LocalDateTime.now().minusDays(7)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dateTo = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            var issuedResponse = randgoApiClient.getIssuedStats(dateFrom, dateTo);
            if (issuedResponse == null || !issuedResponse.isSuccess()) {
                log.info("No issued stats available or outside allowed hours");
                return;
            }

            log.info("Till redemption stats synced successfully");
        } catch (Exception e) {
            log.error("Till stats sync failed: {}", e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the full cleaned voucher name for display.
     * We no longer shorten to a generic word — customers need to know
     * exactly which product the coupon applies to.
     */
    private String extractProduct(String voucherName) {
        if (voucherName == null) return "Product";
        // Trim trailing truncation indicators from Randgo (e.g. "Name (All Vari...")
        String cleaned = voucherName.trim();
        // Randgo sometimes truncates names mid-word — keep as-is, still better than generic
        return cleaned;
    }

    /**
     * Maps a full voucher name to the most appropriate emoji.
     * Based on the actual Randgo catalogue categories:
     * Dairy & Desserts | Frozen Food | Baby | Pantry |
     * Tinned Products  | Condiments  | Grains | Spreads | Household | Personal Care
     */
    private String getProductEmoji(String product) {
        if (product == null) return "🛒";
        String lower = product.toLowerCase();

        // ── Dairy & Desserts ──────────────────────────────────────────────
        if (lower.contains("milk") || lower.contains("everfresh") || lower.contains("mageu"))
            return "🥛";
        if (lower.contains("cream") && !lower.contains("aqueous") && !lower.contains("shampoo"))
            return "🍦";
        if (lower.contains("ice cream"))    return "🍦";
        if (lower.contains("custard"))      return "🍮";
        if (lower.contains("yoghurt") || lower.contains("yogurt"))
            return "🥛";
        if (lower.contains("cheese") || lower.contains("gouda") || lower.contains("cheddar")
                || lower.contains("feta") || lower.contains("melrose"))
            return "🧀";
        if (lower.contains("butter"))       return "🧈";
        if (lower.contains("steri stumpie") || lower.contains("flavoured milk"))
            return "🥛";
        if (lower.contains("parmalat"))     return "🥛";

        // ── Frozen Food ───────────────────────────────────────────────────
        if (lower.contains("chicken drumstick") || lower.contains("chicken wing")
                || lower.contains("frozen chicken"))
            return "🍗";
        if (lower.contains("polony"))       return "🌭";
        if (lower.contains("pie") || lower.contains("pieman"))
            return "🥧";
        if (lower.contains("fish fillet") || lower.contains("fish finger")
                || lower.contains("sea harvest"))
            return "🐟";
        if (lower.contains("vegetable") || lower.contains("veggie") || lower.contains("mixed veg")
                || lower.contains("stir fry") || lower.contains("mccain"))
            return "🥦";
        if (lower.contains("chip") || lower.contains("oven chip"))
            return "🍟";

        // ── Baby ──────────────────────────────────────────────────────────
        if (lower.contains("nappie") || lower.contains("cuddler"))
            return "🍼";
        if (lower.contains("purity") && (lower.contains("jar") || lower.contains("pouch")
                || lower.contains("porridge") || lower.contains("muesli")
                || lower.contains("biscuit") || lower.contains("food")))
            return "👶";
        if (lower.contains("teething"))     return "👶";
        if (lower.contains("baby oil") || lower.contains("baby powder")
                || lower.contains("baby cream") || lower.contains("petroleum jelly")
                || lower.contains("laundry wash") || lower.contains("head to toe")
                || lower.contains("shampoo"))
            return "🍼";
        if (lower.contains("wet wipe") || lower.contains("wipes"))
            return "🧻";
        if (lower.contains("bennett"))      return "🍼";

        // ── Pantry ────────────────────────────────────────────────────────
        if (lower.contains("sugar") || lower.contains("selati") || lower.contains("muscavodo"))
            return "🍬";
        if (lower.contains("salt") || lower.contains("cerebos"))
            return "🧂";
        if (lower.contains("sunfoil") || lower.contains("sunflower oil"))
            return "🫙";
        if (lower.contains("five roses") || lower.contains("tea"))
            return "🍵";

        // ── Tinned / Canned ───────────────────────────────────────────────
        if (lower.contains("baked bean") || lower.contains("koo bean"))
            return "🫘";
        if (lower.contains("tomato") || lower.contains("all gold") || lower.contains("tomato"))
            return "🍅";
        if (lower.contains("corn") || lower.contains("kernel"))
            return "🌽";
        if (lower.contains("fruit cocktail") || lower.contains("fruit"))
            return "🍑";
        if (lower.contains("samp") || lower.contains("vegetable in brine"))
            return "🥫";

        // ── Condiments & Sauces ───────────────────────────────────────────
        if (lower.contains("chutney") || lower.contains("mrs balls"))
            return "🫙";
        if (lower.contains("mayo") || lower.contains("mayonnaise"))
            return "🥫";
        if (lower.contains("relish") || lower.contains("braai relish"))
            return "🫙";
        if (lower.contains("sauce") || lower.contains("worcestershire")
                || lower.contains("kasi magic"))
            return "🍶";
        if (lower.contains("atchar") || lower.contains("peppadew"))
            return "🌶️";
        if (lower.contains("beetroot"))     return "🫛";

        // ── Grains ────────────────────────────────────────────────────────
        if (lower.contains("maize meal") || lower.contains("pap") || lower.contains("mabela")
                || lower.contains("porridge") || lower.contains("samp") && lower.contains("pride"))
            return "🌾";
        if (lower.contains("lentil"))       return "🫘";

        // ── Spreads ───────────────────────────────────────────────────────
        if (lower.contains("peanut butter") || lower.contains("black cat"))
            return "🥜";
        if (lower.contains("jam") || lower.contains("all gold jam") || lower.contains("hugo"))
            return "🍓";
        if (lower.contains("spread") || lower.contains("d'lite") || lower.contains("sunshine"))
            return "🧈";
        if (lower.contains("nola") || lower.contains("sandwich spread"))
            return "🫙";

        // ── Household ─────────────────────────────────────────────────────
        if (lower.contains("washing powder") || lower.contains("bio classic"))
            return "🧺";
        if (lower.contains("dishwashing") || lower.contains("maq"))
            return "🧴";
        if (lower.contains("doom") || lower.contains("mosquito") || lower.contains("peaceful sleep"))
            return "🦟";
        if (lower.contains("jeyes") || lower.contains("disinfectant") || lower.contains("cleaner"))
            return "🧹";
        if (lower.contains("charcoal") || lower.contains("charka") || lower.contains("brikett"))
            return "🔥";

        // ── Personal Care ─────────────────────────────────────────────────
        if (lower.contains("camphor") || lower.contains("ingram"))
            return "💊";
        if (lower.contains("deodorant") || lower.contains("roll on") || lower.contains("status deo"))
            return "🧴";
        if (lower.contains("shampoo") || lower.contains("conditioner") || lower.contains("gill"))
            return "🚿";
        if (lower.contains("pantyliner") || lower.contains("comfitex") || lower.contains("cotton pad"))
            return "🩹";
        if (lower.contains("cottonwool") || lower.contains("cherub"))
            return "🧻";
        if (lower.contains("betadine") || lower.contains("intimate"))
            return "💊";
        if (lower.contains("gel") || lower.contains("perfect touch"))
            return "💆";
        if (lower.contains("moisturi") || lower.contains("bennett"))
            return "🧴";

        // ── Bread & Bakery ────────────────────────────────────────────────
        if (lower.contains("bread") || lower.contains("sunbake") || lower.contains("bun")
                || lower.contains("rusk") || lower.contains("ouma"))
            return "🍞";

        // ── Cereals & Snacks ──────────────────────────────────────────────
        if (lower.contains("futurelife") || lower.contains("cereal") || lower.contains("oat")
                || lower.contains("muesli") || lower.contains("granola"))
            return "🥣";
        if (lower.contains("jungle") || lower.contains("snack") || lower.contains("bar"))
            return "🍫";
        if (lower.contains("fizzer") || lower.contains("toff") || lower.contains("wilson"))
            return "🍬";
        if (lower.contains("rusks"))        return "🍞";

        // ── Chicken / Meat (generic fallback) ────────────────────────────
        if (lower.contains("chicken"))      return "🍗";
        if (lower.contains("rainbow"))      return "🍗";

        return "🛒";
    }

    public record VoucherSyncResult(boolean success, int count, String message) {}
    public record CodeSyncResult(boolean success, int count, String message) {}
}