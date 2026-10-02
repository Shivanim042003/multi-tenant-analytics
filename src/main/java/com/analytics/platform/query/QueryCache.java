package com.analytics.platform.query;

import com.analytics.platform.config.AnalyticsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside in front of ClickHouse. Every key starts with "tenant:{tenantId}:", and the tenant id comes from the
 * authenticated tenant, so two tenants can never share an entry.
 *
 * Redis problems never fail a request: errors are counted and the query falls through to ClickHouse.
 * Metric: analytics.cache{result=hit|miss|error}. Hit rate = hit / (hit + miss).
 *
 * Consistency: an entry can be up to ttl-seconds stale (new events become visible only after expiry).
 */
@Component
public class QueryCache {

    private static final Logger log = LoggerFactory.getLogger(QueryCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final boolean enabled;
    private final Duration ttl;

    public QueryCache(StringRedisTemplate redis, ObjectMapper mapper, AnalyticsProperties props, MeterRegistry registry) {
        this.redis = redis;
        this.mapper = mapper;
        this.registry = registry;
        this.enabled = props.cache().enabled();
        this.ttl = Duration.ofSeconds(props.cache().ttlSeconds());
    }

    public <T> T getOrLoad(String tenantId, String queryName, String fingerprint, Class<T> type, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }

        String key = key(tenantId, queryName, fingerprint);
        boolean redisUp = true;
        String cached = null;

        try {
            cached = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            redisUp = false;
            count("error");
            log.warn("Redis read failed, falling back to ClickHouse: {}", e.toString());
        }

        if (cached != null) {
            try {
                T value = mapper.readValue(cached, type);
                count("hit");
                return value;
            } catch (JsonProcessingException e) {
                log.warn("Ignoring unreadable cache entry {}", key);
            }
        }

        if (redisUp) {
            count("miss");
        }

        T value = loader.get();

        if (redisUp) {
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
            } catch (RuntimeException | JsonProcessingException e) {
                count("error");
                log.warn("Redis write failed: {}", e.toString());
            }
        }
        return value;
    }

    static String key(String tenantId, String queryName, String fingerprint) {
        return "tenant:" + tenantId + ":q:" + queryName + ":" + sha256(fingerprint).substring(0, 32);
    }

    private void count(String result) {
        registry.counter("analytics.cache", "result", result).increment();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
