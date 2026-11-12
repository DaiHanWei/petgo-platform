package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.DetailBuilder.Outcome;
import com.tailtopia.analytics.store.DetailBuilder.RunResult;
import com.tailtopia.auth.domain.User;
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
 * L1：原始层 → 明细层转换（analytics-pipeline Story 1.6 AC1 / AC3 / AC4 / AC7 / AC8），跑在埋点测试库
 * {@code petgo_analytics_test}（前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>定时关闭（基类 {@code analytics.detail.initial-delay-ms=1 天}），用例手工 new 一个带 mock 告警的实例直接调
 *       {@link DetailBuilder#runOnce()}（不额外起 Spring 上下文 / 连接池）。</li>
 *   <li>真实时钟：{@code DetailBuilder} 只处理到 {@code now − 2 分钟}，原始行的 {@code received_at} 取最近十几分钟；每个用例开始
 *       把共享的 {@code pipeline_progress} 回拨到这批行之前（重放别人留下的行是幂等的，查重 + 唯一键兜底）。</li>
 *   <li>隔离：本类造的行一律用唯一 {@code message_id} 前缀，结束按前缀删原始层与明细层，不 TRUNCATE。</li>
 * </ul>
 */
class DetailBuilderIntegrationTest extends ApiIntegrationTest {

    private static final String EVENT = "bottom_nav_tab_switched";
    private static final String STORE_CTX = "{\"platform\":\"android\",\"appVersion\":\"1.5.0\",\"osVersion\":\"14\","
            + "\"deviceModel\":\"Google Pixel 8\",\"isPhysicalDevice\":true,\"installSource\":\"store\","
            + "\"isProduction\":true,\"installerStore\":\"com.android.vending\"}";
    private static final String SIMULATOR_CTX = "{\"platform\":\"android\",\"isPhysicalDevice\":false,"
            + "\"installSource\":\"simulator\",\"isProduction\":false}";

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

    private OpsAlertService alerts;
    private DetailBuilder builder;
    private String prefix;
    private Instant now;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        assertThat(catalog.allowedPropertyKeys(EVENT)).contains("from_tab", "to_tab");
        alerts = mock(OpsAlertService.class);
        builder = newBuilder();
        prefix = "it-db-" + SEQ.incrementAndGet() + "-";
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 用例会用到 WIB 今天往前 4 天到明天的明细分区（迁移只建了它跑那天的前后 7 天，共享测试库可能早已过期）
        LocalDate today = EventTimeCorrector.wibDate(now);
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = today.minusDays(4); !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
                DetailPartitions.ensure(analyticsJdbc, d);
            }
        });
        setProgress(now.minus(Duration.ofMinutes(20)));
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private DetailBuilder newBuilder() {
        return new DetailBuilder(state, analyticsJdbc, analyticsTx, businessJdbc, catalog, alerts, "test",
                Clock.systemUTC(), Duration.ofSeconds(50));
    }

    private void setProgress(Instant t) {
        analyticsJdbc.update("UPDATE pipeline_progress SET last_received_at = ? WHERE pipeline = 'detail'", utc(t));
    }

    private Instant progress() {
        return analyticsJdbc.queryForObject("SELECT last_received_at FROM pipeline_progress WHERE pipeline = 'detail'",
                OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private void insertRaw(String messageId, String event, Instant clientTs, Instant sentAt, Instant receivedAt,
            Long userId, String props, String ctx, boolean unknown) {
        analyticsJdbc.update("""
                INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, anonymous_id, user_key,
                                        user_id, source, properties, context, unknown_event)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APP', ?::jsonb, ?::jsonb, ?)""",
                messageId, event, utc(clientTs), utc(sentAt), utc(receivedAt), "4b0e3c0e-5f6a-4c41-9a39-0d1f6c2b7a10",
                userId == null ? null : AnalyticsDistinctId.of(userId), userId, props, ctx, unknown);
    }

    private void insertRaw(String messageId, Instant receivedAt, Long userId, String ctx) {
        insertRaw(messageId, EVENT, receivedAt.minusSeconds(2), receivedAt.minusSeconds(1), receivedAt, userId,
                "{\"from_tab\":\"home\",\"to_tab\":\"shop\"}", ctx, false);
    }

    private List<Map<String, Object>> detail(String messageId) {
        return analyticsJdbc.queryForList("SELECT * FROM events_detail WHERE message_id = ?", messageId);
    }

    @Test
    void buildsDetailAndHoldsBackRowsYoungerThanTwoMinutes() {
        String ready = prefix + "ready";
        String young = prefix + "young";
        insertRaw(ready, now.minus(Duration.ofMinutes(10)), null, STORE_CTX);
        insertRaw(young, now.minus(Duration.ofSeconds(30)), null, STORE_CTX);

        RunResult r = builder.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        List<Map<String, Object>> rows = detail(ready);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("event")).isEqualTo(EVENT);
        assertThat(row.get("platform")).isEqualTo("android");
        assertThat(row.get("app_version")).isEqualTo("1.5.0");
        assertThat(row.get("os_version")).isEqualTo("14");
        assertThat(row.get("device_model")).isEqualTo("Google Pixel 8");
        assertThat(row.get("is_production")).isEqualTo(true);
        assertThat(row.get("is_suspicious")).isEqualTo(false);
        assertThat(detail(young)).as("received_at 在 now − 2 分钟之内的行本轮不处理").isEmpty();
        assertThat(progress()).isAfter(now.minus(Duration.ofMinutes(10)));
        assertThat(progress()).as("进度只推进到 now − 2 分钟").isBefore(now.minusSeconds(30));
    }

    @Test
    void unknownEventIsNotTransferred() {
        String id = prefix + "unknown";
        insertRaw(id, "it_not_in_catalog", now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(10)),
                now.minus(Duration.ofMinutes(10)), null, "{}", "{}", true);

        assertThat(builder.runOnce().outcome()).isEqualTo(Outcome.DONE);
        assertThat(detail(id)).isEmpty();
    }

    @Test
    void stripsUnregisteredPropertyKeys() {
        String id = prefix + "slim";
        Instant received = now.minus(Duration.ofMinutes(10));
        insertRaw(id, EVENT, received.minusSeconds(2), received.minusSeconds(1), received, null,
                "{\"from_tab\":\"home\",\"to_tab\":\"shop\",\"phone\":\"08123456789\"}", STORE_CTX, false);

        builder.runOnce();

        String props = analyticsJdbc.queryForObject("SELECT properties::text FROM events_detail WHERE message_id = ?",
                String.class, id);
        assertThat(props).contains("from_tab").contains("to_tab").doesNotContain("phone").doesNotContain("0812");
    }

    @Test
    void duplicateWithinSameSliceYieldsOneRowFromEarliestReceipt() {
        String id = prefix + "dup";
        Instant first = now.minus(Duration.ofMinutes(10));
        Instant client = first.minusSeconds(60);
        insertRaw(id, EVENT, client, first, first, null, "{}", STORE_CTX, false);
        // 重试：同一 messageId，60 秒后重发（发生时间故意不同，用来分辨留下的是哪一条）
        insertRaw(id, EVENT, client.plusSeconds(30), first.plusSeconds(60), first.plusSeconds(60), null, "{}",
                STORE_CTX, false);

        builder.runOnce();

        List<Map<String, Object>> rows = detail(id);
        assertThat(rows).hasSize(1);
        assertThat(((java.sql.Timestamp) rows.get(0).get("event_time")).toInstant()).as("取最早收到的那条")
                .isEqualTo(client);
    }

    @Test
    void retryAcrossWibMidnightInTwoSlicesYieldsOneRow() {
        String id = prefix + "midnight";
        LocalDate d = EventTimeCorrector.wibDate(now).minusDays(2);
        Instant r1 = now.minus(Duration.ofMinutes(14));
        Instant r2 = now.minus(Duration.ofMinutes(7));
        // 第一次：校正后 D 23:59:59 WIB；第二次（不同 sent_at）：校正后 D+1 00:00:01 WIB
        Instant c1 = d.atTime(23, 59, 59).atZone(EventTimeCorrector.WIB).toInstant();
        Instant c2 = d.plusDays(1).atTime(0, 0, 1).atZone(EventTimeCorrector.WIB).toInstant();
        insertRaw(id, EVENT, c1, r1, r1, null, "{}", STORE_CTX, false);
        insertRaw(id, EVENT, c2, r2, r2, null, "{}", STORE_CTX, false);
        // r1 与 r2 相隔 7 分钟 > 5 分钟时间片：两条必然分属两个时间片（两个事务）
        setProgress(r1.minusSeconds(1));

        RunResult r = builder.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.slices()).isGreaterThanOrEqualTo(2);
        List<Map<String, Object>> rows = detail(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_date").toString()).isEqualTo(d.toString());
    }

    @Test
    void nonProductionFromContextAndUsers() {
        User real = newUser();
        User vet = newUser();
        User virtual = newUser();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", vet.getId());
        businessJdbc.update("UPDATE users SET account_type = 'VIRTUAL' WHERE id = ?", virtual.getId());
        Instant received = now.minus(Duration.ofMinutes(10));
        insertRaw(prefix + "real", received, real.getId(), STORE_CTX);
        insertRaw(prefix + "sim", received, real.getId(), SIMULATOR_CTX);
        insertRaw(prefix + "vet", received, vet.getId(), STORE_CTX);
        insertRaw(prefix + "virtual", received, virtual.getId(), STORE_CTX);
        insertRaw(prefix + "ghost", received, Long.MAX_VALUE - SEQ.incrementAndGet(), STORE_CTX);

        RunResult r = builder.runOnce();

        assertThat(detail(prefix + "real").get(0).get("is_production")).isEqualTo(true);
        assertThat(detail(prefix + "real").get(0).get("user_key")).isEqualTo(AnalyticsDistinctId.of(real.getId()));
        assertThat(detail(prefix + "sim").get(0).get("is_production")).isEqualTo(false);
        assertThat(detail(prefix + "vet").get(0).get("is_production")).isEqualTo(false);
        assertThat(detail(prefix + "virtual").get(0).get("is_production")).isEqualTo(false);
        assertThat(detail(prefix + "ghost").get(0).get("is_production")).as("业务库查不到的用户").isEqualTo(false);
        assertThat(r.unknownUsers()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void progressAndDataCommitTogether() {
        String id = prefix + "rollback";
        Instant start = now.minus(Duration.ofMinutes(10));
        insertRaw(id, start.plusSeconds(1), null, STORE_CTX);
        setProgress(start);
        DetailBuilder failing = new DetailBuilder(state, analyticsJdbc, analyticsTx, businessJdbc, catalog, alerts,
                "test", Clock.systemUTC(), Duration.ofSeconds(50)) {
            @Override
            protected void beforeCommit() {
                throw new IllegalStateException("boom after write");
            }
        };

        RunResult r = failing.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(progress()).isEqualTo(start);
        assertThat(detail(id)).isEmpty();
        verify(alerts).send(eq(OpsChannel.TECH), startsWith(DetailBuilder.ALERT_FAILED), anyString());

        // 修好后下一轮从断点继续
        assertThat(builder.runOnce().outcome()).isEqualTo(Outcome.DONE);
        assertThat(detail(id)).hasSize(1);
    }
}
