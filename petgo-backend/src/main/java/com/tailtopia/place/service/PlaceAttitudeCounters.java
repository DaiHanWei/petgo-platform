package com.tailtopia.place.service;

import com.tailtopia.place.domain.PlaceCommentAttitude;
import com.tailtopia.place.repository.PlaceCommentRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 场所的推荐 / 不推荐计数（V1.3.0 batch-b1 Story 1.8 · AD-9）。
 *
 * <h2>🔴 这不是"缓存层解禁"（AD-9 §3，三条判据）</h2>
 * <ol>
 *   <li>Redis 是**既有**组件（本就在跑），不是新引入的中间件；</li>
 *   <li>用法是**单个整数计数器 + 可从库完整重算**的派生数据，不是"给查询结果加一层缓存"；</li>
 *   <li>**真值永远在 `place_comments` 表里** —— Redis 整个丢掉也只是慢一次（回算再写回），
 *       不丢任何数据。</li>
 * </ol>
 * ⚠️ 因此**任何"给场所列表整页结果做缓存"的做法都不在本条授权范围内**，
 * 仍受基线「禁通用缓存层」约束。往这个类里加一个"缓存整页 DTO"的方法就是越界。
 *
 * <h2>🔴 计入计数的只有 VISIBLE 的评论</h2>
 * AC5 把第一个触发点写成「评论创建（带态度）」，但**在先发后审之下，创建那一刻评论还是
 * `UNDER_REVIEW`、对他人不可见**（Story 1.7）。创建即 +1 的话，一条尚未过审、甚至最终被拒的
 * 评论会立刻出现在所有人看到的公开计数里 —— 而列表页的这两个数字正是"大家觉得这地方行不行"。
 * 所以自增发生在**评论转可见**那一刻（{@link #onCommentBecameVisible}），
 * 与回算口径（`deleted_at IS NULL AND moderation_status = VISIBLE`）逐字一致。
 * <p>⚠️ 两者必须一致：不一致的表现是「自愈跑完数字变了」—— 谁也说不清哪个是对的。
 *
 * <h2>形态沿用既有未读角标那一套</h2>
 * {@code NotificationCenterService.unreadCount}：读 Redis → 读不到 / 值异常 → 回库重算 → 写回；
 * 减到负数就置 0。
 *
 * <h2>🔴 计数键有 TTL —— 这是"自愈"真正的底</h2>
 * 未读角标那套靠「打开通知中心时按库校准」兜底；场所计数**没有那样一个天然的校准时机**
 * （列表页每次都读，不可能每次都回算）。所以这里用 TTL：
 * <b>每次写都带 {@link #TTL}，键到期即消失 → 下一次读自动回库重算</b>。
 * <p>它兜住的是三类**丢一次增量就永久偏差**的情况（code-review 2026-09-15）：
 * <ul>
 *   <li>Redis 抖动导致某次 {@code INCR} 没写进去；</li>
 *   <li>两个线程同时回算、后写的把先写的盖回去；</li>
 *   <li>任何本文件没想到的第三种。</li>
 * </ul>
 * 代价是每隔 {@link #TTL} 有一次回算查询（一条 group-by，命中索引），
 * 换来的是**偏差有界**：最长 {@link #TTL} 之后一定回到真值。
 * ⚠️ 不要为了"省那一次查询"把 TTL 去掉 —— 去掉之后计数只会越偏越远，而没有任何地方会报错。
 */
@Service
public class PlaceAttitudeCounters {

    private static final Logger log = LoggerFactory.getLogger(PlaceAttitudeCounters.class);

    /** 推荐数 key 前缀。 */
    static final String RECOMMEND_KEY_PREFIX = "place:rec:";
    /** 不推荐数 key 前缀。 */
    static final String NOT_RECOMMEND_KEY_PREFIX = "place:notrec:";

    /**
     * 计数键存活时长 —— 偏差的**上界**（见类注释）。
     *
     * <p>10 分钟：足够让热门场所的绝大多数请求命中 Redis，又短到"丢了一次增量"不会被用户
     * 记住。调它之前想清楚你是在调**偏差能存在多久**，不是在调缓存命中率。
     */
    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final PlaceCommentRepository comments;

    public PlaceAttitudeCounters(StringRedisTemplate redis, PlaceCommentRepository comments) {
        this.redis = redis;
        this.comments = comments;
    }

    /** 一个场所的两个计数。 */
    public record Counts(long recommend, long notRecommend) {
        /** 一条都没有（新场所 / 取不到时的兜底）。 */
        public static final Counts ZERO = new Counts(0, 0);
    }

    /**
     * 一批场所的计数（AC6）。
     *
     * <h2>🔴 一次批量命令取回，**绝不逐个场所 GET**</h2>
     * 列表页一页十几个场所，逐个 `GET` 就是把 N+1 从数据库搬到了 Redis —— 延迟照样叠加，
     * 只是换了个地方发生。这里用 `MGET` 一次把 2N 个 key 取回来。
     *
     * <p>取不到 / 值异常的那些场所**一起**回库重算（一条 group-by 查询，不是每个一条），
     * 再一次性写回。
     */
    public Map<Long, Counts> countsOf(List<Long> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Map.of();
        }
        // key 顺序与 placeIds 严格对应：前 N 个是推荐，后 N 个是不推荐。
        List<String> keys = new ArrayList<>(placeIds.size() * 2);
        for (Long id : placeIds) {
            keys.add(RECOMMEND_KEY_PREFIX + id);
        }
        for (Long id : placeIds) {
            keys.add(NOT_RECOMMEND_KEY_PREFIX + id);
        }

        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            // 🔴 Redis 挂了不能让场所列表打不开：整批回落到回算（慢一次，但页面是好的）。
            log.warn("场所计数读 Redis 失败，整批回落 DB 回算：{}", e.getClass().getSimpleName());
            values = null;
        }

        Map<Long, Counts> result = new LinkedHashMap<>();
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < placeIds.size(); i++) {
            Long id = placeIds.get(i);
            Long rec = parse(values == null ? null : values.get(i));
            Long not = parse(values == null ? null : values.get(placeIds.size() + i));
            if (rec == null || not == null) {
                // 🔴 两个键**任意一个**缺就整对重算：只补一个会让 👍 是新的、👎 是旧的。
                missing.add(id);
            } else {
                result.put(id, new Counts(rec, not));
            }
        }
        if (!missing.isEmpty()) {
            result.putAll(recomputeAndStore(missing));
        }
        return result;
    }

    /** 单个场所（详情页）。内部就是 {@link #countsOf} 的一元调用，不另写一套读逻辑。 */
    /**
     * 丢弃这些场所的计数键，下一次读自动回库重算（2026-09-18 场所表对齐）。
     *
     * <p>用在**不经过本类增减**的写路径之后：后台合并场所（子表整体改指保留场所）、后台删评论。
     * 不丢的话最长偏 {@link #TTL}；丢了是一次回算查询，很便宜。
     * 在事务里调用时推迟到提交之后（回滚了就不该丢）；Redis 不可用只记日志。
     */
    public void evictAfterCommit(long... placeIds) {
        afterCommit(() -> {
            try {
                List<String> keys = new ArrayList<>(placeIds.length * 2);
                for (long id : placeIds) {
                    keys.add(RECOMMEND_KEY_PREFIX + id);
                    keys.add(NOT_RECOMMEND_KEY_PREFIX + id);
                }
                redis.delete(keys);
            } catch (RuntimeException e) {
                log.warn("场所计数键清理失败（TTL 到期会自愈）：{}", e.getClass().getSimpleName());
            }
        });
    }

    public Counts countsOf(long placeId) {
        return countsOf(List.of(placeId)).getOrDefault(placeId, Counts.ZERO);
    }

    /**
     * 触发点 ①（AC5）：评论**转为可见** → 对应那一侧 +1。
     *
     * <p>未表态（{@code attitude == null}）→ 什么都不做：两个计数都不含它。
     */
    public void onCommentBecameVisible(long placeId, PlaceCommentAttitude attitude) {
        if (attitude == null) {
            return;
        }
        afterCommit(() -> bump(keyOf(placeId, attitude), 1));
    }

    /**
     * 触发点 ② / ③（AC5）：评论被**用户自删**或**运营删除 / 下架** → 对应那一侧 −1（不低于 0）。
     *
     * <p>⚠️ 调用方必须只在**它此前确实计入过**（即处于 VISIBLE 且带态度）时调用 ——
     * 一条还挂在 UNDER_REVIEW 的评论被删掉时减 1，会把计数减成负数（这里兜到 0，
     * 但那之后这个场所的数字就一直偏低，直到下一次自愈）。
     */
    public void onVisibleCommentRemoved(long placeId, PlaceCommentAttitude attitude) {
        if (attitude == null) {
            return;
        }
        afterCommit(() -> bump(keyOf(placeId, attitude), -1));
    }

    /**
     * 把计数变更推迟到**事务提交之后**（code-review 2026-09-15）。
     *
     * <p>🔴 三个触发点都在各自的 {@code @Transactional} 里。事务内直接 INCR 的话，
     * 那个事务一旦回滚（删除时撞死锁、约束冲突、任何异常），**库里什么都没变，计数却已经改了**；
     * 用户重试成功 → 同一条评论减了两次。Redis 不参与数据库事务，只能靠这个钩子对齐。
     *
     * <p>没有活动事务时（比如从测试或异步回调直接调进来）就地执行 —— 那时不存在"回滚"。
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String keyOf(long placeId, PlaceCommentAttitude attitude) {
        return (attitude == PlaceCommentAttitude.RECOMMEND
                ? RECOMMEND_KEY_PREFIX : NOT_RECOMMEND_KEY_PREFIX) + placeId;
    }

    /**
     * 加减一个计数器。
     *
     * <p>🔴 **键不存在时不要"先 set 再 incr"**：那会把一个从未算过的场所的计数当成 0 起步，
     * 而它库里可能已经有 30 条评论（Redis 被清、或这个场所从没被列出来过）。
     * Redis 的 `INCR` 对不存在的键确实从 0 起，所以这里**先确认键在**（不在就先回算写回），
     * 再加减。
     *
     * <p>Redis 不可用时**只记一行日志**：计数是派生数据，掉一次增量会在下一次自愈时补回来 ——
     * 绝不能因为计数失败把"发表评论"这个主动作也搞失败。
     */
    private void bump(String key, int delta) {
        try {
            if (Boolean.FALSE.equals(redis.hasKey(key))) {
                recomputeAndStore(List.of(placeIdOf(key)));
                return; // 回算出来的已经是含这一条的最新值，不要再加一次
            }
            Long after = redis.opsForValue().increment(key, delta);
            // 🔴 两种情况都**删键**而不是就地修正：删掉 = 下一次读自动回库重算，
            //    且不会留下一个没有 TTL 的永久键（TTL 是偏差有界的唯一保证）。
            // ① 减成负数：计数已经偏了。⚠️ 不能 set(key, "0") —— 普通 SET 会清掉 TTL。
            if (after != null && after < 0) {
                redis.delete(key);
                return;
            }
            // ② 键恰好在 hasKey 与 INCR 之间过期：INCR 新建了一个无 TTL、值只含这一次增量的键。
            Long ttl = redis.getExpire(key);
            if (ttl != null && ttl == -1) {
                redis.delete(key);
            }
        } catch (RuntimeException e) {
            log.warn("场所计数写 Redis 失败（下次读取时会自愈）：{}", e.getClass().getSimpleName());
        }
    }

    private static long placeIdOf(String key) {
        int at = key.lastIndexOf(':');
        return Long.parseLong(key.substring(at + 1));
    }

    /**
     * 从库回算并写回（AD-9 的"自愈"那一半）。
     *
     * <p>口径：{@code deleted_at IS NULL AND moderation_status = VISIBLE}，
     * 与 {@link #onCommentBecameVisible} / {@link #onVisibleCommentRemoved} 的增减时机一致。
     *
     * <p>⚠️ **不是 viewer 维度**：这两个数字是"大家觉得这地方行不行"的平台口径，
     * 与谁在看无关（对比：评论数是 viewer 维度的，因为它要与列出来的条数一致）。
     */
    @Transactional(readOnly = true)
    public Map<Long, Counts> recomputeAndStore(List<Long> placeIds) {
        Map<Long, long[]> tally = new HashMap<>();
        for (Long id : placeIds) {
            tally.put(id, new long[] {0, 0});
        }
        for (Object[] row : comments.countVisibleAttitudesByPlaceIds(placeIds)) {
            long placeId = ((Number) row[0]).longValue();
            PlaceCommentAttitude attitude = (PlaceCommentAttitude) row[1];
            long count = ((Number) row[2]).longValue();
            long[] pair = tally.get(placeId);
            if (pair == null || attitude == null) {
                continue;
            }
            pair[attitude == PlaceCommentAttitude.RECOMMEND ? 0 : 1] = count;
        }

        Map<Long, Counts> result = new LinkedHashMap<>();
        tally.forEach((id, pair) -> {
            result.put(id, new Counts(pair[0], pair[1]));
            writeBackIfAbsent(RECOMMEND_KEY_PREFIX + id, pair[0]);
            writeBackIfAbsent(NOT_RECOMMEND_KEY_PREFIX + id, pair[1]);
        });
        return result;
    }

    /**
     * 解析一个计数值；**任何不健康的值都返回 null（= 当缺失处理，走回算）**。
     *
     * <p>🔴 负数也算不健康，不要 {@code Math.max(0, …)} 洗成 0：
     * 键停在 {@code -1}（那次"减到负就置 0"的补偿写失败了）时，洗成 0 会让它被当作健康值 ——
     * 之后每次 +1 都是 -1→0→1，这个场所的计数**永远比库里低**，而且永远不会被回算修好
     * （code-review 2026-09-15）。
     */
    /**
     * 回算结果写回。
     *
     * <p>🔴 **`setIfAbsent` 而不是无条件覆盖**：两个线程可能同时发现键缺失并各自回算，
     * 而其中一个读到的是**更新的**库状态。无条件覆盖时，慢的那个会把新值盖回旧值 ——
     * 一次审核通过就这么被永久吞掉（code-review 2026-09-15）。
     * 先写的赢，后到的增量照常加在它上面；万一先写的那个是旧值，TTL 到期会纠正。
     *
     * <p>逐键 set（而不是 `multiSet`）是因为 **TTL 必须跟着值一起写** ——
     * `multiSet` 没有带过期时间的变体，分两步写会出现"值在、TTL 没设上"的窗口，
     * 而那个键从此永不过期，也就永不自愈。回算本来就是冷路径（只在缺值/过期时发生）。
     */
    private void writeBackIfAbsent(String key, long value) {
        try {
            redis.opsForValue().setIfAbsent(key, String.valueOf(value), TTL);
        } catch (RuntimeException e) {
            // 写回失败 = 下次还得再算一遍，但本次返回的结果是对的。
            log.warn("场所计数写回 Redis 失败：{}", e.getClass().getSimpleName());
        }
    }

    private static Long parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            long v = Long.parseLong(raw);
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null; // 值异常 → 当缺失处理，走回算
        }
    }
}
