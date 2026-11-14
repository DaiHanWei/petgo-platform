package com.tailtopia.content.autolike;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.autocomment.VirtualIdentityPicker;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.LikeService;
import com.tailtopia.content.species.ContentSpecies;
import com.tailtopia.content.species.ContentSpeciesResolver;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 自动点赞派发（V1.5.0 Story 2.3，FR-214 派发侧 / FR-215 / FR-216 / FR-217 / FR-219，架构 L-6 / L-9 / L-10）。
 *
 * <p>每个 tick（拿到 Redis 锁后）：收口过窗口的 PLANNED → MISSED、run → DONE；开关关到此为止；取到期行；号池为空
 * 叫停全部 ACTIVE run；否则逐条一个事务（认领 → 复查 → 选号 → 点赞 → 留档）。单条异常回滚整条（赞、日志、目标集），
 * 再开小事务记 FAILED，连续失败到上限叫停本轮并撤回本轮计入的失败数。
 *
 * <p>时间一律取注入的 {@link Clock}，SQL 写 {@code :now}（便于 L1 拨钟）。日志只记数字与异常类名。
 */
@Component
public class AutoLikeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AutoLikeDispatcher.class);

    static final String ERROR_ALREADY_LIKED = "ALREADY_LIKED";

    static final String MISS_EXPIRED_SQL = """
            UPDATE auto_like_logs SET status = 'MISSED', updated_at = now()
            WHERE status = 'PLANNED' AND window_end <= :now
            """;

    static final String CLOSE_RUNS_SQL = """
            UPDATE auto_like_runs SET status = 'DONE', updated_at = now()
            WHERE status = 'ACTIVE' AND window_end <= :now
            """;

    static final String DUE_SQL = """
            SELECT l.id, l.run_id, l.post_id
            FROM auto_like_logs l JOIN auto_like_runs r ON r.id = l.run_id AND r.status = 'ACTIVE'
            WHERE l.status = 'PLANNED' AND l.planned_at <= :now AND l.window_end > :now
            ORDER BY l.planned_at, l.id
            LIMIT :limit
            """;

    static final String STOP_ACTIVE_NO_POOL_SQL = """
            UPDATE auto_like_runs SET status = 'STOPPED', stop_reason = 'NO_POOL', updated_at = now()
            WHERE status = 'ACTIVE'
            """;

    /** 认领：仍是 PLANNED、仍在窗口内、所属 run 仍 ACTIVE（别的实例 / 本 tick 早先可能已叫停）。 */
    static final String CLAIM_SQL = """
            UPDATE auto_like_logs l SET updated_at = now()
            WHERE l.id = :id AND l.status = 'PLANNED' AND l.window_end > :now
              AND EXISTS (SELECT 1 FROM auto_like_runs r WHERE r.id = l.run_id AND r.status = 'ACTIVE')
            """;

    static final String FINISH_SQL = """
            UPDATE auto_like_logs SET status = :status, virtual_user_id = :virtualUserId,
                   like_count_before = :likeCountBefore, error_code = :errorCode, attempted_at = :now, updated_at = now()
            WHERE id = :id
            """;

    static final String LEAVE_TARGET_SQL = """
            UPDATE auto_like_targets SET left_at = :now, leave_reason = :reason, updated_at = now()
            WHERE post_id = :postId AND left_at IS NULL
            """;

    static final String TOUCH_LIKED_SQL =
            "UPDATE auto_like_targets SET last_liked_at = :now, updated_at = now() WHERE post_id = :postId";

    static final String RESET_CONSECUTIVE_SQL = """
            UPDATE auto_like_runs SET consecutive_failures = 0, updated_at = now()
            WHERE id = :runId AND consecutive_failures <> 0
            """;

    static final String FAIL_LOG_SQL = """
            UPDATE auto_like_logs SET status = 'FAILED', error_code = :errorCode, attempted_at = :now, updated_at = now()
            WHERE id = :id AND status = 'PLANNED'
            """;

    static final String FAIL_TARGET_SQL = """
            UPDATE auto_like_targets SET failed_attempts = failed_attempts + 1, updated_at = now()
            WHERE post_id = :postId
            """;

    static final String FAIL_RUN_SQL = """
            UPDATE auto_like_runs SET consecutive_failures = consecutive_failures + 1, updated_at = now()
            WHERE id = :runId AND status = 'ACTIVE'
            RETURNING consecutive_failures
            """;

    static final String STOP_RUN_FAILURES_SQL = """
            UPDATE auto_like_runs SET status = 'STOPPED', stop_reason = 'CONSECUTIVE_FAILURES', updated_at = now()
            WHERE id = :runId AND status = 'ACTIVE'
            """;

    /** 叫停轮的失败不计入任何帖子的失败次数（A-AC37 / AC38）：撤回本轮计入的那些。 */
    static final String UNDO_RUN_FAILURES_SQL = """
            UPDATE auto_like_targets SET failed_attempts = failed_attempts - 1, updated_at = now()
            WHERE failed_attempts > 0 AND post_id IN (
                SELECT post_id FROM auto_like_logs
                WHERE run_id = :runId AND status = 'FAILED'
                  AND (error_code IS NULL OR error_code <> 'ALREADY_LIKED'))
            """;

    static final String MISS_RUN_REMAINING_SQL = """
            UPDATE auto_like_logs SET status = 'MISSED', updated_at = now()
            WHERE run_id = :runId AND status = 'PLANNED'
            """;

    /** 到期行。 */
    record DueRow(long id, long runId, long postId) {
    }

    /** 一个 tick 的计数（只用于日志与测试）。 */
    record TickStats(int missed, int due, int liked, int failed, boolean stoppedNoPool) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final AutoLikeProperties props;
    private final AutoLikeVirtualPool virtualPool;
    private final ContentPostRepository posts;
    private final UserRepository users;
    private final ContentLikeRepository likes;
    private final ContentSpeciesResolver speciesResolver;
    private final VirtualIdentityPicker picker;
    private final LikeService likeService;
    private final Clock clock;

    /** 🔴 双构造器必须标 {@code @Autowired}，否则 Spring 选不出构造器、启动即崩。 */
    @Autowired
    public AutoLikeDispatcher(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager,
            StringRedisTemplate redis, AutoLikeProperties props, AutoLikeVirtualPool virtualPool,
            ContentPostRepository posts, UserRepository users, ContentLikeRepository likes,
            ContentSpeciesResolver speciesResolver, VirtualIdentityPicker picker, LikeService likeService) {
        this(jdbc, txManager, redis, props, virtualPool, posts, users, likes, speciesResolver, picker, likeService,
                Clock.systemUTC());
    }

    AutoLikeDispatcher(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager,
            StringRedisTemplate redis, AutoLikeProperties props, AutoLikeVirtualPool virtualPool,
            ContentPostRepository posts, UserRepository users, ContentLikeRepository likes,
            ContentSpeciesResolver speciesResolver, VirtualIdentityPicker picker, LikeService likeService,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.redis = redis;
        this.props = props;
        this.virtualPool = virtualPool;
        this.posts = posts;
        this.users = users;
        this.likes = likes;
        this.speciesResolver = speciesResolver;
        this.picker = picker;
        this.likeService = likeService;
        this.clock = clock;
    }

    /** 定时入口。兜底 catch 只记异常类名，不让调度线程崩。 */
    @Scheduled(fixedDelayString = "${petgo.auto-like.dispatch-interval-ms:60000}",
            initialDelayString = "${petgo.auto-like.dispatch-initial-delay-ms:30000}")
    public void tick() {
        try {
            boolean ran = AutoLikeDispatchLock.runLocked(redis, this::dispatchOnce);
            if (!ran) {
                log.debug("auto like dispatch skipped: lock held by another instance");
            }
        } catch (RuntimeException e) {
            log.warn("auto like dispatch failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 一个 tick 的全部工作（调用方持 Redis 锁）。 */
    TickStats dispatchOnce() {
        long t0 = System.currentTimeMillis();
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        int missed = jdbc.update(MISS_EXPIRED_SQL, Map.of("now", nowTs));
        jdbc.update(CLOSE_RUNS_SQL, Map.of("now", nowTs));
        if (!props.isEnabled()) {
            return logged(new TickStats(missed, 0, 0, 0, false), t0);
        }
        List<DueRow> due = jdbc.query(DUE_SQL,
                new MapSqlParameterSource("now", nowTs).addValue("limit", props.getDispatchBatch()),
                (rs, i) -> new DueRow(rs.getLong("id"), rs.getLong("run_id"), rs.getLong("post_id")));
        if (due.isEmpty()) {
            return logged(new TickStats(missed, 0, 0, 0, false), t0);
        }
        List<User> pool = virtualPool.load();
        if (pool.isEmpty()) {
            // A-AC26：叫停，不改任何日志行与目标集；已排的会在窗口结束后被收成 MISSED。
            jdbc.update(STOP_ACTIVE_NO_POOL_SQL, Map.of());
            log.warn("auto like dispatch stopped: no usable virtual account");
            return logged(new TickStats(missed, due.size(), 0, 0, true), t0);
        }
        int liked = 0;
        int failed = 0;
        for (DueRow row : due) {
            // 每条取当下时刻：一批 200 条可能跨越窗口结束，认领时按它复查窗口，留档时间也准确。
            Instant rowNow = clock.instant();
            AutoLikeLogStatus result;
            try {
                result = tx.execute(s -> processOne(row, pool, rowNow));
            } catch (RuntimeException e) {
                failed++;
                try {
                    tx.executeWithoutResult(s -> recordFailure(row, e, rowNow));
                } catch (RuntimeException inner) {
                    // 小事务也失败（如库不可用）：结束本 tick，行仍是 PLANNED，下个 tick 窗口内重试。
                    log.warn("auto like failure record failed: {}", inner.getClass().getSimpleName());
                    break;
                }
                continue;
            }
            if (result == AutoLikeLogStatus.LIKED) {
                liked++;
            }
        }
        return logged(new TickStats(missed, due.size(), liked, failed, false), t0);
    }

    private TickStats logged(TickStats s, long t0) {
        if (s.due() > 0 || s.missed() > 0) {
            log.info("auto like dispatched: due={} liked={} missed={} failed={} tookMs={}",
                    s.due(), s.liked(), s.missed(), s.failed(), System.currentTimeMillis() - t0);
        }
        return s;
    }

    /**
     * 单条处理（在调用方开启的 REQUIRES_NEW 事务里）。返回结果状态；{@code null} = 被别的实例认领过，什么都不做。
     */
    AutoLikeLogStatus processOne(DueRow row, List<User> pool, Instant now) {
        if (jdbc.update(CLAIM_SQL, Map.of("id", row.id(), "now", Timestamp.from(now))) == 0) {
            return null;
        }
        Optional<ContentPost> maybePost = posts.findById(row.postId());
        ContentPost post = maybePost.orElse(null);
        if (post == null || isGone(post)) {
            finish(row, AutoLikeLogStatus.POST_GONE, null, null, null, now);
            leave(row.postId(), AutoLikeLeaveReason.POST_GONE, now);
            return AutoLikeLogStatus.POST_GONE;
        }
        if (post.getStatus() == PostStatus.UNDER_REVIEW) {
            finish(row, AutoLikeLogStatus.SKIPPED_REVIEW, null, null, null, now);
            return AutoLikeLogStatus.SKIPPED_REVIEW;
        }
        long authorId = post.getAuthorId();
        long before = likes.countByPostId(row.postId());
        if (before >= props.getLikeTarget()) {
            finish(row, AutoLikeLogStatus.REACHED_TARGET, null, (int) before, null, now);
            leave(row.postId(), AutoLikeLeaveReason.REACHED_TARGET, now);
            return AutoLikeLogStatus.REACHED_TARGET;
        }
        List<Long> poolIds = pool.stream().map(User::getId).toList();
        Set<Long> already = new HashSet<>(likes.findLikerIdsAmong(row.postId(), poolIds));
        List<User> filtered = pool.stream().filter(u -> !already.contains(u.getId())).toList();
        String species = speciesResolver.resolve(post).species();
        User identity = picker.pick(filtered, authorId, species == null ? ContentSpecies.GENERAL : species);
        if (identity == null) {
            finish(row, AutoLikeLogStatus.NO_IDENTITY, null, (int) before, null, now);
            leave(row.postId(), AutoLikeLeaveReason.NO_IDENTITY, now);
            return AutoLikeLogStatus.NO_IDENTITY;
        }
        LikeService.AutomatedLikeResult r = likeService.likeAutomated(row.postId(), identity.getId(), authorId);
        if (!r.created()) {
            // Q-2：竞态下该号已赞——不写 LIKED、不计帖子失败数、不计连续失败数；帖子留在集合，下轮再来。
            finish(row, AutoLikeLogStatus.FAILED, identity.getId(), (int) before, ERROR_ALREADY_LIKED, now);
            return AutoLikeLogStatus.FAILED;
        }
        finish(row, AutoLikeLogStatus.LIKED, identity.getId(), (int) (r.likeCount() - 1), null, now);
        jdbc.update(TOUCH_LIKED_SQL, Map.of("now", Timestamp.from(now), "postId", row.postId()));
        if (r.likeCount() >= props.getLikeTarget()) {
            leave(row.postId(), AutoLikeLeaveReason.REACHED_TARGET, now);
        }
        return AutoLikeLogStatus.LIKED;
    }

    /** 复查（FR-215 / Q-8）：帖子已删 / 非公开 / 作者停用，或作者不再是 REAL + USER + 未注销 + ACTIVE。 */
    private boolean isGone(ContentPost post) {
        if (post.getDeletedAt() != null || post.getVisibility() != ContentVisibility.PUBLIC
                || post.getStatus() == PostStatus.AUTHOR_DEACTIVATED) {
            return true;
        }
        User author = users.findById(post.getAuthorId()).orElse(null);
        return author == null || author.getAccountType() != AccountType.REAL || author.getRole() != Role.USER
                || author.getDeletedAt() != null || author.getStatus() != UserStatus.ACTIVE;
    }

    private void finish(DueRow row, AutoLikeLogStatus status, Long virtualUserId, Integer likeCountBefore,
            String errorCode, Instant now) {
        jdbc.update(FINISH_SQL, new MapSqlParameterSource("id", row.id())
                .addValue("status", status.name())
                .addValue("virtualUserId", virtualUserId, java.sql.Types.BIGINT)
                .addValue("likeCountBefore", likeCountBefore, java.sql.Types.INTEGER)
                .addValue("errorCode", errorCode, java.sql.Types.VARCHAR)
                .addValue("now", Timestamp.from(now)));
        // 非 FAILED 结果与 ALREADY_LIKED 都把本轮连续失败数清零。
        jdbc.update(RESET_CONSECUTIVE_SQL, Map.of("runId", row.runId()));
    }

    private void leave(long postId, AutoLikeLeaveReason reason, Instant now) {
        jdbc.update(LEAVE_TARGET_SQL, new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("reason", reason.name()).addValue("postId", postId));
    }

    /** 失败小事务（L-10）：日志 FAILED、帖子失败数 +1、本轮连续失败 +1；到上限叫停并撤回本轮计入的失败数。 */
    void recordFailure(DueRow row, RuntimeException e, Instant now) {
        String code = e.getClass().getSimpleName();
        if (code.length() > 64) {
            code = code.substring(0, 64);
        }
        int marked = jdbc.update(FAIL_LOG_SQL, new MapSqlParameterSource("id", row.id())
                .addValue("errorCode", code).addValue("now", Timestamp.from(now)));
        if (marked == 0) {
            return;
        }
        List<Integer> consecutive = jdbc.queryForList(FAIL_RUN_SQL, Map.of("runId", row.runId()), Integer.class);
        if (consecutive.isEmpty()) {
            return; // 所属 run 已不是 ACTIVE（被叫停）：叫停轮的失败不计入帖子失败数（A-AC37）。
        }
        jdbc.update(FAIL_TARGET_SQL, Map.of("postId", row.postId()));
        if (consecutive.get(0) < props.getStopAfterConsecutiveFailures()) {
            return;
        }
        jdbc.update(STOP_RUN_FAILURES_SQL, Map.of("runId", row.runId()));
        jdbc.update(UNDO_RUN_FAILURES_SQL, Map.of("runId", row.runId()));
        jdbc.update(MISS_RUN_REMAINING_SQL, Map.of("runId", row.runId()));
        log.warn("auto like run stopped after {} consecutive failures", consecutive.get(0));
    }
}
