package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shared.analytics.AnonymousVisitorId;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：服务端事件改走自建（analytics-pipeline Story 1.9 AC2），跑在真业务库 + 埋点测试库 {@code petgo_analytics_test}
 * （前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>Toko 付款成功（造单路径同 {@code ShopOrderPaymentIntegrationTest#paidEmitsShopPaymentPaid}）→ 真实
 *       {@code SelfHostedAnalyticsClient} 入队 → 上下文里的 {@code IngestFlusher}（每 1 秒）落 {@code events_raw} →
 *       手工跑一次 {@link DetailBuilder#runOnce()} → {@code events_detail} 有 {@code source='SERVER'}、{@code user_id} 为下单人。</li>
 *   <li>分享页打开 → {@code source='H5'} 行，匿名 id = {@code tt_vid}，{@code identity_links} 无新增。</li>
 *   <li>{@code DetailBuilder} 只处理到 {@code now − 2 分钟}：用例里 new 一个时钟拨快 3 分钟的实例，让刚落库的行可被转换。</li>
 *   <li>隔离：按本用例的下单人 {@code user_id} / 访客 id 删原始层与明细层，不 TRUNCATE。</li>
 * </ul>
 */
class ServerEventsIntegrationTest extends ServerEventsTestSupport {

    private static final Duration FLUSH_WAIT = Duration.ofSeconds(15);

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;
    @Autowired
    private EventCatalog catalog;
    @Autowired
    private ContentPostRepository posts;

    private Long cleanupUserId;
    private String cleanupVisitorId;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        Instant now = Instant.now();
        LocalDate today = EventTimeCorrector.wibDate(now);
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = today.minusDays(1); !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
                DetailPartitions.ensure(analyticsJdbc, d);
            }
        });
        // 共享测试库：把明细进度回拨到本用例之前（重放别人留下的行是幂等的）
        analyticsJdbc.update("UPDATE pipeline_progress SET last_received_at = ? WHERE pipeline = 'detail'",
                OffsetDateTime.ofInstant(now.minus(Duration.ofMinutes(5)), ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        if (cleanupUserId != null) {
            analyticsJdbc.update("DELETE FROM events_detail WHERE user_id = ?", cleanupUserId);
            analyticsJdbc.update("DELETE FROM events_raw WHERE user_id = ?", cleanupUserId);
        }
        if (cleanupVisitorId != null) {
            analyticsJdbc.update("DELETE FROM events_detail WHERE anonymous_id = ?", cleanupVisitorId);
            analyticsJdbc.update("DELETE FROM events_raw WHERE anonymous_id = ?", cleanupVisitorId);
        }
    }

    /** 时钟拨快 3 分钟的明细转换器：刚落库的行越过 2 分钟水位线，可被本轮转换。 */
    private DetailBuilder fastForwardBuilder() {
        return new DetailBuilder(state, analyticsJdbc, analyticsTx, jdbc, catalog, mock(OpsAlertService.class), "test",
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(3)), Duration.ofSeconds(50));
    }

    private static void await(String what, Supplier<Boolean> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(FLUSH_WAIT);
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("等待超时：" + what);
            }
            Thread.sleep(200);
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void paidOrderLandsInDetailAsServerEventOfTheBuyer() throws Exception {
        long uid = payOneOrder().userId();
        cleanupUserId = uid;

        await("shop_payment_paid 落 events_raw", () -> analyticsJdbc.queryForObject(
                "SELECT count(*) FROM events_raw WHERE event = 'shop_payment_paid' AND user_id = ?",
                Long.class, uid) == 1L);

        Map<String, Object> raw = analyticsJdbc.queryForMap(
                "SELECT source, user_key, anonymous_id, identity_unverified, unknown_event FROM events_raw "
                        + "WHERE event = 'shop_payment_paid' AND user_id = ?", uid);
        assertThat(raw.get("source")).isEqualTo("SERVER");
        assertThat(raw.get("user_key")).isEqualTo(AnalyticsDistinctId.of(uid));
        assertThat(raw.get("anonymous_id")).isNull();
        assertThat(raw.get("identity_unverified")).isEqualTo(false);
        assertThat(raw.get("unknown_event")).isEqualTo(false);

        fastForwardBuilder().runOnce();

        List<Map<String, Object>> detail = analyticsJdbc.queryForList(
                "SELECT source, user_id FROM events_detail WHERE event = 'shop_payment_paid' AND user_id = ?", uid);
        assertThat(detail).hasSize(1);
        assertThat(detail.getFirst().get("source")).isEqualTo("SERVER");
        assertThat(((Number) detail.getFirst().get("user_id")).longValue()).isEqualTo(uid);
    }

    @Test
    void sharePageOpenLandsAsH5WithoutIdentityLink() throws Exception {
        User author = newUser();
        ContentPost p = posts.save(ContentPost.publish(author.getId(), ContentType.GROWTH_MOMENT,
                null, "1.9 用的那条", List.of("https://img.example/1.jpg")));
        String body = mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shareToken = json.readTree(body).get("shareToken").asText();

        Cookie vid = mvc.perform(get("/c/" + shareToken)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME);
        assertThat(vid).isNotNull();
        String visitorId = vid.getValue();
        cleanupVisitorId = visitorId;
        long linksBefore = analyticsJdbc.queryForObject(
                "SELECT count(*) FROM identity_links WHERE anonymous_id = ?", Long.class, visitorId);

        await("post_share_link_opened 落 events_raw", () -> analyticsJdbc.queryForObject(
                "SELECT count(*) FROM events_raw WHERE event = 'post_share_link_opened' AND anonymous_id = ?",
                Long.class, visitorId) == 1L);

        Map<String, Object> raw = analyticsJdbc.queryForMap(
                "SELECT source, user_id, user_key FROM events_raw "
                        + "WHERE event = 'post_share_link_opened' AND anonymous_id = ?", visitorId);
        assertThat(raw.get("source")).isEqualTo("H5");
        assertThat(raw.get("user_id")).isNull();
        assertThat(raw.get("user_key")).isNull();

        fastForwardBuilder().runOnce();

        assertThat(analyticsJdbc.queryForList(
                "SELECT source FROM events_detail WHERE event = 'post_share_link_opened' AND anonymous_id = ?",
                String.class, visitorId)).containsExactly("H5");
        assertThat(analyticsJdbc.queryForObject(
                "SELECT count(*) FROM identity_links WHERE anonymous_id = ?", Long.class, visitorId))
                .as("H5 访客不进关联表").isEqualTo(linksBefore);
    }
}
