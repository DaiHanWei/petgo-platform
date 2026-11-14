package com.tailtopia.topic.service;

import static com.tailtopia.topic.domain.TopicHeat.INTERACTION_WEIGHT;
import static com.tailtopia.topic.domain.TopicHeat.POST_WEIGHT;
import static com.tailtopia.topic.domain.TopicHeat.VIEW_WEIGHT;
import static com.tailtopia.topic.domain.TopicHeat.sqlWeight;

import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.topic.domain.TopicHeat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 话题热度 / 帖子数每小时重算（V1.4.2 social-topic · Story 4.2 · AC7 · D-14 · FR-69.6）。
 *
 * <ul>
 *   <li>公式只在 {@link TopicHeat}（SQL 由其常量拼出）；</li>
 *   <li>「可计入帖子」= PUBLIC + PUBLISHED + 未删 + 作者未注销且 ACTIVE（与话题页列表 AC2 同口径，复审采纳）；</li>
 *   <li>窗口：帖子 / 点赞 / 评论 {@code created_at >= now − 7 天}；按天浏览 {@code view_date >= 今天(WIB) − 6}（含今天共 7 个 WIB 日）；</li>
 *   <li>评论 = 平台口径（CROSS-STORY 2026-08-16）：未删 + VISIBLE，R1 不套，<b>R2 影子评论及其回复必须排除</b>；</li>
 *   <li><b>一条集合 UPDATE 覆盖所有话题</b>（无数据置 0、不残留旧值），只写值有变化的行；<b>不改 {@code updated_at}</b>
 *       （那是运营编辑时间，6.1 列表展示）；</li>
 *   <li>防重入：事务内 {@code pg_try_advisory_xact_lock(hashtext('topic_heat'))}，拿不到即跳过本轮；</li>
 *   <li>兜底 catch 只记异常类名（照 {@code DashboardMaterializer.run}），不记 SQL 参数与内容。</li>
 * </ul>
 * 原生 {@code @Scheduled} + DB，禁 MQ / Quartz / 缓存层。R-5：后台列表帖子数最多滞后 1 小时可接受。
 */
@Component
public class TopicHeatJob {

    private static final Logger log = LoggerFactory.getLogger(TopicHeatJob.class);

    /** advisory 锁（事务级，拿不到即跳过）。 */
    static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(hashtext('topic_heat'))";

    /** 一条集合 SQL 重算全部话题的 {@code post_count} 与 {@code heat_score}。 */
    static final String RECOMPUTE_SQL = """
            WITH vis AS (
                SELECT ct.topic_id, p.id AS post_id, p.author_id, p.created_at
                FROM content_topics ct
                JOIN content_posts p ON p.id = ct.post_id
                JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
                WHERE p.deleted_at IS NULL AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC'
            ),
            agg_posts AS (
                SELECT topic_id, COUNT(*) AS post_count,
                       COUNT(*) FILTER (WHERE created_at >= :since) AS new_posts
                FROM vis GROUP BY topic_id
            ),
            agg_views AS (
                SELECT v.topic_id, COUNT(*) AS viewer_days
                FROM vis v
                JOIN content_post_view_days d ON d.post_id = v.post_id AND d.view_date >= :fromDate
                GROUP BY v.topic_id
            ),
            agg_likes AS (
                SELECT v.topic_id, COUNT(*) AS likes
                FROM vis v
                JOIN content_likes l ON l.post_id = v.post_id AND l.created_at >= :since
                 AND NOT l.is_automated -- V1.5.0 自动点赞 FR-218：排除自动赞
                GROUP BY v.topic_id
            ),
            agg_comments AS (
                SELECT v.topic_id, COUNT(*) AS comments
                FROM vis v
                JOIN comments c ON c.post_id = v.post_id AND c.created_at >= :since
                 AND c.deleted_at IS NULL AND c.moderation_status = 'VISIBLE'
                WHERE NOT EXISTS (SELECT 1 FROM user_hide_relations h
                                  WHERE h.holder_id = v.author_id AND h.target_id = c.author_id)
                  AND (c.parent_id IS NULL OR NOT EXISTS (
                          SELECT 1 FROM comments pc JOIN user_hide_relations h2
                            ON h2.holder_id = v.author_id AND h2.target_id = pc.author_id
                          WHERE pc.id = c.parent_id))
                GROUP BY v.topic_id
            ),
            calc AS (
                SELECT t2.id,
                       CAST(COALESCE(ap.post_count, 0) AS INTEGER) AS post_count,
                       CAST(COALESCE(ap.new_posts, 0) * %s
                          + COALESCE(av.viewer_days, 0) * %s
                          + COALESCE(al.likes, 0) * %s
                          + COALESCE(ac.comments, 0) * %s AS NUMERIC(12,1)) AS heat_score
                FROM topics t2
                LEFT JOIN agg_posts ap    ON ap.topic_id = t2.id
                LEFT JOIN agg_views av    ON av.topic_id = t2.id
                LEFT JOIN agg_likes al    ON al.topic_id = t2.id
                LEFT JOIN agg_comments ac ON ac.topic_id = t2.id
            )
            UPDATE topics t SET
                post_count = c.post_count,
                heat_score = c.heat_score
            FROM calc c
            WHERE t.id = c.id
              AND (t.post_count IS DISTINCT FROM c.post_count OR t.heat_score IS DISTINCT FROM c.heat_score)
            """.formatted(sqlWeight(POST_WEIGHT), sqlWeight(VIEW_WEIGHT),
            sqlWeight(INTERACTION_WEIGHT), sqlWeight(INTERACTION_WEIGHT));

    /** 一次运行的结果：{@code skipped} = 没拿到锁；{@code updated} = 值有变化而被写的行数。 */
    public record Result(boolean skipped, int updated) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public TopicHeatJob(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this(jdbc, txManager, Clock.systemUTC());
    }

    TopicHeatJob(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        // 独立事务：即使被外层事务调用，失败也不把外层置 rollback-only。
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 定时入口：每小时 :20（WIB 解释，{@code petgo.topic.heat-cron}）。兜底 catch 只记异常类名。 */
    @Scheduled(cron = "${petgo.topic.heat-cron:0 20 * * * *}", zone = "Asia/Jakarta")
    public void run() {
        try {
            Result r = recompute();
            if (r.skipped()) {
                log.info("topic heat skipped: lock held by another run");
            } else {
                log.info("topic heat recomputed: updated={}", r.updated());
            }
        } catch (RuntimeException e) {
            log.warn("话题热度重算失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 一轮重算（单事务；拿不到 advisory 锁即跳过、什么都不写）。 */
    public Result recompute() {
        Instant now = clock.instant();
        Instant since = since(now);
        LocalDate fromDate = fromDate(now);
        return tx.execute(status -> {
            Boolean locked = jdbc.getJdbcTemplate().queryForObject(TRY_LOCK_SQL, Boolean.class);
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(true, 0);
            }
            int updated = jdbc.update(RECOMPUTE_SQL, new MapSqlParameterSource()
                    // OffsetDateTime → timestamptz（不受会话时区影响）；LocalDate → date。
                    .addValue("since", since.atOffset(ZoneOffset.UTC))
                    .addValue("fromDate", fromDate));
            return new Result(false, updated);
        });
    }

    /** 帖子 / 点赞 / 评论窗口起点：now − 7 天。 */
    static Instant since(Instant now) {
        return now.minus(Duration.ofDays(TopicHeat.WINDOW_DAYS));
    }

    /** 按天浏览窗口起点：今天(WIB) − 6（含今天共 7 个 WIB 日）。 */
    static LocalDate fromDate(Instant now) {
        return LocalDate.ofInstant(now, ScheduleWindow.WIB).minusDays(TopicHeat.WINDOW_DAYS - 1);
    }
}
