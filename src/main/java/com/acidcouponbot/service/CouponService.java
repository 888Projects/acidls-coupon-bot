package com.acidcouponbot.service;

import com.acidcouponbot.model.*;
import com.acidcouponbot.randgo.RandgoApiClient;
import com.acidcouponbot.randgo.RandgoMemberService;
import com.acidcouponbot.randgo.dto.RandgoCouponCheckoutResponse;
import com.acidcouponbot.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Core coupon issuance logic.
 *
 * FLOW:
 * 1. Check campaign active + not expired
 * 2. Check phone not already redeemed (our DB)
 * 3. Register member in Randgo if needed
 * 4. PRIMARY: Call Randgo CouponBasketCheckout (live)
 * 5. FALLBACK: If Randgo API fails, use cached local codes
 * 6. Build WhatsApp message with codes
 * 7. Send via WhatsApp
 * 8. Record redemption in DB
 * 9. Retry failed sends every 5 minutes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CouponService {

    private static final int MAX_SEND_ATTEMPTS = 3;

    private final CampaignRepository campaignRepository;
    private final RandgoVoucherRepository randgoVoucherRepository;
    private final RedemptionRepository redemptionRepository;
    private final CachedCouponCodeRepository cachedCouponCodeRepository;
    private final RandgoApiClient randgoApiClient;
    private final RandgoMemberService randgoMemberService;
    private final WhatsAppService whatsAppService;
    private final ObjectMapper objectMapper;

    public enum ClaimResult {
        SUCCESS,
        SUCCESS_FALLBACK,         // Issued from local cache (Randgo was down)
        ALREADY_REDEEMED,
        MEMBER_ALREADY_CLAIMED_THIS_MONTH,
        ALREADY_REDEEMED_RESENT,  // Previous send failed — resent
        NO_ACTIVE_CAMPAIGN,
        CAMPAIGN_EXPIRED,
        NO_VOUCHERS_CONFIGURED,
        RANDGO_FAILED,            // Both live API and cache failed
        RATE_LIMITED
    }

    public record ClaimOutcome(
            ClaimResult result,
            String message,
            List<String> issuedCodes,
            boolean usedFallback
    ) {}

    /**
     * The current coupon bundle's voucher GUIDs — the SAME selection {@link #claimCoupons} issues: enabled
     * vouchers (active + include_in_bundle), display-ordered, capped to the active campaign's bundleSize
     * (default 5). Exposed for the bulk campaign runner so it issues EXACTLY what the live claim path issues.
     * Read-only; does not change how the bundle is chosen. Returns empty if there is no active campaign or no
     * enabled vouchers (the runner treats empty as a hard stop rather than issuing nothing).
     */
    @Transactional(readOnly = true)
    public List<String> selectBundleVoucherGuids() {
        Optional<Campaign> campaignOpt = campaignRepository.findByActiveTrue();
        if (campaignOpt.isEmpty()) return List.of();

        List<RandgoVoucher> allBundled = randgoVoucherRepository
                .findByActiveTrueAndIncludeInBundleTrueOrderByDisplayOrderAsc();
        if (allBundled.isEmpty()) return List.of();

        int bundleLimit = campaignOpt.get().getBundleSize() > 0 ? campaignOpt.get().getBundleSize() : 5;
        List<RandgoVoucher> vouchers = allBundled.size() > bundleLimit
                ? allBundled.subList(0, bundleLimit)
                : allBundled;
        return vouchers.stream().map(RandgoVoucher::getVoucherGuid).toList();
    }

    // ─── Main Claim Method ────────────────────────────────────────────────────

    @Transactional
    public ClaimOutcome claimCoupons(String phoneNumber) {
        log.info("Coupon claim request from +{}", phoneNumber);

        // 1. Find active campaign
        Optional<Campaign> campaignOpt = campaignRepository.findByActiveTrue();
        if (campaignOpt.isEmpty()) {
            return new ClaimOutcome(ClaimResult.NO_ACTIVE_CAMPAIGN,
                    "🔔 *No Active Campaign*\n\nThere are no coupon campaigns running right now.\n\nFollow us for updates — new campaigns drop regularly! 💛\n\n_Reply *HELP* for support._",
                    null, false);
        }

        Campaign campaign = campaignOpt.get();

        // 2. Check campaign expiry
        if (campaign.isExpired()) {
            return new ClaimOutcome(ClaimResult.CAMPAIGN_EXPIRED,
                    "⏰ *This Campaign Has Ended*\n\nUnfortunately this coupon campaign is no longer active.\n\nStay tuned — the next campaign is coming soon! 🛍️\n\n_Reply *HELP* for support._",
                    null, false);
        }

        // 3. Check if already redeemed
        Optional<Redemption> existing = redemptionRepository
                .findByPhoneNumberAndCampaignId(phoneNumber, campaign.getId());

        if (existing.isPresent()) {
            Redemption redemption = existing.get();

            // FIX: If previous WhatsApp send failed — resend instead of rejecting
            if (!redemption.isMessageSentSuccessfully()
                    && redemption.getSendAttempts() < MAX_SEND_ATTEMPTS) {
                log.info("Previous send failed for +{} — resending", phoneNumber);
                return retrySend(redemption);
            }

            log.info("Duplicate claim from +{} — already redeemed on {}",
                    phoneNumber, redemption.getRedeemedAt().toLocalDate());
            return new ClaimOutcome(ClaimResult.ALREADY_REDEEMED,
                    buildAlreadyRedeemedMessage(redemption), null, false);
        }

        // 4. Get active vouchers for this campaign
        // Load vouchers marked for bundle, capped to the campaign's bundle size
        // bundleSize prevents ALL synced vouchers from being issued when admin
        // hasn't manually curated the bundle (sync sets includeInBundle=true on all)
        List<RandgoVoucher> allBundled = randgoVoucherRepository
                .findByActiveTrueAndIncludeInBundleTrueOrderByDisplayOrderAsc();

        if (allBundled.isEmpty()) {
            log.error("No active vouchers configured — cannot issue coupons");
            return new ClaimOutcome(ClaimResult.NO_VOUCHERS_CONFIGURED,
                    "⚠️ *Coupons Temporarily Unavailable*\n\nWe're setting up the latest coupon bundle. Please try again in a few minutes.\n\n_Reply *HELP* if you need assistance._ 💛",
                    null, false);
        }

        // Cap to campaign bundle size (default 5 if not set)
        int bundleLimit = campaign.getBundleSize() > 0 ? campaign.getBundleSize() : 5;
        List<RandgoVoucher> vouchers = allBundled.size() > bundleLimit
                ? allBundled.subList(0, bundleLimit)
                : allBundled;

        log.info("Bundle: {} vouchers selected (limit: {}, total in DB: {})",
                vouchers.size(), bundleLimit, allBundled.size());

        List<String> voucherGuids = vouchers.stream()
                .map(RandgoVoucher::getVoucherGuid).toList();

        // 5. Register member in Randgo (if not already)
        randgoMemberService.ensureMemberRegistered(phoneNumber);

        // 6. PRIMARY: Try live Randgo API
        List<IssuedCodeDetail> issuedCodes = null;
        boolean usedFallback = false;
        String randgoBasketGuid = null;

        try {
            log.info("Calling Randgo CouponBasketCheckout for +{}", phoneNumber);
            RandgoCouponCheckoutResponse response = randgoApiClient
                    .checkoutCoupons(phoneNumber, voucherGuids);

            if (response != null && response.isSuccess()
                    && response.getBasket() != null
                    && response.getBasket().getCodes() != null
                    && !response.getBasket().getCodes().isEmpty()) {

                randgoBasketGuid = response.getBasket().getGuid();
                issuedCodes = mapToIssuedCodeDetails(
                        response.getBasket().getCodes(), vouchers);
                log.info("Live Randgo checkout successful for +{} — {} codes issued",
                        phoneNumber, issuedCodes.size());
            } else {
                String msg = response != null ? response.getMessage() : "null response";
                log.warn("Randgo checkout returned no codes for +{}: {}", phoneNumber, msg);
            }

        } catch (Exception e) {
            log.error("Randgo live API failed for +{}: {} — trying fallback cache",
                    phoneNumber, e.getMessage());
        }

        // 7. FALLBACK: Use cached local codes if live API failed
        if (issuedCodes == null || issuedCodes.isEmpty()) {
            log.warn("Using fallback cached codes for +{}", phoneNumber);
            issuedCodes = assignFallbackCodes(vouchers, phoneNumber);
            usedFallback = true;

            if (issuedCodes == null || issuedCodes.isEmpty()) {
                log.error("Both Randgo API and fallback cache failed for +{}", phoneNumber);
                return new ClaimOutcome(ClaimResult.RANDGO_FAILED,
                        "⚠️ *Temporary Issue*\n\nWe're having trouble processing your coupons right now.\n\n🔄 *Please try again in 2 minutes.*\n\nIf this keeps happening, reply *HELP* and our team will sort it out for you. 💛",
                        null, false);
            }
            log.info("Fallback codes assigned for +{} — {} codes", phoneNumber, issuedCodes.size());
        }

        // 8. Build WhatsApp message
        String message = buildCouponMessage(issuedCodes, campaign, usedFallback);

        // 9. Save redemption BEFORE sending
        List<String> codeStrings = issuedCodes.stream()
                .map(IssuedCodeDetail::code).toList();

        Redemption redemption = Redemption.builder()
                .phoneNumber(phoneNumber)
                .campaignId(campaign.getId())
                .randgoBasketGuid(randgoBasketGuid)
                .issuedCodesJson(toJson(codeStrings))
                .messageSent(message)
                .messageSentSuccessfully(false)
                .sendAttempts(0)
                .memberRegisteredInRandgo(true)
                .build();
        redemption = redemptionRepository.save(redemption);

        // 10. Send WhatsApp message
        boolean sent = whatsAppService.sendMessage(phoneNumber, message);
        redemption.setMessageSentSuccessfully(sent);
        redemption.setSendAttempts(1);
        redemption.setLastSendAttempt(LocalDateTime.now());
        redemptionRepository.save(redemption);

        if (sent) {
            log.info("✅ Coupons sent to +{} (fallback: {})", phoneNumber, usedFallback);
        } else {
            log.error("⚠️ Coupons assigned but WhatsApp send FAILED for +{} — will retry", phoneNumber);
        }

        ClaimResult result = usedFallback ? ClaimResult.SUCCESS_FALLBACK : ClaimResult.SUCCESS;
        return new ClaimOutcome(result, message, codeStrings, usedFallback);
    }

    // ─── Fallback Code Assignment ─────────────────────────────────────────────

    /**
     * Assigns one cached code per voucher type from local pool.
     * FIX: Uses pessimistic lock to prevent race conditions.
     */
    @Transactional
    public List<IssuedCodeDetail> assignFallbackCodes(
            List<RandgoVoucher> vouchers, String phoneNumber) {

        List<IssuedCodeDetail> assigned = new ArrayList<>();

        for (RandgoVoucher voucher : vouchers) {
            Optional<CachedCouponCode> codeOpt = cachedCouponCodeRepository
                    .findNextAvailableForUpdate(voucher.getVoucherGuid());

            if (codeOpt.isEmpty()) {
                log.warn("No fallback codes available for voucher: {}", voucher.getVoucherName());
                // Rollback assigned codes
                assigned.forEach(d -> {
                    cachedCouponCodeRepository.findById(d.cacheId()).ifPresent(c -> {
                        c.setStatus(CachedCouponCode.CodeStatus.AVAILABLE);
                        c.setAssignedToPhone(null);
                        cachedCouponCodeRepository.save(c);
                    });
                });
                return null;
            }

            CachedCouponCode code = codeOpt.get();
            code.setStatus(CachedCouponCode.CodeStatus.USED_FALLBACK);
            code.setAssignedToPhone(phoneNumber);
            code.setAssignedAt(LocalDateTime.now());
            code.setUsedAsFallback(true);
            cachedCouponCodeRepository.save(code);

            assigned.add(new IssuedCodeDetail(
                    code.getId(), code.getCode(),
                    voucher.getProduct(), voucher.getDiscountDisplay(),
                    voucher.getEmoji(), voucher.getVoucherName()
            ));
        }

        return assigned;
    }

    // ─── Retry Failed Sends ───────────────────────────────────────────────────

    /**
     * Claims monthly coupon allocation for a registered ACID LS member.
     * Uses allocationKey = phone + "-MEMBER-" + YYYY-MM as the idempotency key
     * so allocation resets automatically each calendar month.
     *
     * Non-members use the standard claimCoupons() which is once per campaign.
     */
    @Transactional
    public ClaimOutcome claimCouponsForMember(String phoneNumber, String allocationKey) {
        log.info("MEMBER_COUPON_CLAIM phone={} key={}", phoneNumber, allocationKey);

        // Check if already claimed this month
        boolean alreadyClaimed = redemptionRepository.findAll().stream()
                .anyMatch(r -> allocationKey.equals(r.getRandgoBasketGuid())
                        || allocationKey.equals(r.getRandgoBatchGuid()));

        if (alreadyClaimed) {
            log.info("MEMBER_COUPON_ALREADY_CLAIMED phone={} key={}", phoneNumber, allocationKey);
            return new ClaimOutcome(
                    ClaimResult.MEMBER_ALREADY_CLAIMED_THIS_MONTH,
                    "✅ You've already claimed your coupons for this month.\n\n"
                            + "Send *History* to see your active codes or come back next month! 🗓️",
                    null, false);
        }

        // Claim using standard flow — passes allocationKey as basket reference
        ClaimOutcome outcome = claimCoupons(phoneNumber);

        // If successful, tag the redemption with the allocation key for future checks
        if (outcome.result() == ClaimResult.SUCCESS
                || outcome.result() == ClaimResult.SUCCESS_FALLBACK) {
            redemptionRepository.findByPhoneNumberAndCampaignId(
                            phoneNumber,
                            campaignRepository.findByActiveTrue()
                                    .map(c -> c.getId()).orElse(0L))
                    .ifPresent(r -> {
                        r.setRandgoBatchGuid(allocationKey); // reuse field as allocation tag
                        redemptionRepository.save(r);
                    });
        }

        return outcome;
    }

    public void retryFailedSends() {
        List<Redemption> failed = redemptionRepository
                .findByMessageSentSuccessfullyFalseAndSendAttemptsLessThan(MAX_SEND_ATTEMPTS);

        if (failed.isEmpty()) return;

        log.info("Retry job: {} failed sends to retry", failed.size());

        for (Redemption redemption : failed) {
            try {
                boolean sent = whatsAppService.sendMessage(
                        redemption.getPhoneNumber(), redemption.getMessageSent());

                redemption.setMessageSentSuccessfully(sent);
                redemption.setSendAttempts(redemption.getSendAttempts() + 1);
                redemption.setLastSendAttempt(LocalDateTime.now());
                redemptionRepository.save(redemption);

                if (sent) {
                    log.info("✅ Retry successful for +{} (attempt {})",
                            redemption.getPhoneNumber(), redemption.getSendAttempts());
                } else {
                    log.warn("⚠️ Retry failed for +{} (attempt {}/{})",
                            redemption.getPhoneNumber(),
                            redemption.getSendAttempts(), MAX_SEND_ATTEMPTS);
                }
            } catch (Exception e) {
                log.error("Error during retry for +{}: {}",
                        redemption.getPhoneNumber(), e.getMessage());
            }
        }
    }

    // ─── Message Builders ─────────────────────────────────────────────────────

    private String buildCouponMessage(
            List<IssuedCodeDetail> codes, Campaign campaign, boolean fallback) {

        String retailer = campaign.getRetailerName() != null ? campaign.getRetailerName() : "our partner store";
        String emoji    = campaign.getRetailerEmoji() != null ? campaign.getRetailerEmoji() : "🛍️";

        StringBuilder sb = new StringBuilder();

        // ── Header ──────────────────────────────────────────────────────────
        sb.append("🎉 *Your FREE Grocery Coupons are here!*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        sb.append(emoji).append(" *Valid at: ").append(retailer).append("*\n");

        if (campaign.getExpiryDate() != null) {
            sb.append("⏰ *Expires: ").append(campaign.getExpiryDate().toLocalDate()).append("*\n");
        }

        sb.append("\n");

        // ── Coupon codes ────────────────────────────────────────────────────
        sb.append("🧾 *YOUR COUPON CODES*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        int codeNum = 1;
        for (IssuedCodeDetail item : codes) {
            String e = item.emoji() != null ? item.emoji() : "🛒";
            // product may contain
            // + for multiple products on one code
            String[] productLines = item.product().split("\\n     \\+ ");
            if (productLines.length == 1) {
                // Single product per code
                sb.append(e).append(" *").append(item.product()).append("*");
                if (item.discount() != null && !item.discount().isBlank()) {
                    sb.append("  |  Save ").append(item.discount());
                }
                sb.append("\n");
            } else {
                // Multiple products share this code (Randgo denomination grouping)
                sb.append("🏷️ *Bundle Code — covers:*\n");
                for (String line : productLines) {
                    sb.append("   ").append(e).append(" ").append(line.trim()).append("\n");
                }
                if (item.discount() != null && !item.discount().isBlank()) {
                    sb.append("   💰 Total savings: ").append(item.discount()).append("\n");
                }
            }
            sb.append("┌─────────────────────────┐\n");
            sb.append("│  `").append(item.code()).append("`  │\n");
            sb.append("└─────────────────────────┘\n\n");
            codeNum++;
        }

        // ── How to redeem ───────────────────────────────────────────────────
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📋 *HOW TO REDEEM*\n\n");
        sb.append("1️⃣  Add the qualifying product to your trolley\n");
        sb.append("2️⃣  At the till, tell the cashier you have a coupon\n");
        sb.append("3️⃣  Show this message on your phone\n");
        sb.append("4️⃣  The cashier scans or types the code\n");
        sb.append("5️⃣  Your discount is applied instantly! ✅\n\n");

        // ── Important notes ─────────────────────────────────────────────────
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("ℹ️ *IMPORTANT*\n\n");
        sb.append("• Each code is *unique to you* — do not share\n");
        sb.append("• Each code can only be used *once*\n");
        sb.append("• Keep this message saved in your chat\n");
        sb.append("• One coupon per qualifying product per visit\n\n");

        // ── Footer ──────────────────────────────────────────────────────────
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("_Powered by Tangiro Wallet_ 💛\n");
        sb.append("_Reply *HELP* for support_");

        return sb.toString();
    }

    private String buildAlreadyRedeemedMessage(Redemption redemption) {
        return "✅ *You already have your coupons!*\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + "📅 You claimed them on *" + redemption.getRedeemedAt().toLocalDate() + "*\n\n"
                + "🔍 *To find your codes:*\n"
                + "Scroll up in this chat — your coupon message is saved here.\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "_Can't find them? Reply *HELP* and we'll resend._ 💛";
    }

    private ClaimOutcome retrySend(Redemption redemption) {
        boolean sent = whatsAppService.sendMessage(
                redemption.getPhoneNumber(), redemption.getMessageSent());
        redemption.setMessageSentSuccessfully(sent);
        redemption.setSendAttempts(redemption.getSendAttempts() + 1);
        redemption.setLastSendAttempt(LocalDateTime.now());
        redemptionRepository.save(redemption);

        return new ClaimOutcome(
                sent ? ClaimResult.ALREADY_REDEEMED_RESENT : ClaimResult.ALREADY_REDEEMED,
                redemption.getMessageSent(), null, false);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<IssuedCodeDetail> mapToIssuedCodeDetails(
            List<RandgoCouponCheckoutResponse.IssuedCode> randgoCodes,
            List<RandgoVoucher> vouchers) {

        // Build a GUID → voucher lookup map
        java.util.Map<String, RandgoVoucher> byGuid = new java.util.LinkedHashMap<>();
        for (RandgoVoucher v : vouchers) {
            byGuid.put(v.getVoucherGuid().toUpperCase(), v);
        }

        List<IssuedCodeDetail> details = new ArrayList<>();
        for (RandgoCouponCheckoutResponse.IssuedCode code : randgoCodes) {
            // Each code can cover multiple vouchers (Randgo groups by denomination)
            List<String> products    = new ArrayList<>();
            List<String> discounts   = new ArrayList<>();
            List<String> emojis      = new ArrayList<>();
            List<String> voucherNames = new ArrayList<>();
            String       firstEmoji  = "🛒";

            if (code.getCoupons() != null) {
                for (RandgoCouponCheckoutResponse.CouponResult cr : code.getCoupons()) {
                    if (cr.isIssueFailed()) continue;
                    RandgoVoucher v = byGuid.get(cr.getVoucherGuid().toUpperCase());
                    if (v != null) {
                        // Use full voucher name — customers need to know exact product
                        products.add(v.getVoucherName() != null ? v.getVoucherName() : v.getProduct());
                        discounts.add(v.getDiscountDisplay() != null ? v.getDiscountDisplay() : "");
                        emojis.add(v.getEmoji() != null ? v.getEmoji() : "🛒");
                        voucherNames.add(v.getVoucherName());
                    }
                }
            }

            // If no voucher mapping found, fall back to denomination display
            if (products.isEmpty()) {
                String denom = code.getDenomination() != null
                        ? "R " + String.format("%.0f", code.getDenomination() / 100.0)
                        : "Coupon";
                products.add(denom);
                discounts.add(denom);
            }

            if (!emojis.isEmpty()) firstEmoji = emojis.get(0);

            // For grouped codes: each product on its own line in the message
            String productDisplay  = String.join("\n     + ", products);
            String discountDisplay = discounts.stream()
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" | "));
            String nameDisplay     = String.join(" + ", voucherNames);

            details.add(new IssuedCodeDetail(
                    null,
                    code.getIssuedCode(),
                    productDisplay,
                    discountDisplay,
                    firstEmoji,
                    nameDisplay
            ));
        }
        return details;
    }

    private String toJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (JsonProcessingException e) {
            return codes.toString();
        }
    }

    public record IssuedCodeDetail(
            Long cacheId,
            String code,
            String product,
            String discount,
            String emoji,
            String voucherName
    ) {}
}