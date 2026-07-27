package com.acidcouponbot.controller;

import com.acidcouponbot.randgo.RandgoMemberService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * POST /internal/member/ensure — JIT RandGo member provisioning for the gateway SSO flow.
 * Pins: the X-Internal-Api-Key gate (fail-closed), delegation to the idempotent+fail-open
 * RandgoMemberService, and that the endpoint NEVER surfaces a 5xx to the SSO caller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalMemberController.ensureMember")
class InternalMemberControllerTest {

    private static final String API_KEY = "shared-internal-key";
    private static final String PHONE   = "27831234567";

    @Mock RandgoMemberService randgoMemberService;
    @InjectMocks InternalMemberController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "internalApiKey", API_KEY);
    }

    @Test
    @DisplayName("valid key + phone → 200 ensured=true, delegates to ensureMemberRegistered")
    void valid_ensuresMember() {
        when(randgoMemberService.ensureMemberRegistered(PHONE)).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", true);
        verify(randgoMemberService).ensureMemberRegistered(PHONE);
    }

    @Test
    @DisplayName("import not confirmed (ensureMemberRegistered=false) → still 200, ensured=false (fail-open)")
    void notConfirmed_still200() {
        when(randgoMemberService.ensureMemberRegistered(PHONE)).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", false);
    }

    @Test
    @DisplayName("service throws → swallowed, 200 ensured=false (never a 5xx to the SSO caller)")
    void serviceThrows_never5xx() {
        when(randgoMemberService.ensureMemberRegistered(PHONE))
                .thenThrow(new RuntimeException("RandGo down"));

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", false);
    }

    @Test
    @DisplayName("bad key → 401, never touches RandGo")
    void badKey_unauthorized() {
        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember("wrong-key", Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(randgoMemberService, never()).ensureMemberRegistered(anyString());
    }

    @Test
    @DisplayName("missing key → 401")
    void missingKey_unauthorized() {
        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(null, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(randgoMemberService, never()).ensureMemberRegistered(anyString());
    }

    @Test
    @DisplayName("blank configured key rejects even a blank incoming key (fail-closed)")
    void blankConfiguredKey_failsClosed() {
        ReflectionTestUtils.setField(controller, "internalApiKey", "");

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember("", Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(randgoMemberService, never()).ensureMemberRegistered(anyString());
    }

    @Test
    @DisplayName("valid key but missing phone → 400, never touches RandGo")
    void missingPhone_badRequest() {
        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(randgoMemberService, never()).ensureMemberRegistered(anyString());
    }
}
