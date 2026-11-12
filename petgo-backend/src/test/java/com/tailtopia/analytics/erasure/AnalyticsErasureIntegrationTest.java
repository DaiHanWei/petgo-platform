package com.tailtopia.analytics.erasure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.erasure.ErasureJob.Outcome;
import com.tailtopia.analytics.erasure.ErasureJob.RunResult;
import com.tailtopia.analytics.store.DetailBuilder;
import com.tailtopia.analytics.store.DetailPartitions;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.analytics.store.RawPartitionPlanner;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：注销擦除端到端（analytics-pipeline Story 1.8 AC1–AC5 + T5），真业务库 + 埋点测试库 {@code petgo_analytics_test}
 * （前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>注销走真实状态机：{@link AccountDeletionService#requestDeletion} → 提交后 {@code @Async} 执行 → 轮询到 {@code DONE}。</li>
 *   <li>定时关闭（基类 {@code analytics.erasure.cron=-}），用例手工 new 一个带 mock 告警的 {@link ErasureJob} 直接调
 *       {@link ErasureJob#runOnce()}。共享库里别的测试留下的待擦除请求会被一并处理（本来就该擦）。</li>
 *   <li>隔离：埋点行一律用唯一 {@code message_id} 前缀，结束按前缀删；outbox 行属于本类新造的用户，保留。</li>
 * </ul>
 */
class AnalyticsErasureIntegrationTest extends ApiIntegrationTest {

    private static final String ANON_A = "aaaaaaaa-5f6a-4c41-9a39-0d1f6c2b7a10";
    private static final String ANON_A2 = "aaaaaaab-5f6a-4c41-9a39-0d1f6c2b7a10";
    private static final String ANON_B = "bbbbbbbb-5f6a-4c41-9a39-0d1f6c2b7a10";

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;
    @Autowired
    private JdbcTemplate businessJdbc;
    @Autowired
    private EventCatalog catalog;
    @Autowired
    private AccountDeletionService accountDeletion;

    private OpsAlertService alerts;
    private ErasureJob job;
    private String prefix;
    private Instant now;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        assertThat(catalog.userIdentifierKeys()).contains("owner_id", "target_user_id");
        alerts = mock(OpsAlertService.class);
        job = newJob(state);
        prefix = "it-er-" + SEQ.incrementAndGet() + "-";
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        LocalDate todayWib = EventTimeCorrector.wibDate(now);
        LocalDate todayUtc = RawPartitionPlanner.utcDay(now);
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = todayWib.minusDays(3); !d.isAfter(todayWib.plusDays(1)); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
            for (LocalDate d = todayUtc.minusDays(3); !d.isAfter(todayUtc.plusDays(1)); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + RawPartitionPlanner.partitionName(d)
                        + " PARTITION OF " + RawPartitionPlanner.PARENT + " FOR VALUES FROM ("
                        + RawPartitionPlanner.lowerBound(d) + ") TO (" + RawPartitionPlanner.upperBound(d) + ")");
            }
        });
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private ErasureJob newJob(AnalyticsPipelineState s) {
        return new ErasureJob(s, analyticsJdbc, analyticsTx, businessJdbc, catalog, alerts, "test", "30min",
                Clock.systemUTC());
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private void link(long userId, String anon, Instant at) {
        analyticsJdbc.update("INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
                + "VALUES (?, ?, ?, ?, ?)", anon, userId, AnalyticsDistinctId.of(userId), utc(at),
                prefix + "link-" + SEQ.incrementAndGet());
    }

    private String raw(String event, String anon, Long userId, String props, Instant receivedAt) {
        String id = prefix + "raw-" + SEQ.incrementAndGet();
        analyticsJdbc.update("""
                INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, anonymous_id, user_key,
                                        user_id, source, properties, context)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APP', ?::jsonb, '{}'::jsonb)""",
                id, event, utc(receivedAt.minusSeconds(2)), utc(receivedAt.minusSeconds(1)), utc(receivedAt), anon,
                userId == null ? null : AnalyticsDistinctId.of(userId), userId, props);
        return id;
    }

    private String detail(String event, String anon, Long userId, String props) {
        String id = prefix + "det-" + SEQ.incrementAndGet();
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, source, anonymous_id,
                                           user_id, user_key, is_production, properties)
                VALUES (?, ?, ?, ?, ?, 'APP', ?, ?, ?, true, ?::jsonb)""",
                id, event, utc(now), utc(now), EventTimeCorrector.wibDate(now), anon, userId,
                userId == null ? null : AnalyticsDistinctId.of(userId), props);
        return id;
    }

    private String publicToken(long userId) {
        return businessJdbc.queryForObject("SELECT public_token FROM users WHERE id = ?", String.class, userId);
    }

    private void deleteAccountAndWait(long userId) throws InterruptedException {
        accountDeletion.requestDeletion(userId);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            List<String> s = businessJdbc.queryForList("SELECT status FROM account_deletions WHERE user_id = ?",
                    String.class, userId);
            status = s.isEmpty() ? null : s.get(0);
            if (DeletionStatus.DONE.name().equals(status) || DeletionStatus.FAILED.name().equals(status)) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(status).as("注销状态机应跑完").isEqualTo(DeletionStatus.DONE.name());
    }

    private Map<String, Object> outboxRow(long userId) {
        return businessJdbc.queryForMap("SELECT user_key, done_at FROM analytics_erasure_outbox WHERE user_id = ?",
                userId);
    }

    /** 埋点库全表（关联 / 明细 / 原始）里能按 A 的 user_id / user_key / 属性值找到的行数。 */
    private long traces(long userId, String token) {
        String key = AnalyticsDistinctId.of(userId);
        String id = Long.toString(userId);
        long n = analyticsJdbc.queryForObject("SELECT count(*) FROM identity_links WHERE user_id = ? OR user_key = ?",
                Long.class, userId, key);
        for (String table : List.of("events_detail", "events_raw")) {
            n += analyticsJdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ? OR user_key = ? "
                    + "OR properties ->> 'owner_id' IN (?, ?, ?) OR properties ->> 'target_user_id' IN (?, ?, ?)",
                    Long.class, userId, key, id, key, token, id, key, token);
        }
        return n;
    }

    private long countByPrefix(String table) {
        return analyticsJdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE message_id LIKE ?", Long.class,
                prefix + "%");
    }

    @Test
    void deletionThenErasureUnlinksUserEverywhereButKeepsAnonymousEvents() throws InterruptedException {
        long a = newUser().getId();
        long b = newUser().getId();
        String tokenA = publicToken(a);
        // A：两条登录关联、原始层两个分区（今天 / 昨天 UTC）各一条、明细层一条（都带清单里的用户标识属性）
        link(a, ANON_A, now.minus(Duration.ofDays(2)));
        link(a, ANON_A2, now.minus(Duration.ofDays(1)));
        String rawToday = raw("user_followed", ANON_A, a, "{\"target_user_id\":" + b + ",\"source\":\"feed\"}",
                now.minus(Duration.ofMinutes(10)));
        String rawYesterday = raw("pet_follow", ANON_A2, a, "{\"owner_id\":\"zzz\",\"pet_id\":1}",
                now.minus(Duration.ofDays(1)));
        String detA = detail("user_followed", ANON_A, a, "{\"target_user_id\":" + b + ",\"source\":\"feed\"}");
        // B：事件属性里引用了 A（公开 token / 内部 id）
        String rawB1 = raw("pet_follow", ANON_B, b, "{\"owner_id\":\"" + tokenA + "\",\"source\":\"feed\"}",
                now.minus(Duration.ofMinutes(9)));
        String rawB2 = raw("user_profile_viewed", ANON_B, b, "{\"target_user_id\":" + a + ",\"from\":\"feed\"}",
                now.minus(Duration.ofDays(1)));
        String detB = detail("pet_follow", ANON_B, b, "{\"owner_id\":\"" + tokenA + "\",\"source\":\"feed\"}");
        long rawBefore = countByPrefix("events_raw");
        long detailBefore = countByPrefix("events_detail");
        assertThat(traces(a, tokenA)).isGreaterThanOrEqualTo(7);

        // AC1：注销状态机跑完 → 业务库 outbox 有一行（待处理）
        deleteAccountAndWait(a);
        Map<String, Object> pending = outboxRow(a);
        assertThat(pending.get("user_key")).isEqualTo(AnalyticsDistinctId.of(a));
        assertThat(pending.get("done_at")).isNull();

        RunResult first = job.runOnce();

        // AC2 / AC5：埋点库按 user_id / user_key / 属性值都找不到 A；outbox 回写 done_at
        assertThat(first.outcome()).isEqualTo(Outcome.DONE);
        assertThat(first.pending()).isGreaterThanOrEqualTo(1);
        assertThat(traces(a, tokenA)).isZero();
        assertThat(outboxRow(a).get("done_at")).isNotNull();
        // AC4：只脱钩不删事件 —— 行数不变，匿名 id 还在
        assertThat(countByPrefix("events_raw")).isEqualTo(rawBefore);
        assertThat(countByPrefix("events_detail")).isEqualTo(detailBefore);
        assertThat(analyticsJdbc.queryForObject("SELECT anonymous_id FROM events_raw WHERE message_id = ?",
                String.class, rawToday)).isEqualTo(ANON_A);
        assertThat(analyticsJdbc.queryForObject("SELECT properties::text FROM events_raw WHERE message_id = ?",
                String.class, rawToday)).doesNotContain("target_user_id").contains("feed");
        assertThat(analyticsJdbc.queryForObject("SELECT properties::text FROM events_raw WHERE message_id = ?",
                String.class, rawYesterday)).doesNotContain("owner_id").contains("pet_id");
        assertThat(analyticsJdbc.queryForObject("SELECT anonymous_id FROM events_detail WHERE message_id = ?",
                String.class, detA)).isEqualTo(ANON_A);
        // B 的事件保留（仍归 B），但不再含 A 的标识
        for (String id : List.of(rawB1, rawB2)) {
            Map<String, Object> row = analyticsJdbc.queryForMap(
                    "SELECT user_id, properties::text AS p FROM events_raw WHERE message_id = ?", id);
            assertThat(row.get("user_id")).isEqualTo(b);
            assertThat((String) row.get("p")).doesNotContain(tokenA).doesNotContain("target_user_id");
        }
        Map<String, Object> rowDetB = analyticsJdbc.queryForMap(
                "SELECT user_id, properties::text AS p FROM events_detail WHERE message_id = ?", detB);
        assertThat(rowDetB.get("user_id")).isEqualTo(b);
        assertThat((String) rowDetB.get("p")).doesNotContain(tokenA).contains("feed");

        // AC3：再跑一次 → 无报错、无更新行（已处理的请求不再全量扫描）
        RunResult second = job.runOnce();
        assertThat(second.outcome()).isEqualTo(Outcome.DONE);
        assertThat(second.pending()).isZero();
        assertThat(second.linksDeleted()).isZero();
        assertThat(second.detailRows()).isZero();
        assertThat(second.rawRows()).isZero();
    }

    @Test
    void lateEventsAfterErasureNeverReachDetailAndAreClearedFromRaw() throws InterruptedException {
        long a = newUser().getId();
        long b = newUser().getId();
        String tokenA = publicToken(a);
        deleteAccountAndWait(a);
        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.DONE);
        assertThat(outboxRow(a).get("done_at")).isNotNull();

        // 擦除完成后，A 的设备补发旧事件；B 的设备也补发一条引用 A 的事件（received_at 在 now − 2 分钟之前，明细会转）
        Instant received = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofMinutes(3));
        String lateA = raw("user_followed", ANON_A, a, "{\"target_user_id\":" + b + ",\"source\":\"feed\"}", received);
        String lateB = raw("pet_follow", ANON_B, b, "{\"owner_id\":\"" + tokenA + "\",\"source\":\"feed\"}", received);
        analyticsJdbc.update("UPDATE pipeline_progress SET last_received_at = ? WHERE pipeline = 'detail'",
                utc(received.minus(Duration.ofMinutes(5))));

        // T5 ①：DetailBuilder 写明细前按业务库 outbox 拦截 —— 明细层永不再出现 A
        DetailBuilder builder = new DetailBuilder(state, analyticsJdbc, analyticsTx, businessJdbc, catalog, alerts,
                "test");
        assertThat(builder.runOnce().outcome()).isEqualTo(DetailBuilder.Outcome.DONE);
        Map<String, Object> detLateA = analyticsJdbc.queryForMap(
                "SELECT user_id, user_key, anonymous_id, properties::text AS p FROM events_detail WHERE message_id = ?",
                lateA);
        assertThat(detLateA.get("user_id")).isNull();
        assertThat(detLateA.get("user_key")).isNull();
        assertThat(detLateA.get("anonymous_id")).isEqualTo(ANON_A);
        assertThat((String) detLateA.get("p")).doesNotContain("target_user_id");
        assertThat(analyticsJdbc.queryForObject("SELECT properties::text FROM events_detail WHERE message_id = ?",
                String.class, lateB)).doesNotContain(tokenA);

        // T5 ②：下一轮 ErasureJob 只扫 done_at 之后的原始层分区，把补发行也置空
        RunResult r = job.runOnce();
        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.late()).isGreaterThanOrEqualTo(1);
        assertThat(r.rawRows()).isGreaterThanOrEqualTo(2);
        assertThat(traces(a, tokenA)).isZero();
    }

    @Test
    void pipelineDisabledSkipsWithAlertAndLeavesRequestPending() throws InterruptedException {
        long a = newUser().getId();
        // A10（Dai 拍板）：注销只写业务库 outbox，不依赖埋点库 —— 状态机照常 DONE
        deleteAccountAndWait(a);
        AnalyticsPipelineState down = new AnalyticsPipelineState();
        down.disable(Reason.DB_UNAVAILABLE, "test");

        assertThat(newJob(down).runOnce().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);

        assertThat(outboxRow(a).get("done_at")).isNull();
        verify(alerts).send(eq(OpsChannel.TECH), startsWith(ErasureJob.ALERT_SKIPPED), anyString());

        // 埋点库恢复后（同一请求）由下一轮处理
        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.DONE);
        assertThat(outboxRow(a).get("done_at")).isNotNull();
    }
}
