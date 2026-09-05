package com.acidcouponbot.randgo;

import com.acidcouponbot.model.RandgoSession;
import com.acidcouponbot.repository.RandgoSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The daily-Login guard on {@link RandgoSessionManager#forceRelogin()}. RandGo suspends the production
 * account ~2h if Login is called more than once a day, so a 401 that arrives while a Login already happened
 * in the last 24h must NOT trigger a second Login — it must refuse (DailyLoginExhaustedException) and let
 * the caller degrade to FAILED. The campaign guard and the legitimate >24h re-login are also pinned.
 */
@ExtendWith(MockitoExtension.class)
class RandgoSessionManagerTest {

    @Mock RandgoSessionRepository sessionRepository;
    @Mock WebClient.Builder       webClientBuilder;

    RandgoSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new RandgoSessionManager(sessionRepository, webClientBuilder);
        ReflectionTestUtils.setField(manager, "apiUrl", "https://randgo.test");
        ReflectionTestUtils.setField(manager, "username", "u");
        ReflectionTestUtils.setField(manager, "password", "p");
        ReflectionTestUtils.setField(manager, "mockMode", false);   // exercise the real guard
    }

    private RandgoSession sessionCreatedAt(LocalDateTime createdAt) {
        return RandgoSession.builder()
                .id(1L).sessionToken("tok").active(true)
                .createdAt(createdAt).expiresAt(createdAt.plusHours(23))
                .build();
    }

    @Test
    @DisplayName("401 with a Login < 24h ago → REFUSES a second Login (DailyLoginExhaustedException), no HTTP")
    void refusesSecondLoginWithin24h() {
        when(sessionRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.of(sessionCreatedAt(LocalDateTime.now().minusHours(1))));

        assertThatThrownBy(() -> manager.forceRelogin())
                .isInstanceOf(DailyLoginExhaustedException.class);

        // Never touched the login HTTP path, and never deactivated the still-valid session.
        verifyNoInteractions(webClientBuilder);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("401 with the last Login > 24h ago → guard passes (attempts a real re-login, not a refusal)")
    void allowsReloginAfter24h() {
        when(sessionRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.of(sessionCreatedAt(LocalDateTime.now().minusHours(25))));
        when(sessionRepository.findTopByActiveTrueOrderByCreatedAtDesc())
                .thenReturn(Optional.empty());

        // Past the guard it proceeds to login(), which fails on the unstubbed WebClient — the point is only
        // that it did NOT refuse with DailyLoginExhaustedException.
        assertThatThrownBy(() -> manager.forceRelogin())
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(DailyLoginExhaustedException.class);
    }

    @Test
    @DisplayName("no prior Login on record → guard passes (first-ever login is legitimate)")
    void allowsFirstEverLogin() {
        when(sessionRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(sessionRepository.findTopByActiveTrueOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.forceRelogin())
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(DailyLoginExhaustedException.class);
    }

    @Test
    @DisplayName("campaign thread still short-circuits FIRST with CampaignLoginSuppressedException (unchanged)")
    void campaignGuardStillWins() {
        manager.enterCampaignMode();
        try {
            assertThatThrownBy(() -> manager.forceRelogin())
                    .isInstanceOf(CampaignLoginSuppressedException.class);
            // Campaign guard fires before the daily-login lookup runs at all.
            verifyNoInteractions(sessionRepository);
        } finally {
            manager.exitCampaignMode();
        }
    }
}
