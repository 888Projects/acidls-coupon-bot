package com.acidcouponbot.controller;

import com.acidcouponbot.service.ConversationService;
import com.acidcouponbot.service.RateLimiterService;
import com.acidcouponbot.service.WhatsAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    @Value("${whatsapp.webhook.verify.token:acid_coupon_verify_token_2024}")
    private String verifyToken;

    @Value("${whatsapp.app.secret:YOUR_APP_SECRET}")
    private String appSecret;

    @Value("${app.dev.mode:true}")
    private boolean devMode;

    private final ConversationService conversationService;
    private final WhatsAppService     whatsAppService;
    private final RateLimiterService  rateLimiterService;
    private final ObjectMapper        objectMapper;

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode")         String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge")    String challenge) {
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("✅ Webhook verified");
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String sig,
            @RequestBody String rawBody) {

        if (!devMode && !verifySignature(sig, rawBody)) {
            log.warn("❌ Invalid webhook signature");
            return ResponseEntity.status(403).body("Invalid signature");
        }

        try {
            JsonNode payload  = objectMapper.readTree(rawBody);
            JsonNode entry    = payload.path("entry").get(0);
            if (entry == null) return ResponseEntity.ok("ok");

            JsonNode value    = entry.path("changes").get(0).path("value");
            JsonNode messages = value.path("messages");
            if (messages.isMissingNode() || !messages.isArray() || messages.isEmpty())
                return ResponseEntity.ok("ok");

            JsonNode message = messages.get(0);
            if (!"text".equals(message.path("type").asText()))
                return ResponseEntity.ok("ok");

            String phone = message.path("from").asText().trim();
            String body  = message.path("text").path("body").asText().trim();
            log.info("📨 Message from +{}: '{}'", phone, body);

            if (!rateLimiterService.isAllowed(phone)) {
                log.warn("🚫 Rate limit exceeded for +{}", phone);
                whatsAppService.sendMessage(phone, "⏳ Please wait a moment before trying again.");
                return ResponseEntity.ok("ok");
            }

            conversationService.handleMessage(phone, body);

        } catch (Exception e) {
            log.error("Webhook error: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok("ok");
    }

    private boolean verifySignature(String signature, String body) {
        if (signature == null || signature.isBlank()) return false;
        if ("YOUR_APP_SECRET".equals(appSecret))       return true;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return expected.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }
}