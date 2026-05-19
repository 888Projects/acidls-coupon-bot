package com.acidcouponbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * WhatsApp outbound service — backed by Infobip.
 *
 * Production: POST {infobip.base-url}/whatsapp/1/message/text
 * Auth: "App {infobip.api-key}" (NOT Bearer — Infobip uses "App" prefix)
 *
 * Dev mode: logs to console, no real API call.
 * Set COUPON_BOT_DEV_MODE=true in env until Tin (Infobip) disables the old bot.
 *
 * Required env vars (in /opt/acid/coupon-bot/.env):
 *   INFOBIP_BASE_URL            = https://9rjyk4.api.infobip.com
 *   INFOBIP_API_KEY             = a58a0dcae4882fbce0133724d9d026a9-c55a669d-...
 *   INFOBIP_WHATSAPP_SENDER     = 27815898282
 *   COUPON_BOT_DEV_MODE         = true  (flip to false after old bot is disabled by Tin)
 */
@Service
@Slf4j
public class WhatsAppService {

    @Value("${infobip.base-url:https://9rjyk4.api.infobip.com}")
    private String infobipBaseUrl;

    @Value("${infobip.api-key:NOT_SET}")
    private String infobipApiKey;

    @Value("${infobip.whatsapp.sender:27815898282}")
    private String senderNumber;

    @Value("${app.dev.mode:true}")
    private boolean devMode;

    private static final Duration TIMEOUT     = Duration.ofSeconds(10);
    private static final int      MAX_LENGTH  = 4096;

    private final WebClient webClient;

    @Autowired
    public WhatsAppService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    /**
     * Sends a WhatsApp text message via Infobip.
     * Returns true on success, false on any failure.
     * In dev mode: logs to console and returns true without making an API call.
     */
    public boolean sendMessage(String toPhone, String message) {

        if (devMode || "NOT_SET".equals(infobipApiKey)) {
            log.info("""

                    ╔══════════════════════════════════════════════════════╗
                    ║  DEV MODE — WhatsApp Message (Infobip — not sent)    ║
                    ║  To: +{}
                    ╠══════════════════════════════════════════════════════╣
                    {}
                    ╚══════════════════════════════════════════════════════╝
                    """, maskPhone(toPhone), message);
            return true;
        }

        // Truncate if over WhatsApp 4096-char limit
        String body = message.length() > MAX_LENGTH
                ? message.substring(0, MAX_LENGTH - 3) + "..."
                : message;

        Map<String, Object> payload = Map.of(
                "from",    senderNumber,
                "to",      toPhone,
                "content", Map.of("text", body)
        );

        try {
            webClient.post()
                    .uri(infobipBaseUrl + "/whatsapp/1/message/text")
                    .header(HttpHeaders.AUTHORIZATION, "App " + infobipApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();

            log.info("WHATSAPP_SENT to={}", maskPhone(toPhone));
            return true;

        } catch (WebClientResponseException e) {
            log.error("WHATSAPP_INFOBIP_ERROR to={} status={} body={}",
                    maskPhone(toPhone), e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("WHATSAPP_SEND_FAILED to={} error={}", maskPhone(toPhone), e.getMessage());
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }
}