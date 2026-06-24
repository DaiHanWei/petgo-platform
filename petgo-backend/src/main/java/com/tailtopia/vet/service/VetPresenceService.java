package com.tailtopia.vet.service;

import com.tailtopia.vet.domain.VetPresenceStatus;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 兽医 Redis 在线态（Story 5.2，FR-32）。Redis 收窄用途之一：只存在线态，绝不塞业务对象。
 *
 * <p>数据结构（单一 sorted-set，惰性清理，避免「幽灵在线」）：
 * <pre>
 *   ZSET vet:online  member=vetId  score=lastSeen(epochMillis)
 * </pre>
 * 「在线」= 成员的 lastSeen 在 TTL 窗口（{@link #TTL}）内；过期成员靠 {@link #pruneStale} 惰性移除，
 * 即便兽医不显式点离线（退出/掉线/杀 App），TTL 兜底自动离线。
 *
 * <p>{@code BUSY} 子态（进行中会话占用）由 Story 5.5 接，本故事只读写 ONLINE/OFFLINE。
 * <b>不引入 Redisson 等额外中间件</b>——用 Spring Data Redis 原生 ZSet 操作（架构护栏）。
 */
@Service
public class VetPresenceService {

    /** 在线集合键（ZSET，score=lastSeen）。 */
    static final String ONLINE_ZSET = "vet:online";
    /** 忙碌集合键（SET，进行中会话占用，Story 5.5）。 */
    static final String BUSY_SET = "vet:busy";
    /** 在线态有效窗口：超过此时长未心跳即视为离线（>心跳间隔，留掉线宽限）。 */
    static final Duration TTL = Duration.ofMinutes(3);

    private final StringRedisTemplate redis;

    public VetPresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 上线 / 心跳续期：刷新 lastSeen 为当前时刻。 */
    public void goOnline(long vetId) {
        redis.opsForZSet().add(ONLINE_ZSET, String.valueOf(vetId), now());
    }

    /** 心跳续期（语义同 goOnline，命名区分调用意图）。 */
    public void heartbeat(long vetId) {
        goOnline(vetId);
    }

    /**
     * 活动续期（兜底）：兽医<b>已在线</b>时，凭工作台任意请求续期在线 TTL；<b>已离线则不复活</b>
     * （显式 goOffline / 退后台停轮询后不被活动重新拉起）。解决「工作台在用但客户端心跳缺失 → 误判离线」
     * （见 ApiAccessLoggingFilter 实证：兽医狂轮询 waiting 却 0 心跳）。
     */
    public void refreshIfOnline(long vetId) {
        if (isOnline(vetId)) {
            goOnline(vetId);
        }
    }

    /** 显式离线 / 登出 / 封禁(5.7) → 移出在线集合 + 清忙碌标记。 */
    public void goOffline(long vetId) {
        redis.opsForZSet().remove(ONLINE_ZSET, String.valueOf(vetId));
        redis.opsForSet().remove(BUSY_SET, String.valueOf(vetId));
    }

    /** 接单占用（Story 5.5）：ONLINE → BUSY（仍在线但不接新请求）。 */
    public void goBusy(long vetId) {
        redis.opsForSet().add(BUSY_SET, String.valueOf(vetId));
    }

    /** 会话结束/中断（5.6/5.7）：BUSY → ONLINE（恢复可接单）。 */
    public void goAvailable(long vetId) {
        redis.opsForSet().remove(BUSY_SET, String.valueOf(vetId));
    }

    public boolean isBusy(long vetId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(BUSY_SET, String.valueOf(vetId)));
    }

    /** 某兽医是否在线（lastSeen 在 TTL 窗口内）。 */
    public boolean isOnline(long vetId) {
        Double score = redis.opsForZSet().score(ONLINE_ZSET, String.valueOf(vetId));
        return score != null && score >= staleThreshold();
    }

    /** 当前在线态：BUSY 优先（占用中），否则 ONLINE/OFFLINE。 */
    public VetPresenceStatus statusOf(long vetId) {
        if (!isOnline(vetId)) {
            return VetPresenceStatus.OFFLINE;
        }
        return isBusy(vetId) ? VetPresenceStatus.BUSY : VetPresenceStatus.ONLINE;
    }

    /**
     * 是否有任意兽医在线（用户侧入口用，<b>只回 bool，不回精确人数</b>——架构：概率性展示）。
     * 顺带惰性清理过期成员。
     */
    public boolean anyOnline() {
        pruneStale();
        Long count = redis.opsForZSet().count(ONLINE_ZSET, staleThreshold(), Double.POSITIVE_INFINITY);
        return count != null && count > 0;
    }

    /** 当前在线兽医 id 列表（5.3 队列匹配用；不对用户侧透传）。 */
    public List<Long> onlineVetIds() {
        pruneStale();
        Set<String> members = redis.opsForZSet()
                .rangeByScore(ONLINE_ZSET, staleThreshold(), Double.POSITIVE_INFINITY);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::parseLong).toList();
    }

    /** 惰性清理：移除 lastSeen 早于阈值的过期成员（防幽灵在线）。 */
    void pruneStale() {
        redis.opsForZSet().removeRangeByScore(ONLINE_ZSET, Double.NEGATIVE_INFINITY, staleThreshold() - 1);
    }

    private double staleThreshold() {
        return now() - TTL.toMillis();
    }

    private static double now() {
        return System.currentTimeMillis();
    }
}
