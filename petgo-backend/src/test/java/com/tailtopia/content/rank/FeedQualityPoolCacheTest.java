package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** L0（修复线 2026-11-12）：优质老帖候选 id 全站共用一份，Redis 存 10 分钟；参数变了即换键重算。 */
class FeedQualityPoolCacheTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ContentPostRepository posts;
    private FeedQualityPoolCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        posts = mock(ContentPostRepository.class);
        cache = new FeedQualityPoolCache(redis, posts);
    }

    @Test
    void missQueriesOnceAndStoresWithTtlUnderAParamKeyedKey() {
        when(posts.findQualityCandidateIds(any(), anyDouble(), anyInt())).thenReturn(List.of(7L, 3L));

        assertThat(cache.topIds(30, 300, 1.2, NOW)).containsExactly(7L, 3L);

        verify(posts).findQualityCandidateIds(eq(NOW.minus(java.time.Duration.ofDays(30))), eq(1.2), eq(300));
        verify(values).set("feed:quality:30:300:1.2", "7,3", FeedQualityPoolCache.TTL);
    }

    @Test
    void hitReturnsStoredIdsWithoutQuerying() {
        when(values.get("feed:quality:30:300:1.2")).thenReturn("9,8");

        assertThat(cache.topIds(30, 300, 1.2, NOW)).containsExactly(9L, 8L);
        verify(posts, never()).findQualityCandidateIds(any(), anyDouble(), anyInt());
    }

    @Test
    void emptyResultIsStoredToo() {
        when(posts.findQualityCandidateIds(any(), anyDouble(), anyInt())).thenReturn(List.of());
        assertThat(cache.topIds(30, 300, 1.2, NOW)).isEmpty();
        verify(values).set("feed:quality:30:300:1.2", "", FeedQualityPoolCache.TTL);

        when(values.get(anyString())).thenReturn("");
        assertThat(cache.topIds(30, 300, 1.2, NOW)).isEmpty();
    }

    @Test
    void sizeOrDaysZeroIsOffAndTouchesNothing() {
        assertThat(cache.topIds(30, 0, 1.2, NOW)).isEmpty();
        assertThat(cache.topIds(0, 300, 1.2, NOW)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(posts, values);
    }

    @Test
    void redisDownFallsBackToTheQuery() {
        when(values.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(posts.findQualityCandidateIds(any(), anyDouble(), anyInt())).thenReturn(List.of(5L));

        assertThat(cache.topIds(30, 300, 1.2, NOW)).containsExactly(5L);
    }
}
