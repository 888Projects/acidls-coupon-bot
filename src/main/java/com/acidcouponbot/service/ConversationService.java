package com.acidcouponbot.service;

import com.acidcouponbot.model.Redemption;
import com.acidcouponbot.model.RandgoVoucher;
import com.acidcouponbot.model.UserSession;
import com.acidcouponbot.model.UserSession.SessionState;
import com.acidcouponbot.repository.RandgoVoucherRepository;
import com.acidcouponbot.repository.RedemptionRepository;
import com.acidcouponbot.middleware.MiddlewareClient;
import com.acidcouponbot.repository.UserSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Handles all multi-turn WhatsApp conversation state for the coupon bot.
 *
 * SUPPORTED INTENTS:
 *   "coupon" / "hi" / "1" / "start"   → category selection → bundle preview → claim
 *   "history" / "my coupons" / "2"     → show last 5 redemptions with codes + till status
 *   "help" / "menu" / anything else    → show main menu
 *
 * All methods are @Transactional — session state commits before
 * the next inbound message reads it (fixes H2 read-your-writes issue).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final UserSessionRepository   sessionRepo;
    private final RandgoVoucherRepository voucherRepo;
    private final RedemptionRepository    redemptionRepo;
    private final CouponService           couponService;
    private final WhatsAppService         whatsAppService;
    private final ObjectMapper            objectMapper;
    private final MiddlewareClient        middlewareClient;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy");

    // ── Entry point ───────────────────────────────────────────────────────────

    @Transactional
    public void handleMessage(String phone, String body) {
        String lower = body.toLowerCase().trim();

        UserSession session = sessionRepo.findById(phone)
                .orElseGet(() -> sessionRepo.save(
                        UserSession.builder().phoneNumber(phone).state(SessionState.IDLE).build()));

        if (session.isExpired()) {
            log.info("Session expired for +{} — resetting", phone);
            session.setState(SessionState.IDLE);
            session.setSelectedCategory(null);
        }

        log.info("Session +{} state={} body='{}'", phone, session.getState(), body);

        switch (session.getState()) {
            case AWAITING_CATEGORY_SELECTION  -> onCategorySelection(phone, body, session);
            case AWAITING_BUNDLE_CONFIRMATION -> onBundleConfirmation(phone, lower, session);
            default                            -> onIdle(phone, lower, session);
        }
    }

    // ── IDLE ──────────────────────────────────────────────────────────────────

    @Transactional
    protected void onIdle(String phone, String lower, UserSession session) {

        // History intent
        if (lower.contains("history") || lower.contains("my coupon")
                || lower.contains("past coupon") || lower.contains("redeemed")
                || lower.equals("2")) {
            showHistory(phone, session);
            return;
        }

        // Coupon claim intent
        if (lower.contains("coupon") || lower.contains("hi")
                || lower.contains("hello") || lower.equals("1")
                || lower.contains("start") || lower.contains("menu")) {

            // Check if suspended member
            MiddlewareClient.MemberStatus memberStatus =
                    middlewareClient.getMemberStatus(phone);

            if (memberStatus.suspended()) {
                whatsAppService.sendMessage(phone,
                        "⚠️ Your Tangiro wallet is currently suspended.\n"
                                + "Please contact support to resolve this.");
                return;
            }

            session.setState(SessionState.AWAITING_CATEGORY_SELECTION);
            session.setSelectedCategory(null);
            // Store member key so confirmation step knows which allocation to use
            session.setSelectedCategory(
                    memberStatus.isMember() ? "MEMBER:" + memberStatus.monthlyAllocationKey()
                            : "PROMO");
            sessionRepo.saveAndFlush(session);

            String greeting = memberStatus.isMember()
                    ? "🎉 *Your Monthly Coupons*\n\nHi! Choose a category for your monthly bundle:"
                    : "🎉 *Tangiro Coupons*\n\nChoose a category:";

            whatsAppService.sendMessage(phone,
                    greeting + "\n\n"
                            + buildCategoryMenu()
                            + "\n_Reply with a number to select._");
            return;
        }

        // Default — show menu
        whatsAppService.sendMessage(phone,
                "👋 Hi! Welcome to *Tangiro Coupons* 🎉\n\n"
                        + "1️⃣ *Coupon* — Claim free coupons\n"
                        + "2️⃣ *History* — View your past coupons\n\n"
                        + "_Reply with a number or keyword._");
    }

    // ── CATEGORY SELECTION ────────────────────────────────────────────────────

    @Transactional
    protected void onCategorySelection(String phone, String body, UserSession session) {
        CouponCategory selected = CouponCategory.fromMenuNumber(body);

        if (selected == null) {
            sessionRepo.saveAndFlush(session);
            whatsAppService.sendMessage(phone,
                    "⚠️ Please reply with a number (1–"
                            + CouponCategory.values().length + "):\n\n"
                            + buildCategoryMenu());
            return;
        }

        List<RandgoVoucher> vouchers =
                voucherRepo.findByCategoryAndActiveTrueAndIncludeInBundleTrue(selected.name());

        if (vouchers.isEmpty()) {
            sessionRepo.saveAndFlush(session);
            whatsAppService.sendMessage(phone,
                    selected.emoji + " No *" + selected.displayName
                            + "* coupons available right now. 😔\n\n"
                            + "Try another category:\n\n" + buildCategoryMenu());
            return;
        }

        session.setSelectedCategory(selected.name());
        session.setState(SessionState.AWAITING_BUNDLE_CONFIRMATION);
        sessionRepo.saveAndFlush(session);
        whatsAppService.sendMessage(phone, buildBundlePreview(selected, vouchers));
    }

    // ── BUNDLE CONFIRMATION ───────────────────────────────────────────────────

    @Transactional
    protected void onBundleConfirmation(String phone, String lower, UserSession session) {
        boolean yes = lower.equals("yes") || lower.equals("y") || lower.equals("1")
                || lower.equals("confirm") || lower.equals("ok");
        boolean no  = lower.equals("no")  || lower.equals("n") || lower.equals("2")
                || lower.equals("back")    || lower.equals("cancel");

        if (yes) {
            String allocationKey = session.getSelectedCategory(); // e.g. "MEMBER:27831234567-MEMBER-2026-05" or "PROMO"
            session.setState(SessionState.IDLE);
            session.setSelectedCategory(null);
            sessionRepo.saveAndFlush(session);

            CouponService.ClaimOutcome outcome;

            if (allocationKey != null && allocationKey.startsWith("MEMBER:")) {
                // ── Registered member → monthly allocation ─────────────────
                String memberKey = allocationKey.substring("MEMBER:".length());
                log.info("MEMBER_CLAIM +{} key={}", phone, memberKey);
                outcome = couponService.claimCouponsForMember(phone, memberKey);
            } else {
                // ── Non-member (promo QR scan) → once per campaign ─────────
                log.info("PROMO_CLAIM +{}", phone);
                outcome = couponService.claimCoupons(phone);
            }

            if (outcome.result() != CouponService.ClaimResult.SUCCESS
                    && outcome.result() != CouponService.ClaimResult.SUCCESS_FALLBACK
                    && outcome.result() != CouponService.ClaimResult.ALREADY_REDEEMED_RESENT) {
                whatsAppService.sendMessage(phone, outcome.message());
            }
            log.info("✅ Coupon claimed +{}: {}", phone, outcome.result());

        } else if (no) {
            session.setState(SessionState.AWAITING_CATEGORY_SELECTION);
            session.setSelectedCategory(null);
            sessionRepo.saveAndFlush(session);
            whatsAppService.sendMessage(phone,
                    "No problem! Choose a different category:\n\n" + buildCategoryMenu());

        } else {
            sessionRepo.saveAndFlush(session);
            whatsAppService.sendMessage(phone,
                    "Reply *Yes* to claim your coupons or *No* to go back. 👇");
        }
    }

    // ── HISTORY ───────────────────────────────────────────────────────────────

    @Transactional
    protected void showHistory(String phone, UserSession session) {
        session.setState(SessionState.IDLE);
        sessionRepo.saveAndFlush(session);

        List<Redemption> history =
                redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc(phone);

        if (history.isEmpty()) {
            whatsAppService.sendMessage(phone,
                    "📭 You haven't claimed any coupons yet.\n\n"
                            + "Send *Coupon* to get started! 🛒");
            return;
        }

        StringBuilder sb = new StringBuilder("🧾 *Your Coupon History*\n\n");
        int shown = 0;

        for (Redemption r : history) {
            if (shown >= 5) break;

            // Date
            sb.append("📅 *").append(r.getRedeemedAt() != null
                    ? r.getRedeemedAt().format(DATE_FMT) : "Unknown").append("*\n");

            // Codes
            try {
                List<String> codes = objectMapper.readValue(
                        r.getIssuedCodesJson(), new TypeReference<List<String>>() {});
                if (codes != null && !codes.isEmpty()) {
                    sb.append("🎟️ ");
                    for (int i = 0; i < Math.min(3, codes.size()); i++) {
                        if (i > 0) sb.append("  |  ");
                        sb.append("*").append(codes.get(i)).append("*");
                    }
                    if (codes.size() > 3)
                        sb.append("  _(+").append(codes.size() - 3).append(" more)_");
                    sb.append("\n");
                }
            } catch (Exception e) {
                sb.append("🎟️ _Codes unavailable_\n");
            }

            // Till status
            if (r.isRedeemedAtTill()) {
                sb.append("✅ _Used at till");
                if (r.getTillRedeemedAtStore() != null)
                    sb.append(" — ").append(r.getTillRedeemedAtStore());
                sb.append("_\n");
            } else {
                sb.append("⏳ _Not yet scanned at store_\n");
            }

            sb.append("\n");
            shown++;
        }

        if (history.size() > 5)
            sb.append("_Showing ").append(shown)
                    .append(" of ").append(history.size()).append(" total_\n\n");

        sb.append("💬 Send *Coupon* to get new coupons 🛒");
        whatsAppService.sendMessage(phone, sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildCategoryMenu() {
        StringBuilder sb = new StringBuilder();
        for (CouponCategory cat : CouponCategory.values())
            sb.append(cat.menuNumber()).append(". ")
                    .append(cat.emoji).append(" *").append(cat.displayName).append("*\n");
        return sb.toString();
    }

    private String buildBundlePreview(CouponCategory cat, List<RandgoVoucher> vouchers) {
        StringBuilder sb = new StringBuilder();
        sb.append(cat.emoji).append(" *").append(cat.displayName).append(" Bundle*\n\n");
        sb.append("Your bundle includes:\n\n");
        for (RandgoVoucher v : vouchers) {
            sb.append(v.getEmoji() != null ? v.getEmoji() : "✅").append(" *")
                    .append(v.getProduct() != null ? v.getProduct() : v.getVoucherName())
                    .append("*");
            if (v.getDiscountDisplay() != null)
                sb.append(" — ").append(v.getDiscountDisplay());
            sb.append("\n");
        }
        sb.append("\nReply *Yes* to claim or *No* to go back. 👇");
        return sb.toString();
    }
}