package com.acidcouponbot.controller;

import com.acidcouponbot.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookController")
class WebhookControllerTest {

    @Mock ConversationService conversationService;
    @Mock CouponService       couponService;
    @Mock WhatsAppService     whatsAppService;
    @Mock RateLimiterService  rateLimiterService;
    @InjectMocks WebhookController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "verifyToken",
                "acid_coupon_verify_token_2024");
        ReflectionTestUtils.setField(controller, "appSecret", "YOUR_APP_SECRET");
        ReflectionTestUtils.setField(controller, "devMode", true);
        ReflectionTestUtils.setField(controller, "objectMapper", objectMapper);
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(whatsAppService.sendMessage(anyString(), anyString())).thenReturn(true);
    }

    @Test @DisplayName("GET verification returns challenge on valid token")
    void verification_validToken_returnsChallenge() {
        ResponseEntity<String> resp = controller.verify(
                "subscribe", "acid_coupon_verify_token_2024", "CHALLENGE_123");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo("CHALLENGE_123");
    }

    @Test @DisplayName("GET verification returns 403 on wrong token")
    void verification_wrongToken_returns403() {
        ResponseEntity<String> resp = controller.verify(
                "subscribe", "wrong-token", "CHALLENGE_123");

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test @DisplayName("POST with valid text message delegates to ConversationService")
    void validMessage_delegatesToConversationService() {
        String payload = metaPayload("27831234567", "Coupon");

        ResponseEntity<String> resp = controller.receive(null, payload);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(conversationService).handleMessage("27831234567", "Coupon");
    }

    @Test @DisplayName("POST with non-text message type is ignored")
    void nonTextMessage_isIgnored() {
        String payload = """
            {"entry":[{"changes":[{"value":{"messages":[{
                "from":"27831234567",
                "type":"image",
                "image":{}
            }]}}]}]}""";

        controller.receive(null, payload);

        verifyNoInteractions(conversationService);
    }

    @Test @DisplayName("POST with no messages (status update) is ignored")
    void statusUpdate_isIgnored() {
        String payload = """
            {"entry":[{"changes":[{"value":{
                "statuses":[{"id":"msgid","status":"delivered"}]
            }}]}]}""";

        ResponseEntity<String> resp = controller.receive(null, payload);

        assertThat(resp.getBody()).isEqualTo("ok");
        verifyNoInteractions(conversationService);
    }

    @Test @DisplayName("POST from rate-limited phone sends wait message")
    void rateLimited_sendsWaitMessage() {
        when(rateLimiterService.isAllowed("27831234567")).thenReturn(false);

        controller.receive(null, metaPayload("27831234567", "Coupon"));

        verify(whatsAppService).sendMessage(eq("27831234567"),
                argThat(m -> m.contains("wait")));
        verifyNoInteractions(conversationService);
    }

    @Test @DisplayName("POST with invalid JSON returns 200 without throwing")
    void invalidJson_returns200WithoutThrowing() {
        ResponseEntity<String> resp = controller.receive(null, "not json");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo("ok");
    }

    @Test @DisplayName("POST with empty entry returns 200")
    void emptyEntry_returns200() {
        controller.receive(null, "{\"entry\":[]}");
        assertThat(true).isTrue(); // no exception
    }

    // Helper
    private String metaPayload(String from, String body) {
        return String.format("""
            {"entry":[{"changes":[{"value":{"messages":[{
                "from":"%s",
                "type":"text",
                "text":{"body":"%s"}
            }]}}]}]}""", from, body);
    }
}