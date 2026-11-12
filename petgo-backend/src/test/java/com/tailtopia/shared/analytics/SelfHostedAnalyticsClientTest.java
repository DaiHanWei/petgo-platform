package com.tailtopia.shared.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.ingest.IngestQueue;
import com.tailtopia.analytics.ingest.RawEvent;
import com.tailtopia.shared.analytics.SelfHostedAnalyticsClient.DropReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：{@link SelfHostedAnalyticsClient}（analytics-pipeline Story 1.9 AC2 / AC3 / AC5）。纯单测，无 Spring、无库。
 *
 * <p>护栏用例（被拒事件不入队、属性被过滤）由已删除的 {@code PostHogAnalyticsClientTest} 改写而来。
 */
class SelfHostedAnalyticsClientTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:04:05Z");
    private static final String VISITOR = "0123456789abcdef0123456789abcdef";

    private final AnalyticsEventGuard guard = new AnalyticsEventGuard();
    private final IngestQueue queue = mock(IngestQueue.class);
    private final EventCatalog catalog = mock(EventCatalog.class);
    private AnalyticsPipelineState state;
    private SelfHostedAnalyticsClient client;

    @BeforeEach
    void setUp() {
        state = new AnalyticsPipelineState();
        when(queue.offer(any())).thenReturn(true);
        when(catalog.isRegistered(anyString())).thenReturn(true);
        client = new SelfHostedAnalyticsClient(guard, queue, state, catalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RawEvent offered() {
        ArgumentCaptor<RawEvent> cap = ArgumentCaptor.forClass(RawEvent.class);
        verify(queue).offer(cap.capture());
        return cap.getValue();
    }

    private static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    // ---------------------------------------------------------------- AC2：入队行形态

    @Test
    @DisplayName("captureForUser → source=SERVER、user_id 原样、user_key=AnalyticsDistinctId、已校验、无匿名 id")
    void userEventShape() {
        client.captureForUser(42L, "shop_payment_paid", Map.of("pay_channel", "QRIS"));

        RawEvent e = offered();
        assertThat(e.source()).isEqualTo(RawEvent.Source.SERVER);
        assertThat(e.userId()).isEqualTo(42L);
        assertThat(e.userKey()).isEqualTo(AnalyticsDistinctId.of(42L));
        assertThat(e.identityUnverified()).isFalse();
        assertThat(e.anonymousId()).isNull();
        assertThat(e.identityLink()).as("服务端事件永远不带关联").isNull();
        assertThat(e.event()).isEqualTo("shop_payment_paid");
        assertThat(e.unknownEvent()).isFalse();
        assertThat(e.clientTs()).isEqualTo(NOW);
        assertThat(e.sentAt()).isEqualTo(NOW);
        assertThat(e.receivedAt()).isEqualTo(NOW);
        assertThat(UUID.fromString(e.messageId())).isNotNull();
        assertThat(e.contextJson()).isEqualTo("{}");
        assertThat(json(e.propertiesJson()).get("pay_channel").stringValue()).isEqualTo("QRIS");
    }

    @Test
    @DisplayName("不再注入 PostHog 专用键（distinct_id / app_env / $geoip_disable）")
    void noPosthogFrameworkKeys() {
        client.captureForUser(42L, "shop_payment_paid", Map.of("pay_channel", "QRIS"));

        JsonNode props = json(offered().propertiesJson());
        assertThat(props.has("distinct_id")).isFalse();
        assertThat(props.has("app_env")).isFalse();
        assertThat(props.has("$geoip_disable")).isFalse();
    }

    @Test
    @DisplayName("每条事件 messageId 唯一")
    void messageIdIsUniquePerEvent() {
        client.captureForUser(1L, "shop_payment_paid", Map.of());
        client.captureForUser(1L, "shop_payment_paid", Map.of());

        ArgumentCaptor<RawEvent> cap = ArgumentCaptor.forClass(RawEvent.class);
        verify(queue, org.mockito.Mockito.times(2)).offer(cap.capture());
        assertThat(cap.getAllValues().get(0).messageId()).isNotEqualTo(cap.getAllValues().get(1).messageId());
    }

    @Test
    @DisplayName("captureForVisitor → source=H5、匿名 id 为 32 位 hex 原样（列是 varchar），不带 user_id / user_key")
    void visitorEventShape() {
        client.captureForVisitor(VISITOR, "post_share_link_opened", Map.of("open_method", "DIRECT"));

        RawEvent e = offered();
        assertThat(e.source()).isEqualTo(RawEvent.Source.H5);
        assertThat(e.anonymousId()).isEqualTo(VISITOR);
        assertThat(e.userId()).isNull();
        assertThat(e.userKey()).isNull();
        assertThat(e.identityUnverified()).isFalse();
        assertThat(e.identityLink()).as("H5 访客不进关联表").isNull();
    }

    @Test
    @DisplayName("访客 id 为空 / 格式不合法 → 丢弃计数，不入队")
    void blankOrMalformedVisitorIdDropped() {
        client.captureForVisitor(null, "post_share_link_opened", Map.of());
        client.captureForVisitor("", "post_share_link_opened", Map.of());
        client.captureForVisitor("0123456789ABCDEF0123456789ABCDEF", "post_share_link_opened", Map.of());
        client.captureForVisitor("01234567-89ab-cdef-0123-456789abcdef", "post_share_link_opened", Map.of());

        verify(queue, never()).offer(any());
        assertThat(client.droppedCount()).isEqualTo(4);
        assertThat(client.droppedSinceLastSummary(DropReason.INVALID_VISITOR_ID)).isEqualTo(4);
    }

    @Test
    @DisplayName("unknown_event 按事件清单判定（与 1.4 接收端同一个 EventCatalog#isRegistered）")
    void unknownEventFlagFollowsCatalog() {
        when(catalog.isRegistered("shop_payment_paid")).thenReturn(false);

        client.captureForUser(42L, "shop_payment_paid", Map.of());

        assertThat(offered().unknownEvent()).isTrue();
    }

    // ---------------------------------------------------------------- AC5：护栏照常生效

    @Test
    @DisplayName("🔴 被 guard 拒掉的事件名 → 不入队")
    void rejectedEventNeverEnqueued() {
        client.captureForUser(42L, "not_a_registered_event", Map.of("code", "X"));
        client.captureForVisitor(VISITOR, "not_a_registered_event", Map.of());

        verify(queue, never()).offer(any());
        assertThat(client.droppedSinceLastSummary(DropReason.GUARD_REJECTED)).isEqualTo(2);
    }

    @Test
    @DisplayName("🔒 白名单外的属性键 / 不合规值被过滤，事件本身照入队")
    void disallowedPropertiesFiltered() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("pay_channel", "QRIS");
        raw.put("order_amount", 100L);
        raw.put("receiver_name", "Budi");
        raw.put("has_pawcoin", Map.of("nested", true));

        client.captureForUser(42L, "shop_payment_paid", raw);

        JsonNode props = json(offered().propertiesJson());
        assertThat(props.propertyNames()).containsExactly("pay_channel");
    }

    @Test
    @DisplayName("属性值含 NUL（jsonb 拒收，会连累整批）→ 只丢该键")
    void nulValueDropped() {
        client.captureForUser(42L, "place_checkin", Map.of("place_type", "a\u0000b", "is_new_stamp", true));

        JsonNode props = json(offered().propertiesJson());
        assertThat(props.propertyNames()).containsExactly("is_new_stamp");
    }

    @Test
    @DisplayName("tailsonality_match_unlocked 能真正入队（1-2 发现：原先被白名单整条丢）")
    void tailsonalityMatchUnlockedEnqueued() {
        client.captureForUser(42L, "tailsonality_match_unlocked", Map.of("role_code", "ENTJ-H", "result_index", 1));

        RawEvent e = offered();
        assertThat(e.event()).isEqualTo("tailsonality_match_unlocked");
        assertThat(json(e.propertiesJson()).propertyNames()).containsExactlyInAnyOrder("role_code", "result_index");
    }

    // ---------------------------------------------------------------- AC3：故障不伤业务

    @Test
    @DisplayName("管道停用 → 不入队、计数 +1、不抛")
    void pipelineDisabledDrops() {
        state.disable(AnalyticsPipelineState.Reason.DB_UNAVAILABLE, "test");

        assertThatCode(() -> client.captureForUser(42L, "shop_payment_paid", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> client.captureForVisitor(VISITOR, "post_share_link_opened", Map.of()))
                .doesNotThrowAnyException();

        verify(queue, never()).offer(any());
        assertThat(client.droppedCount()).isEqualTo(2);
        assertThat(client.droppedSinceLastSummary(DropReason.PIPELINE_DISABLED)).isEqualTo(2);
    }

    @Test
    @DisplayName("队列拒绝（满）→ 不抛、计数 +1")
    void queueFullDrops() {
        when(queue.offer(any())).thenReturn(false);

        assertThatCode(() -> client.captureForUser(42L, "shop_payment_paid", Map.of())).doesNotThrowAnyException();

        assertThat(client.droppedCount()).isEqualTo(1);
        assertThat(client.droppedSinceLastSummary(DropReason.QUEUE_FULL)).isEqualTo(1);
    }

    @Test
    @DisplayName("队列已关闭（停机中）→ 不抛、按 QUEUE_CLOSED 计数")
    void queueClosedDrops() {
        when(queue.offer(any())).thenReturn(false);
        when(queue.isClosed()).thenReturn(true);

        client.captureForUser(42L, "shop_payment_paid", Map.of());

        assertThat(client.droppedSinceLastSummary(DropReason.QUEUE_CLOSED)).isEqualTo(1);
    }

    @Test
    @DisplayName("入队抛异常 → 吞掉、计数 +1")
    void queueThrowsIsSwallowed() {
        when(queue.offer(any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> client.captureForUser(42L, "shop_payment_paid", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> client.captureForVisitor(VISITOR, "post_share_link_opened", Map.of()))
                .doesNotThrowAnyException();

        assertThat(client.droppedCount()).isEqualTo(2);
        assertThat(client.droppedSinceLastSummary(DropReason.ERROR)).isEqualTo(2);
    }

    @Test
    @DisplayName("清单判定抛异常 → 吞掉、计数 +1")
    void catalogThrowsIsSwallowed() {
        when(catalog.isRegistered(anyString())).thenThrow(new RuntimeException("x"));

        assertThatCode(() -> client.captureForUser(42L, "shop_payment_paid", Map.of())).doesNotThrowAnyException();

        verify(queue, never()).offer(any());
        assertThat(client.droppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("5 分钟汇总：有丢弃返回条数并清零窗口；累计数不清零；无丢弃返回 0")
    void summaryResetsWindowOnly() {
        when(queue.offer(any())).thenReturn(false);
        client.captureForUser(42L, "shop_payment_paid", Map.of());
        client.captureForVisitor(null, "post_share_link_opened", Map.of());

        assertThat(client.logDroppedSummary()).isEqualTo(2);
        assertThat(client.droppedSinceLastSummary(DropReason.QUEUE_FULL)).isZero();
        assertThat(client.logDroppedSummary()).isZero();
        assertThat(client.droppedCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 废弃入口

    @Test
    @SuppressWarnings("removal")
    @DisplayName("废弃的 capture(distinctId, …) → 不抛、不入队（distinctId 反推不出 user_id）")
    void deprecatedCaptureIsNoop() {
        assertThatCode(() -> client.capture(AnalyticsDistinctId.of(42L), "shop_payment_paid", Map.of()))
                .doesNotThrowAnyException();

        verify(queue, never()).offer(any());
    }
}
