package com.acidcouponbot.service;

import com.acidcouponbot.model.*;
import com.acidcouponbot.model.UserSession.SessionState;
import com.acidcouponbot.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConversationService")
class ConversationServiceTest {

    @Mock UserSessionRepository                        sessionRepo;
    @Mock RandgoVoucherRepository                      voucherRepo;
    @Mock RedemptionRepository                         redemptionRepo;
    @Mock CouponService                                couponService;
    @Mock WhatsAppService                              whatsAppService;
    @Mock com.acidcouponbot.middleware.MiddlewareClient middlewareClient;

    @InjectMocks ConversationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // inject real ObjectMapper
        org.springframework.test.util.ReflectionTestUtils
                .setField(service, "objectMapper", objectMapper);

        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(middlewareClient.getMemberStatus(anyString()))
                .thenReturn(com.acidcouponbot.middleware.MiddlewareClient.MemberStatus
                        .nonMember("27831234567"));
        when(sessionRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(whatsAppService.sendMessage(anyString(), anyString())).thenReturn(true);
    }

    // ── IDLE state ─────────────────────────────────────────────────────────────

    @Test @DisplayName("'coupon' triggers category menu")
    void couponKeyword_triggersCategoryMenu() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));

        service.handleMessage("27831234567", "Coupon");

        ArgumentCaptor<UserSession> cap = ArgumentCaptor.forClass(UserSession.class);
        verify(sessionRepo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getState())
                .isEqualTo(SessionState.AWAITING_CATEGORY_SELECTION);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(whatsAppService).sendMessage(eq("27831234567"), msg.capture());
        assertThat(msg.getValue()).contains("Groceries").contains("Toiletries");
    }

    @Test @DisplayName("'history' shows redemption history")
    void historyKeyword_showsHistory() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));
        when(redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc("27831234567"))
                .thenReturn(List.of());

        service.handleMessage("27831234567", "History");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("haven't claimed")));
    }

    @Test @DisplayName("'my coupons' also triggers history")
    void myCoupons_triggersHistory() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));
        when(redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc("27831234567"))
                .thenReturn(List.of());

        service.handleMessage("27831234567", "my coupons");

        verify(redemptionRepo).findByPhoneNumberOrderByRedeemedAtDesc("27831234567");
    }

    @Test @DisplayName("unknown message shows main menu")
    void unknownMessage_showsMainMenu() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));

        service.handleMessage("27831234567", "random text");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Coupon") && m.contains("History")));
    }

    @Test @DisplayName("expired session resets to IDLE before processing")
    void expiredSession_resetsToIdle() {
        UserSession expired = idleSession();
        expired.setState(SessionState.AWAITING_CATEGORY_SELECTION);
        expired.setLastUpdated(LocalDateTime.now().minusMinutes(15));
        when(sessionRepo.findById("27831234567")).thenReturn(Optional.of(expired));

        service.handleMessage("27831234567", "random text");

        // After reset it processes as IDLE — shows main menu
        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Coupon")));
    }

    @Test @DisplayName("new user gets a session created")
    void newUser_sessionCreated() {
        when(sessionRepo.findById("27831234567")).thenReturn(Optional.empty());
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(middlewareClient.getMemberStatus(anyString()))
                .thenReturn(com.acidcouponbot.middleware.MiddlewareClient.MemberStatus
                        .nonMember("27831234567"));
        when(redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc(any()))
                .thenReturn(List.of());

        service.handleMessage("27831234567", "History");

        verify(sessionRepo).save(any(UserSession.class));
    }

    // ── Category selection ─────────────────────────────────────────────────────

    @Test @DisplayName("valid category number shows bundle preview")
    void validCategoryNumber_showsBundlePreview() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(awaitingCategorySession()));
        when(voucherRepo.findByCategoryAndActiveTrueAndIncludeInBundleTrue("GROCERIES"))
                .thenReturn(List.of(voucher("Mealie Meal", "GROCERIES")));

        service.handleMessage("27831234567", "1");

        ArgumentCaptor<UserSession> cap = ArgumentCaptor.forClass(UserSession.class);
        verify(sessionRepo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getState())
                .isEqualTo(SessionState.AWAITING_BUNDLE_CONFIRMATION);
        assertThat(cap.getValue().getSelectedCategory()).isEqualTo("GROCERIES");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Groceries") && m.contains("Yes")));
    }

    @Test @DisplayName("invalid category number re-shows menu")
    void invalidCategoryNumber_reshowsMenu() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(awaitingCategorySession()));

        service.handleMessage("27831234567", "99");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Please reply with a number")));
    }

    @Test @DisplayName("empty category shows no coupons message")
    void emptyCategory_showsNoCouponsMessage() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(awaitingCategorySession()));
        when(voucherRepo.findByCategoryAndActiveTrueAndIncludeInBundleTrue(any()))
                .thenReturn(List.of());

        service.handleMessage("27831234567", "1");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("No") && m.contains("available")));
    }

    // ── Bundle confirmation ────────────────────────────────────────────────────

    @Test @DisplayName("'yes' claims coupons and resets session")
    void yes_claimsCoupons() {
        UserSession session = awaitingConfirmationSession("GROCERIES");
        when(sessionRepo.findById("27831234567")).thenReturn(Optional.of(session));
        when(couponService.claimCoupons("27831234567"))
                .thenReturn(new CouponService.ClaimOutcome(
                        CouponService.ClaimResult.SUCCESS, "done", List.of(), false));

        service.handleMessage("27831234567", "Yes");

        verify(couponService).claimCoupons("27831234567");
        ArgumentCaptor<UserSession> cap = ArgumentCaptor.forClass(UserSession.class);
        verify(sessionRepo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getState()).isEqualTo(SessionState.IDLE);
    }

    @Test @DisplayName("'no' goes back to category menu")
    void no_goesBackToMenu() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(awaitingConfirmationSession("GROCERIES")));

        service.handleMessage("27831234567", "No");

        ArgumentCaptor<UserSession> cap = ArgumentCaptor.forClass(UserSession.class);
        verify(sessionRepo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getState())
                .isEqualTo(SessionState.AWAITING_CATEGORY_SELECTION);
        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("category")));
    }

    @Test @DisplayName("unrecognised reply asks to confirm or go back")
    void unrecognisedReply_asksAgain() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(awaitingConfirmationSession("GROCERIES")));

        service.handleMessage("27831234567", "maybe");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Yes") && m.contains("No")));
        verify(couponService, never()).claimCoupons(any());
    }

    @Test @DisplayName("failed claim sends error message to user")
    void failedClaim_sendsErrorMessage() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(awaitingConfirmationSession("GROCERIES")));
        when(couponService.claimCoupons("27831234567"))
                .thenReturn(new CouponService.ClaimOutcome(
                        CouponService.ClaimResult.RANDGO_FAILED,
                        "Sorry, try again later.", null, false));

        service.handleMessage("27831234567", "yes");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Sorry")));
    }

    // ── History ────────────────────────────────────────────────────────────────

    @Test @DisplayName("history shows last 5 redemptions with codes")
    void history_showsRedemptions() throws Exception {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));

        List<String> codes = List.of("MILK-AB12", "BRDL-XY99", "OIL-44ZZ");
        Redemption r = redemption("27831234567", codes, false);
        when(redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc("27831234567"))
                .thenReturn(List.of(r));

        service.handleMessage("27831234567", "History");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("MILK-AB12")
                        && m.contains("Coupon History")));
    }

    @Test @DisplayName("history shows till status when redeemed at store")
    void history_showsTillStatus() throws Exception {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));
        Redemption r = redemption("27831234567", List.of("CODE-001"), true);
        r.setTillRedeemedAtStore("Pick n Pay Sandton");
        when(redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc("27831234567"))
                .thenReturn(List.of(r));

        service.handleMessage("27831234567", "2");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("Pick n Pay") && (m.contains("Used at till") || m.contains("Used at store"))));
    }

    @Test @DisplayName("history caps at 5 entries")
    void history_capsAtFive() throws Exception {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));
        List<Redemption> many = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            many.add(redemption("27831234567", List.of("CODE-" + i), false));
        when(redemptionRepo.findByPhoneNumberOrderByRedeemedAtDesc("27831234567"))
                .thenReturn(many);

        service.handleMessage("27831234567", "History");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("8 redemptions") || m.contains("of 8")));
    }

    // ── Member vs Promo routing ───────────────────────────────────────────────

    @Test @DisplayName("member confirmation calls claimCouponsForMember")
    void memberConfirmation_callsClaimCouponsForMember() {
        UserSession session = awaitingConfirmationSession("MEMBER:27831234567-MEMBER-2026-05");
        when(sessionRepo.findById("27831234567")).thenReturn(Optional.of(session));
        when(couponService.claimCouponsForMember(eq("27831234567"),
                eq("27831234567-MEMBER-2026-05")))
                .thenReturn(new CouponService.ClaimOutcome(
                        CouponService.ClaimResult.SUCCESS, "done", List.of(), false));

        service.handleMessage("27831234567", "yes");

        verify(couponService).claimCouponsForMember("27831234567",
                "27831234567-MEMBER-2026-05");
        verify(couponService, never()).claimCoupons(any());
    }

    @Test @DisplayName("promo confirmation calls claimCoupons not member method")
    void promoConfirmation_callsClaimCoupons() {
        UserSession session = awaitingConfirmationSession("PROMO");
        when(sessionRepo.findById("27831234567")).thenReturn(Optional.of(session));
        when(couponService.claimCoupons("27831234567"))
                .thenReturn(new CouponService.ClaimOutcome(
                        CouponService.ClaimResult.SUCCESS, "done", List.of(), false));

        service.handleMessage("27831234567", "yes");

        verify(couponService).claimCoupons("27831234567");
        verify(couponService, never()).claimCouponsForMember(any(), any());
    }

    @Test @DisplayName("suspended member gets blocked message")
    void suspendedMember_getsBlockedMessage() {
        when(sessionRepo.findById("27831234567"))
                .thenReturn(Optional.of(idleSession()));
        when(middlewareClient.getMemberStatus("27831234567"))
                .thenReturn(new com.acidcouponbot.middleware.MiddlewareClient.MemberStatus(
                        "27831234567", true, true, "wallet-123", null, null, "SUSPENDED"));

        service.handleMessage("27831234567", "Coupon");

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("suspended")));
        verify(sessionRepo, never()).saveAndFlush(
                argThat(s -> s.getState() == SessionState.AWAITING_CATEGORY_SELECTION));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserSession idleSession() {
        return UserSession.builder()
                .phoneNumber("27831234567")
                .state(SessionState.IDLE)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private UserSession awaitingCategorySession() {
        return UserSession.builder()
                .phoneNumber("27831234567")
                .state(SessionState.AWAITING_CATEGORY_SELECTION)
                .selectedCategory("PROMO") // set by onIdle before transitioning state
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private UserSession awaitingConfirmationSession(String category) {
        return UserSession.builder()
                .phoneNumber("27831234567")
                .state(SessionState.AWAITING_BUNDLE_CONFIRMATION)
                .selectedCategory(category)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private RandgoVoucher voucher(String name, String category) {
        RandgoVoucher v = new RandgoVoucher();
        v.setVoucherName(name);
        v.setProduct(name);
        v.setCategory(category);
        v.setEmoji("🛒");
        v.setDiscountDisplay("R5 off");
        v.setActive(true);
        v.setIncludeInBundle(true);
        return v;
    }

    private Redemption redemption(String phone, List<String> codes, boolean tillRedeemed)
            throws Exception {
        Redemption r = new Redemption();
        r.setPhoneNumber(phone);
        r.setCampaignId(1L);
        r.setIssuedCodesJson(objectMapper.writeValueAsString(codes));
        r.setRedeemedAtTill(tillRedeemed);
        r.setRedeemedAt(LocalDateTime.now());
        return r;
    }
}