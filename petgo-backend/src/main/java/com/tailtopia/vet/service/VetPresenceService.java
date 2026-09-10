package com.tailtopia.vet.service;

import com.tailtopia.vet.domain.VetPresenceStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 兽医 Redis 在线态（Story 5.2，FR-32）。Redis 收窄用途之一：只存在线态，绝不塞业务对象。
 *
 * <p>数据结构（单一 sorted-set）：
 * <pre>
 *   ZSET vet:online  member=vetId  score=lastSeen(epochMillis)
 * </pre>
 * <b>在线态纯显式（bug 20260702-216）</b>：成员在集合内即「在线」，与 lastSeen 新旧无关。一旦兽医
 * 显式上线（{@link #goOnline}），保持在线直到显式离线/登出/封禁（{@link #goOffline}）——退后台、掉线、
 * 杀 App 都不改变在线态（来单靠推送触达）。<b>不设 TTL、不做心跳兜底、不惰性过期</b>；score 仅供后台
 * 展示「最后活跃」。这是产品决策：完全信赖兽医手动选择，不加可达性兜底（Redis 重启清空可接受，重上线即恢复）。
 *
 * <p>{@code BUSY} 子态（进行中会话占用）由 Story 5.5 接：ONLINE ↔ BUSY 显式切换，不影响在线存活。
 * <b>不引入 Redisson 等额外中间件</b>——用 Spring Data Redis 原生 ZSet 操作（架构护栏）。
 */
@Service
public class VetPresenceService {

    /** 在线集合键（ZSET，score=lastSeen 仅供展示）。成员存在 = 在线。 */
    static final String ONLINE_ZSET = "vet:online";
    /** 忙碌集合键（SET，进行中会话占用，Story 5.5）。 */
    static final String BUSY_SET = "vet:busy";

    private final StringRedisTemplate redis;

    public VetPresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 显式上线：加入在线集合（score=lastSeen）。此后保持在线直到显式离线，不随心跳/前后台自动离线。 */
    public void goOnline(long vetId) {
        redis.opsForZSet().add(ONLINE_ZSET, String.valueOf(vetId), now());
    }

    /** 心跳：仅刷新 lastSeen（后台「最后活跃」展示），不影响在线判定（在线态纯显式，不靠心跳续命）。 */
    public void heartbeat(long vetId) {
        refreshIfOnline(vetId);
    }

    /**
     * 活动续期：兽医<b>已在线</b>时刷新 lastSeen（后台展示更准）；<b>已离线不复活</b>——只有显式 {@link #goOnline}
     * 才能上线。在线存活不再依赖此调用（保留仅为让后台「最后活跃」跟随工作台活动，而非拉起在线态）。
     */
    public void refreshIfOnline(long vetId) {
        if (isOnline(vetId)) {
            redis.opsForZSet().add(ONLINE_ZSET, String.valueOf(vetId), now());
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

    /** 某兽医是否在线：在在线集合内即在线（显式态，无 TTL 过期）。 */
    public boolean isOnline(long vetId) {
        Double score = redis.opsForZSet().score(ONLINE_ZSET, String.valueOf(vetId));
        return score != null;
    }

    /**
     * 兽医最后活跃（lastSeen = score）时刻；离线/已移出在线集合 → 空（Bug 20260701-168 后台展示用）。
     * 注：score 是最后活跃时间（上线/心跳/活动续期覆盖），非「上线起始」；离线兽医已被移除故无值。
     */
    public Optional<Instant> lastSeenAt(long vetId) {
        Double score = redis.opsForZSet().score(ONLINE_ZSET, String.valueOf(vetId));
        return score == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(score.longValue()));
    }

    /**
     * 全部在线兽医的 lastSeen（V1.3.0 Story 9.1a：兽医列表的「最后在线」列）。
     *
     * <p>🔴 **一次 ZRANGE 取全集**，不是逐行 ZSCORE：这一列要跟着列表的每一行渲染，
     * 逐行查等于把一次列表渲染变成 N 次 Redis 往返。在线集合的量级 = 当前在线兽医数（几十），
     * 整取一次比 N 次单查便宜得多。
     *
     * <p>不在返回的 Map 里 = 不在线（ZSET 里已被移除）→ 调用方显示「—」，与
     * {@link #lastSeenAt} 的口径一致。
     */
    public java.util.Map<Long, Instant> lastSeenAll() {
        var tuples = redis.opsForZSet().rangeWithScores(ONLINE_ZSET, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, Instant> out = new java.util.HashMap<>(tuples.size());
        for (var t : tuples) {
            String member = t.getValue();
            Double score = t.getScore();
            if (member == null || score == null) {
                continue;
            }
            try {
                out.put(Long.parseLong(member), Instant.ofEpochMilli(score.longValue()));
            } catch (NumberFormatException e) {
                // 集合里混进了非法成员（历史脏数据）：跳过而不是让整张列表崩掉。
            }
        }
        return out;
    }

    /**
     * 全部忙碌中兽医的 id（V1.3.0 Story 9.1a：与 {@link #lastSeenAll()} 配套，供列表一次取数）。
     *
     * <p>🔴 一次 {@code SMEMBERS}，不是逐行 {@code SISMEMBER}：与在线集合合起来，
     * 一张列表的在线态从「每行 2 次 Redis 往返」降到**两次**。
     */
    public java.util.Set<Long> busyAll() {
        var members = redis.opsForSet().members(BUSY_SET);
        if (members == null || members.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Long> out = new java.util.HashSet<>(members.size());
        for (String m : members) {
            try {
                out.add(Long.parseLong(m));
            } catch (NumberFormatException e) {
                // 历史脏数据：跳过而不是让整张列表崩掉（与 lastSeenAll 同一处理）。
            }
        }
        return out;
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
     */
    public boolean anyOnline() {
        Long size = redis.opsForZSet().zCard(ONLINE_ZSET);
        return size != null && size > 0;
    }

    /** 当前在线兽医 id 列表（5.3 队列匹配用；不对用户侧透传）。 */
    public List<Long> onlineVetIds() {
        Set<String> members = redis.opsForZSet().range(ONLINE_ZSET, 0, -1);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::parseLong).toList();
    }

    private static double now() {
        return System.currentTimeMillis();
    }
}
