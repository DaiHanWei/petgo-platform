package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.store.DetailBuilder.DetailRow;
import com.tailtopia.analytics.store.DetailBuilder.Mapped;
import com.tailtopia.analytics.store.DetailBuilder.Outcome;
import com.tailtopia.analytics.store.DetailBuilder.RawRow;
import com.tailtopia.analytics.store.ProductionFlag.UserAccount;
import com.tailtopia.analytics.store.ProductionFlag.Verdict;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：明细转换的纯转换（瘦身 / 上下文 / user_key / 生产标记）与跳过 / 失败分支（Story 1.6 AC1 / AC3 / AC8）。 */
class DetailBuilderTest {

    /** UTC 2026-12-31 18:00 = WIB 2027-01-01 01:00：告警日期按 WIB。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T18:00:00Z"), ZoneOffset.UTC);

    private static final Instant RECEIVED = Instant.parse("2026-11-20T03:00:00Z");
    private static final Set<LocalDate> BUILT = Set.of(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20));
    private static final Function<String, Set<String>> KEYS =
            e -> "bottom_nav_tab_switched".equals(e) ? Set.of("from_tab", "to_tab") : Set.of();
    private static final Map<Long, UserAccount> USERS = Map.of(
            7L, new UserAccount("USER", "REAL"),
            8L, new UserAccount("VET", "REAL"),
            9L, new UserAccount("USER", "VIRTUAL"));

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate business = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final EventCatalog catalog = mock(EventCatalog.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final DetailBuilder builder = new DetailBuilder(state, jdbc, tx, business, catalog, alerts, "test", CLOCK,
            Duration.ofSeconds(50));

    private static RawRow app(String event, Long userId, String userKey, String props, String ctx) {
        return new RawRow("m-1", event, RECEIVED.minusSeconds(30), RECEIVED.minusSeconds(1), RECEIVED,
                "4b0e3c0e-5f6a-4c41-9a39-0d1f6c2b7a10", userKey, userId, "APP", props, ctx);
    }

    // ---------------------------------------------------------------- 纯转换

    @Test
    void stripsUnregisteredPropertyKeys() {
        Mapped m = DetailBuilder.toDetail(app("bottom_nav_tab_switched", null, null,
                "{\"from_tab\":\"home\",\"to_tab\":\"shop\",\"phone\":\"0812\",\"nested\":{\"a\":1}}", "{}"),
                BUILT, KEYS, USERS);

        assertThat(m.row().propertiesJson()).isEqualTo("{\"from_tab\":\"home\",\"to_tab\":\"shop\"}");
    }

    @Test
    void eventWithoutRegisteredKeysKeepsEmptyObject() {
        assertThat(DetailBuilder.slimProperties("{\"a\":1}", Set.of())).isEqualTo("{}");
        assertThat(DetailBuilder.slimProperties("{}", Set.of("a"))).isEqualTo("{}");
        assertThat(DetailBuilder.slimProperties("[1,2]", Set.of("a"))).isEqualTo("{}");
        assertThat(DetailBuilder.slimProperties(null, Set.of("a"))).isEqualTo("{}");
        assertThat(DetailBuilder.slimProperties("{\"a\":[1,{\"b\":null}],\"c\":2}", Set.of("a")))
                .isEqualTo("{\"a\":[1,{\"b\":null}]}");
    }

    @Test
    void correctsTimeAndKeepsClientTs() {
        Mapped m = DetailBuilder.toDetail(app("bottom_nav_tab_switched", null, null, "{}", "{}"), BUILT, KEYS, USERS);
        DetailRow r = m.row();

        // 发生 → 发送 29 秒，发送 → 收到 1 秒
        assertThat(r.clientTs()).isEqualTo(RECEIVED.minusSeconds(30));
        assertThat(r.eventTime()).isEqualTo(RECEIVED.minusSeconds(29));
        assertThat(r.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(r.suspicious()).isFalse();
    }

    @Test
    void parsesContextColumnsAndTruncatesLongValues() {
        String longModel = "x".repeat(200);
        Mapped m = DetailBuilder.toDetail(app("bottom_nav_tab_switched", 7L, null, "{}",
                "{\"platform\":\"android\",\"appVersion\":\"1.5.0\",\"appBuild\":\"150\",\"osVersion\":\"14\","
                        + "\"deviceModel\":\"" + longModel + "\",\"installSource\":\"store\","
                        + "\"isPhysicalDevice\":true,\"isProduction\":true,\"installerStore\":\"com.android.vending\","
                        + "\"screen\":{\"w\":1080}}"),
                BUILT, KEYS, USERS);
        DetailRow r = m.row();

        assertThat(r.platform()).isEqualTo("android");
        assertThat(r.appVersion()).isEqualTo("1.5.0");
        assertThat(r.osVersion()).isEqualTo("14");
        assertThat(r.deviceModel()).hasSize(DetailBuilder.MAX_TEXT);
        assertThat(r.production()).isTrue();
        assertThat(m.verdict()).isEqualTo(Verdict.PRODUCTION);
    }

    @Test
    void missingOrNonStringContextFieldsAreNull() {
        DetailRow r = DetailBuilder.toDetail(app("bottom_nav_tab_switched", null, null, "{}",
                "{\"platform\":1,\"appVersion\":\"  \"}"), BUILT, KEYS, USERS).row();

        assertThat(r.platform()).isNull();
        assertThat(r.appVersion()).isNull();
        assertThat(r.osVersion()).isNull();
        assertThat(r.deviceModel()).isNull();
    }

    @Test
    void fillsUserKeyForServerEventsAndKeepsExisting() {
        RawRow server = new RawRow("m-2", "order_paid", RECEIVED, RECEIVED, RECEIVED, null, null, 7L, "SERVER",
                "{}", "{}");
        DetailRow filled = DetailBuilder.toDetail(server, BUILT, KEYS, USERS).row();
        DetailRow kept = DetailBuilder.toDetail(app("bottom_nav_tab_switched", 7L, "abc", "{}", "{}"), BUILT, KEYS,
                USERS).row();

        assertThat(filled.userKey()).isEqualTo(AnalyticsDistinctId.of(7L));
        assertThat(filled.anonymousId()).isNull();
        assertThat(filled.source()).isEqualTo("SERVER");
        assertThat(kept.userKey()).isEqualTo("abc");
        assertThat(DetailBuilder.toDetail(app("x", null, null, "{}", "{}"), BUILT, KEYS, USERS).row().userKey())
                .isNull();
    }

    @Test
    void productionFlagCombinesContextAndUser() {
        String store = "{\"installSource\":\"store\",\"isPhysicalDevice\":true}";
        assertThat(DetailBuilder.toDetail(app("e", 8L, null, "{}", store), BUILT, KEYS, USERS).row().production())
                .as("VET").isFalse();
        assertThat(DetailBuilder.toDetail(app("e", 9L, null, "{}", store), BUILT, KEYS, USERS).row().production())
                .as("VIRTUAL").isFalse();
        Mapped unknown = DetailBuilder.toDetail(app("e", 404L, null, "{}", store), BUILT, KEYS, USERS);
        assertThat(unknown.row().production()).isFalse();
        assertThat(unknown.verdict()).isEqualTo(Verdict.UNKNOWN_USER);
        assertThat(DetailBuilder.toDetail(app("e", 7L, null, "{}", "{\"isPhysicalDevice\":false}"), BUILT, KEYS,
                USERS).row().production()).as("模拟器").isFalse();
    }

    @Test
    void suspiciousRowUsesReceivedDate() {
        RawRow future = new RawRow("m-3", "e", RECEIVED.plusSeconds(3600), RECEIVED, RECEIVED, "a", null, null,
                "APP", "{}", "{}");
        DetailRow r = DetailBuilder.toDetail(future, BUILT, KEYS, USERS).row();

        assertThat(r.suspicious()).isTrue();
        assertThat(r.eventTime()).isEqualTo(RECEIVED.plusSeconds(3600));
        assertThat(r.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    // ---------------------------------------------------------------- Story 1.8：注销擦除拦截

    private static final Function<String, Set<String>> SOCIAL_KEYS = e -> Set.of("owner_id", "target_user_id", "source");
    private static final Set<String> ID_KEYS = Set.of("owner_id", "target_user_id");
    /** 用户 7 已登记擦除；公开 token 为 tok7。 */
    private static final DetailBuilder.Erased ERASED_7 = new DetailBuilder.Erased(Set.of(7L),
            Set.of(AnalyticsDistinctId.of(7L)), Set.of("7", AnalyticsDistinctId.of(7L), "tok7"));

    @Test
    void erasedUsersOwnLateEventLosesIdentityButKeepsAnonymousId() {
        RawRow late = app("user_followed", 7L, AnalyticsDistinctId.of(7L),
                "{\"target_user_id\":99,\"owner_id\":\"tokX\",\"source\":\"feed\"}", "{}");

        DetailRow r = DetailBuilder.toDetail(late, BUILT, SOCIAL_KEYS, USERS, ERASED_7, ID_KEYS).row();

        assertThat(r.userId()).isNull();
        assertThat(r.userKey()).isNull();
        assertThat(r.anonymousId()).isEqualTo("4b0e3c0e-5f6a-4c41-9a39-0d1f6c2b7a10");
        // 本人行剥掉全部用户标识键（含指向别人的），非标识键保留
        assertThat(r.propertiesJson()).isEqualTo("{\"source\":\"feed\"}");
    }

    @Test
    void otherUsersEventDropsOnlyIdentifierValuesPointingToErasedUser() {
        RawRow byToken = app("pet_follow", 8L, null, "{\"owner_id\":\"tok7\",\"source\":\"feed\"}", "{}");
        RawRow byId = app("user_followed", 8L, null, "{\"target_user_id\":7,\"source\":\"feed\"}", "{}");
        RawRow unrelated = app("user_followed", 8L, null, "{\"target_user_id\":70,\"source\":\"feed\"}", "{}");

        DetailRow a = DetailBuilder.toDetail(byToken, BUILT, SOCIAL_KEYS, USERS, ERASED_7, ID_KEYS).row();
        DetailRow b = DetailBuilder.toDetail(byId, BUILT, SOCIAL_KEYS, USERS, ERASED_7, ID_KEYS).row();
        DetailRow c = DetailBuilder.toDetail(unrelated, BUILT, SOCIAL_KEYS, USERS, ERASED_7, ID_KEYS).row();

        assertThat(a.userId()).isEqualTo(8L);
        assertThat(a.propertiesJson()).isEqualTo("{\"source\":\"feed\"}");
        assertThat(b.propertiesJson()).isEqualTo("{\"source\":\"feed\"}");
        assertThat(c.propertiesJson()).isEqualTo("{\"target_user_id\":70,\"source\":\"feed\"}");
    }

    @Test
    void noErasedUsersLeavesRowUntouched() {
        RawRow raw = app("user_followed", 7L, null, "{\"target_user_id\":7}", "{}");

        DetailRow r = DetailBuilder.toDetail(raw, BUILT, SOCIAL_KEYS, USERS, DetailBuilder.Erased.NONE, ID_KEYS).row();

        assertThat(r.userId()).isEqualTo(7L);
        assertThat(r.userKey()).isEqualTo(AnalyticsDistinctId.of(7L));
        assertThat(r.propertiesJson()).isEqualTo("{\"target_user_id\":7}");
    }

    // ---------------------------------------------------------------- 跳过 / 失败

    @Test
    void skipsWhenPipelineDisabled() {
        state.disable(Reason.DB_UNAVAILABLE, "x");

        assertThat(builder.runOnce().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verifyNoInteractions(jdbc, business, tx, alerts);
    }

    @Test
    void skipsWhenCatalogInvalid() {
        // 清单无效 = 不知道该留哪些属性键，宁可不转
        state.disable(Reason.CATALOG_INVALID, "x");

        assertThat(builder.runOnce().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verifyNoInteractions(jdbc, business, tx, alerts);
    }

    @Test
    void failureAlertsKeyedByWibDayAndNeverThrows() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://secret-host"));

        DetailBuilder.RunResult r = builder.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(state.isEnabled()).isTrue();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-detail-failed:2027-01-01"),
                contains("DataAccessResourceFailureException"));
        verify(alerts, never()).send(any(), anyString(), contains("secret-host"));
    }

    @Test
    void alertFailureIsSwallowed() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenThrow(new IllegalStateException("boom"));
        doThrow(new RuntimeException("lark down")).when(alerts).send(any(), anyString(), anyString());

        assertThat(builder.runOnce().outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void partitionMissingMessageHasDateOnly() {
        DetailBuilder.DetailPartitionMissingException e =
                new DetailBuilder.DetailPartitionMissingException(LocalDate.of(2026, 11, 20));

        assertThat(e.date()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(e.getMessage()).contains("2026-11-20");
    }

    // ---------------------------------------------------------------- 时间片上界 / 追赶跳跃（AC3，复审补测）

    private static final Instant LIMIT = Instant.parse("2026-11-20T03:00:00Z");

    private static java.util.function.Supplier<Instant> noQuery() {
        return () -> {
            throw new AssertionError("落后不超过 1 小时时不应查下一条");
        };
    }

    @Test
    void sliceUpperCaughtUpWhenProgressAtOrAfterLimit() {
        assertThat(DetailBuilder.sliceUpper(LIMIT, LIMIT, noQuery())).isNull();
        assertThat(DetailBuilder.sliceUpper(LIMIT.plusSeconds(1), LIMIT, noQuery())).isNull();
    }

    @Test
    void sliceUpperIsFiveMinutesOrLimitWhenNotFarBehind() {
        Instant lower = LIMIT.minus(Duration.ofMinutes(30));
        assertThat(DetailBuilder.sliceUpper(lower, LIMIT, noQuery())).isEqualTo(lower.plus(Duration.ofMinutes(5)));
        Instant near = LIMIT.minus(Duration.ofMinutes(3));
        assertThat(DetailBuilder.sliceUpper(near, LIMIT, noQuery())).isEqualTo(LIMIT);
        Instant exactlyOneHour = LIMIT.minus(Duration.ofHours(1));
        assertThat(DetailBuilder.sliceUpper(exactlyOneHour, LIMIT, noQuery()))
                .isEqualTo(exactlyOneHour.plus(Duration.ofMinutes(5)));
    }

    @Test
    void sliceUpperJumpsFromEpochButStillCoversTheNextRow() {
        Instant epoch = Instant.parse("1970-01-01T00:00:00Z");
        Instant next = Instant.parse("2026-11-19T10:00:00.123456Z");

        Instant upper = DetailBuilder.sliceUpper(epoch, LIMIT, () -> next);

        // 本片处理 (lower, upper]：下一条待处理行必须落在区间内，且上界不越过 limit
        assertThat(upper).isAfterOrEqualTo(next).isBefore(LIMIT);
        assertThat(upper).isEqualTo(next.minusNanos(1_000).plus(Duration.ofMinutes(5)));
    }

    @Test
    void sliceUpperJumpsToLimitWhenNothingPending() {
        Instant lower = LIMIT.minus(Duration.ofDays(3));
        assertThat(DetailBuilder.sliceUpper(lower, LIMIT, () -> null)).isEqualTo(LIMIT);
    }

    @Test
    void sliceUpperJumpNeverExceedsLimitNorGoesBackwards() {
        Instant lower = LIMIT.minus(Duration.ofHours(2));
        // 下一条就在 lower 后 1 微秒：不得回退到 lower 之前
        Instant justAfter = lower.plusNanos(1_000);
        assertThat(DetailBuilder.sliceUpper(lower, LIMIT, () -> justAfter)).isEqualTo(lower.plus(Duration.ofMinutes(5)));
        // 下一条贴着 limit：上界封顶 limit，且包含该行
        Instant atLimit = LIMIT;
        assertThat(DetailBuilder.sliceUpper(lower, LIMIT, () -> atLimit)).isEqualTo(LIMIT);
    }
}
