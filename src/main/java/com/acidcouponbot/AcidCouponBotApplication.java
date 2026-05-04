package com.acidcouponbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AcidCouponBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcidCouponBotApplication.class, args);
        System.out.println("""
                ╔══════════════════════════════════════════════════════╗
                ║   AcidCouponBot v2 — Randgo Integration              ║
                ║   Admin  : http://localhost:8080                     ║
                ║   H2     : http://localhost:8080/h2-console          ║
                ║   Webhook: http://localhost:8080/webhook             ║
                ║   Health : http://localhost:8080/api/health          ║
                ╚══════════════════════════════════════════════════════╝
                """);
    }
}
