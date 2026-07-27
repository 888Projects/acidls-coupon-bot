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
    @DisplayName("ALREADY_REGISTERED → 200 status=ALREADY_REGISTERED ensured=true, delegates to ensureForSso")
    void valid_ensuresMember() {
        when(randgoMemberService.ensureForSso(PHONE))
                .thenReturn(RandgoMemberService.EnsureStatus.ALREADY_REGISTERED);

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", true);
        assertThat(resp.getBody()).containsEntry("status", "ALREADY_REGISTERED");
        verify(randgoMemberService).ensureForSso(PHONE);
    }

    @Test
    @DisplayName("IMPORTED → 200 status=IMPORTED ensured=true")
    void imported_ready() {
        when(randgoMemberService.ensureForSso(PHONE))
                .thenReturn(RandgoMemberService.EnsureStatus.IMPORTED);

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", true);
        assertThat(resp.getBody()).containsEntry("status", "IMPORTED");
    }

    @Test
    @DisplayName("IMPORT_PENDING → still 200, status=IMPORT_PENDING ensured=false (gateway asks retry)")
    void pending_still200() {
        when(randgoMemberService.ensureForSso(PHONE))
                .thenReturn(RandgoMemberService.EnsureStatus.IMPORT_PENDING);

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", false);
        assertThat(resp.getBody()).containsEntry("status", "IMPORT_PENDING");
    }

    @Test
    @DisplayName("service throws → swallowed, 200 status=FAILED ensured=false (never a 5xx to the SSO caller)")
    void serviceThrows_never5xx() {
        when(randgoMemberService.ensureForSso(PHONE))
                .thenThrow(new RuntimeException("RandGo down"));

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ensured", false);
        assertThat(resp.getBody()).containsEntry("status", "FAILED");
    }

    @Test
    @DisplayName("bad key → 401, never touches RandGo")
    void badKey_unauthorized() {
        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember("wrong-key", Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(randgoMemberService, never()).ensureForSso(anyString());
    }

    @Test
    @DisplayName("missing key → 401")
    void missingKey_unauthorized() {
        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(null, Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(randgoMemberService, never()).ensureForSso(anyString());
    }

    @Test
    @DisplayName("blank configured key rejects even a blank incoming key (fail-closed)")
    void blankConfiguredKey_failsClosed() {
        ReflectionTestUtils.setField(controller, "internalApiKey", "");

        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember("", Map.of("phone", PHONE));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(randgoMemberService, never()).ensureForSso(anyString());
    }

    @Test
    @DisplayName("valid key but missing phone → 400, never touches RandGo")
    void missingPhone_badRequest() {
        ResponseEntity<Map<String, Object>> resp =
                controller.ensureMember(API_KEY, Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(randgoMemberService, never()).ensureForSso(anyString());
    }
}
