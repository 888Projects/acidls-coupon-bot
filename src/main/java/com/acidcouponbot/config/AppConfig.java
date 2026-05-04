package com.acidcouponbot.config;

import com.acidcouponbot.model.Campaign;
import com.acidcouponbot.randgo.RandgoMemberService;
import com.acidcouponbot.randgo.RandgoSyncService;
import com.acidcouponbot.repository.CampaignRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Arrays;

@Configuration
public class AppConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * FIX: Register JavaTimeModule so LocalDateTime fields
     * (lastSynced, cancelDate, expiresAt etc.) serialize correctly
     * to JSON in API responses. Without this registration the
     * /api/vouchers and /api/stats endpoints throw:
     *   "Java 8 date/time type not supported by default"
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}

/**
 * Dev seed data — dev profile only.
 * Double-guarded: @Profile("dev") + runtime profile check.
 * DELETE before production deployment.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
class DataSeeder implements CommandLineRunner {

    private final CampaignRepository campaignRepository;
    private final RandgoSyncService randgoSyncService;
    private final RandgoMemberService randgoMemberService;
    private final Environment environment;

    @Override
    public void run(String... args) {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean isDevProfile = Arrays.asList(activeProfiles).contains("dev")
                || Arrays.asList(activeProfiles).contains("test");

        if (!isDevProfile) {
            log.error("⛔ DataSeeder ABORTED — non-dev profile: {}",
                    Arrays.toString(activeProfiles));
            return;
        }

        if (campaignRepository.existsByActiveTrue()) {
            log.info("Dev data already seeded — skipping");
            return;
        }

        log.info("Seeding dev data...");

        Campaign campaign = campaignRepository.save(Campaign.builder()
                .name("Shoprite Grocery Promo — " + LocalDateTime.now().getMonth())
                .description("Exclusive grocery coupons. Scan QR in-store to claim!")
                .retailerName("Shoprite")
                .retailerEmoji("🛒")
                .bundleSize(5)
                .active(true)
                .expiryDate(LocalDateTime.now().plusMonths(3))
                .lowStockThreshold(10)
                .build());

        RandgoSyncService.VoucherSyncResult vr = randgoSyncService.syncVouchers();
        log.info("Vouchers: {}", vr.message());

        RandgoSyncService.CodeSyncResult cr = randgoSyncService.syncCodesCache();
        log.info("Fallback codes: {}", cr.message());

        randgoMemberService.markAsRegistered("27831234567");
        randgoMemberService.markAsRegistered("27839999999");

        log.info("""

                ╔══════════════════════════════════════════════════════╗
                ║  AcidCouponBot — Dev Seed Complete                   ║
                ║  Campaign : {}
                ║  Vouchers : {} cached                                ║
                ║  Fallback : {} codes                                 ║
                ║  Login    : admin / AcidAdmin@2024                   ║
                ║  H2       : http://localhost:8080/h2-console         ║
                ╚══════════════════════════════════════════════════════╝
                """,
                campaign.getName(), vr.count(), cr.count());
    }
}
