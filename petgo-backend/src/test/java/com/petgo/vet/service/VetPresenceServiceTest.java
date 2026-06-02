package com.petgo.vet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.vet.domain.VetPresenceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * L0 单元测试（无真实 Redis，mock ZSetOperations）：在线/离线读写、TTL 窗口判定、anyOnline 只回 bool。
 */
@ExtendWith(MockitoExtension.class)
class VetPresenceServiceTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ZSetOperations<String, String> zset;

    private VetPresenceService service() {
        when(redis.opsForZSet()).thenReturn(zset);
        return new VetPresenceService(redis);
    }

    @Test
    void goOnlineAddsMemberWithLastSeenScore() {
        service().goOnline(7L);
        verify(zset).add(eq(VetPresenceService.ONLINE_ZSET), eq("7"), anyDouble());
    }

    @Test
    void goOfflineRemovesMember() {
        service().goOffline(7L);
        verify(zset).remove(VetPresenceService.ONLINE_ZSET, "7");
    }

    @Test
    void isOnlineTrueWhenScoreWithinWindow() {
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7"))
                .thenReturn((double) System.currentTimeMillis());
        assertThat(service().isOnline(7L)).isTrue();
        assertThat(service().statusOf(7L)).isEqualTo(VetPresenceStatus.ONLINE);
    }

    @Test
    void isOnlineFalseWhenScoreStale() {
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7"))
                .thenReturn((double) (System.currentTimeMillis() - VetPresenceService.TTL.toMillis() - 1000));
        assertThat(service().isOnline(7L)).isFalse();
        assertThat(service().statusOf(7L)).isEqualTo(VetPresenceStatus.OFFLINE);
    }

    @Test
    void isOnlineFalseWhenAbsent() {
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7")).thenReturn(null);
        assertThat(service().isOnline(7L)).isFalse();
    }

    @Test
    void anyOnlinePrunesStaleThenCountsLiveMembers() {
        when(zset.count(eq(VetPresenceService.ONLINE_ZSET), anyDouble(), eq(Double.POSITIVE_INFINITY)))
                .thenReturn(2L);
        assertThat(service().anyOnline()).isTrue();
        // 惰性清理过期成员
        verify(zset).removeRangeByScore(eq(VetPresenceService.ONLINE_ZSET), eq(Double.NEGATIVE_INFINITY), anyDouble());
    }

    @Test
    void anyOnlineFalseWhenNoLiveMembers() {
        when(zset.count(eq(VetPresenceService.ONLINE_ZSET), anyDouble(), eq(Double.POSITIVE_INFINITY)))
                .thenReturn(0L);
        assertThat(service().anyOnline()).isFalse();
    }
}
