package com.acidcouponbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * Supports two authentication methods:
 *   1. Form login  — browser dashboard (login.html)
 *   2. HTTP Basic  — Postman / API clients
 *
 * Public endpoints:
 *   /webhook/**   — Meta WhatsApp webhook (signature-verified internally)
 *   /api/health   — uptime monitoring
 *   /h2-console   — H2 browser (dev profile only)
 *   Static assets — login.html, *.css, *.js, favicon
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:AcidAdmin@2024}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                "/webhook/**",
                "/api/**",
                "/h2-console/**",
                "/login"
            ))
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/webhook/**").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers(
                    "/login.html", "/login", "/error",
                    "/favicon.ico", "/*.css", "/*.js",
                    "/*.ico", "/*.png", "/*.svg"
                ).permitAll()
                .anyRequest().authenticated()
            )
            // Form login — used by the browser dashboard
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/index.html", true)
                .failureUrl("/login.html?error=true")
                .usernameParameter("username")
                .passwordParameter("password")
                .permitAll()
            )
            // HTTP Basic — used by Postman and API clients
            // Allows Authorization: Basic base64(admin:password) on /api/** requests
            .httpBasic(basic -> basic
                .realmName("AcidCouponBot Admin API")
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login.html?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(
            User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build()
        );
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
