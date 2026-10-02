package com.analytics.platform.query;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analytics.platform.query.QueryResults.CountResult;
import com.analytics.platform.support.TestProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class QueryCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SimpleMeterRegistry meters;
    private QueryCache cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        meters = new SimpleMeterRegistry();
        cache = new QueryCache(redis, new ObjectMapper(), TestProps.defaults(), meters);
    }

    @Test
    void keyAlwaysStartsWithTheTenant() {
        String key = QueryCache.key("tenant-a", "count", "1|2|");

        assertTrue(key.startsWith("tenant:tenant-a:q:count:"));
        assertNotEquals(key, QueryCache.key("tenant-b", "count", "1|2|"));
    }

    @Test
    void missLoadsAndStoresThenHitSkipsTheLoader() throws Exception {
        AtomicInteger loads = new AtomicInteger();

        when(ops.get(anyString())).thenReturn(null);
        CountResult first = cache.getOrLoad("tenant-a", "count", "fp", CountResult.class,
                () -> new CountResult(loads.incrementAndGet()));
        assertEquals(1, first.count());
        verify(ops).set(anyString(), eq("{\"count\":1}"), any(Duration.class));

        when(ops.get(anyString())).thenReturn("{\"count\":7}");
        CountResult second = cache.getOrLoad("tenant-a", "count", "fp", CountResult.class,
                () -> new CountResult(loads.incrementAndGet()));

        assertEquals(7, second.count());
        assertEquals(1, loads.get());
        assertEquals(1.0, meters.counter("analytics.cache", "result", "hit").count());
        assertEquals(1.0, meters.counter("analytics.cache", "result", "miss").count());
    }

    @Test
    void redisFailureFallsBackToTheLoaderWithoutThrowing() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        CountResult result = cache.getOrLoad("tenant-a", "count", "fp", CountResult.class, () -> new CountResult(42));

        assertEquals(42, result.count());
        assertEquals(1.0, meters.counter("analytics.cache", "result", "error").count());
    }

    @Test
    void redisWriteFailureDoesNotFailTheRequest() {
        when(ops.get(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        CountResult result = cache.getOrLoad("tenant-a", "count", "fp", CountResult.class, () -> new CountResult(5));

        assertEquals(5, result.count());
    }
}
