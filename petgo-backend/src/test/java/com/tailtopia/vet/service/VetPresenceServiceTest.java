package com.tailtopia.vet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.vet.domain.VetPresenceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * L0 单元测试（无真实 Redis，mock ZSetOperations）：在线/离线读写、显式态判定（在集合内即在线，
 * 无 TTL 过期，bug 20260702-216）、anyOnline 只回 bool。
 */
@ExtendWith(MockitoExtension.class)
class VetPresenceServiceTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ZSetOperations<String, String> zset;
    @Mock
    SetOperations<String, String> setOps;

    private VetPresenceService service() {
        org.mockito.Mockito.lenient().when(redis.opsForZSet()).thenReturn(zset);
        return new VetPresenceService(redis);
    }

    /** statusOf 在线时会查忙碌集合，默认 stub 为非忙碌。 */
    private void stubNotBusy() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(VetPresenceService.BUSY_SET, "7")).thenReturn(false);
    }

    @Test
    void goOnlineAddsMemberWithLastSeenScore() {
        service().goOnline(7L);
        verify(zset).add(eq(VetPresenceService.ONLINE_ZSET), eq("7"), anyDouble());
    }

    @Test
    void goOfflineRemovesMemberAndClearsBusy() {
        when(redis.opsForSet()).thenReturn(setOps);
        service().goOffline(7L);
        verify(zset).remove(VetPresenceService.ONLINE_ZSET, "7");
        verify(setOps).remove(VetPresenceService.BUSY_SET, "7");
    }

    @Test
    void goBusyAndGoAvailableToggleBusySet() {
        when(redis.opsForSet()).thenReturn(setOps);
        VetPresenceService svc = service();
        svc.goBusy(7L);
        verify(setOps).add(VetPresenceService.BUSY_SET, "7");
        svc.goAvailable(7L);
        verify(setOps).remove(VetPresenceService.BUSY_SET, "7");
    }

    @Test
    void isOnlineTrueWhenMemberPresent() {
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7"))
                .thenReturn((double) System.currentTimeMillis());
        stubNotBusy();
        assertThat(service().isOnline(7L)).isTrue();
        assertThat(service().statusOf(7L)).isEqualTo(VetPresenceStatus.ONLINE);
    }

    @Test
    void isOnlineStaysTrueEvenWhenLastSeenOld() {
        // 显式态（bug 20260702-216）：只要成员在集合内即在线，lastSeen 再老也不自动离线。
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7"))
                .thenReturn((double) (System.currentTimeMillis() - 86_400_000L)); // 一天前
        stubNotBusy();
        assertThat(service().isOnline(7L)).isTrue();
        assertThat(service().statusOf(7L)).isEqualTo(VetPresenceStatus.ONLINE);
    }

    @Test
    void statusBusyWhenOnlineAndInBusySet() {
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7"))
                .thenReturn((double) System.currentTimeMillis());
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(VetPresenceService.BUSY_SET, "7")).thenReturn(true);
        assertThat(service().statusOf(7L)).isEqualTo(VetPresenceStatus.BUSY);
    }

    @Test
    void isOnlineFalseWhenAbsent() {
        when(zset.score(VetPresenceService.ONLINE_ZSET, "7")).thenReturn(null);
        assertThat(service().isOnline(7L)).isFalse();
    }

    @Test
    void anyOnlineTrueWhenMembersPresent() {
        when(zset.zCard(VetPresenceService.ONLINE_ZSET)).thenReturn(2L);
        assertThat(service().anyOnline()).isTrue();
    }

    @Test
    void anyOnlineFalseWhenNoMembers() {
        when(zset.zCard(VetPresenceService.ONLINE_ZSET)).thenReturn(0L);
        assertThat(service().anyOnline()).isFalse();
    }
}
