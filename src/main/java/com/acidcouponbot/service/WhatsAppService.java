package com.acidcouponbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Service
@Slf4j
public class WhatsAppService {

    @Value("${whatsapp.api.url:https://graph.facebook.com/v18.0}")
    private String apiUrl;

    @Value("${whatsapp.phone.number.id:YOUR_PHONE_NUMBER_ID}")
    private String phoneNumberId;

    @Value("${whatsapp.access.token:YOUR_ACCESS_TOKEN}")
    private String accessToken;

    @Value("${app.dev.mode:true}")
    private boolean devMode;

    private final WebClient webClient;

    @Autowired
    public WhatsAppService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    /**
     * Sends a WhatsApp message.
     * Returns true if sent successfully.
     * In dev mode: logs to console instead of calling Meta API.
     */
    public boolean sendMessage(String toPhone, String message) {
        if (devMode || "YOUR_PHONE_NUMBER_ID".equals(phoneNumberId)) {
            log.info("""

                    ╔══════════════════════════════════════════════════════╗
                    ║  DEV MODE — WhatsApp Message Preview                 ║
                    ║  To: +{}
                    ╠══════════════════════════════════════════════════════╣
                    {}
                    ╚══════════════════════════════════════════════════════╝
                    """, toPhone, message);
            return true;
        }

        try {
            webClient.post()
                .uri(apiUrl + "/" + phoneNumberId + "/messages")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                    "messaging_product", "whatsapp",
                    "recipient_type", "individual",
                    "to", toPhone,
                    "type", "text",
                    "text", Map.of("preview_url", false, "body", message)
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            log.info("✅ WhatsApp message sent to +{}", toPhone);
            return true;

        } catch (WebClientResponseException e) {
            log.error("❌ WhatsApp API error to +{}: {} — {}",
                    toPhone, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("❌ WhatsApp send failed to +{}: {}", toPhone, e.getMessage());
            return false;
        }
    }
}
