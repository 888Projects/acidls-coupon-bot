package com.acidcouponbot.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter — prevents phone number spam on the webhook.
 * 3 requests per minute per phone number.
 */
@Service
public class RateLimiterService {

    private static final int MAX_REQUESTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean isAllowed(String phoneNumber) {
        return buckets.computeIfAbsent(phoneNumber, k ->
            Bucket.builder()
                .addLimit(Bandwidth.classic(MAX_REQUESTS, Refill.greedy(MAX_REQUESTS, WINDOW)))
                .build()
        ).tryConsume(1);
    }
}
