package com.tailtopia.shared.analytics;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.ingest.IngestQueue;
import com.tailtopia.analytics.ingest.RawEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AnalyticsClient} 的自建实现（analytics-pipeline Story 1.9，架构 A4 / 服务端事件）：
 * 把服务端事件组装成 {@link RawEvent} 直接放进 Story 1.4 的 {@link IngestQueue}，由 {@code IngestFlusher} 批量写埋点库原始层。
 *
 * <p>🔴 本类是<b>唯一</b>允许直接注入 {@link IngestQueue} 的非 ingest 类；业务模块一律只经 {@link AnalyticsClient}。
 *
 * <h2>两条入口</h2>
 * <ul>
 *   <li>{@link #captureForUser}：{@code source=SERVER}、{@code user_id} 直接给出并视为已校验
 *       （{@code identity_unverified=false}），{@code user_key = AnalyticsDistinctId.of(userId)}，
 *       {@code anonymous_id} 为空，<b>不经过 {@code IdentityResolver}</b>。</li>
 *   <li>{@link #captureForVisitor}：{@code source=H5}、{@code anonymous_id} = 访客 id（32 位小写 hex，原样入
 *       {@code varchar(64)}，不转 UUID），不带 {@code user_id} / {@code user_key}，<b>不写 {@code identity_links}</b>
 *       （H5 访客不是 App 设备）。访客 id 为空或格式不合法 → 丢弃计数。</li>
 * </ul>
 *
 * <h2>🔴 故障不伤业务（AC3）</h2>
 * 管道停用（{@link AnalyticsPipelineState}，{@code IngestQueue#offer} 自身不判断停用）、队列满 / 已关闭、
 * 将来 Story 1.10 的熔断（落在同一个 {@code offer} 判定点），以及任何异常 → 丢弃、按原因计数，<b>任何路径不抛异常</b>。
 * 服务端事件无法重试，不排队等待。每 5 分钟若有丢弃打一条汇总 INFO（只含按原因分的条数）。
 *
 * <p>不加 {@code @Async}：入队是内存非阻塞操作，调用方都是 {@code AFTER_COMMIT} 监听器或分享页请求线程，直接在调用线程入队。
 *
 * <p>日志只打事件名与条数，<b>不打 userId、访客 id、属性</b>。不再注入 {@code app_env} / {@code $geoip_disable} /
 * {@code distinct_id} 等 PostHog 专用键：环境由「哪个后端写进哪个库」天然隔离（D-1）。
 */
@Component
public class SelfHostedAnalyticsClient implements AnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(SelfHostedAnalyticsClient.class);

    /** 丢弃原因（汇总日志按此分组）。 */
    public enum DropReason {
        /** 事件名未进 {@link AnalyticsEventGuard} 白名单。 */
        GUARD_REJECTED,
        /** 埋点管道停用（库连不上 / 迁移失败 / 清单无效）。 */
        PIPELINE_DISABLED,
        /** 队列满（或将来的熔断）。 */
        QUEUE_FULL,
        /** 队列已关闭（停机中）。 */
        QUEUE_CLOSED,
        /** 访客 id 为空或格式不合法。 */
        INVALID_VISITOR_ID,
        /** 组装 / 入队过程中的任何异常。 */
        ERROR
    }

    static final String EMPTY_JSON = "{}";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AnalyticsEventGuard guard;
    private final IngestQueue queue;
    private final AnalyticsPipelineState state;
    private final EventCatalog catalog;
    private final Clock clock;

    private final Map<DropReason, AtomicLong> dropped = new EnumMap<>(DropReason.class);
    private final AtomicLong droppedCount = new AtomicLong();

    @Autowired
    public SelfHostedAnalyticsClient(AnalyticsEventGuard guard, IngestQueue queue, AnalyticsPipelineState state,
            EventCatalog catalog) {
        this(guard, queue, state, catalog, Clock.systemUTC());
    }

    /** 测试用：可替换时钟。 */
    SelfHostedAnalyticsClient(AnalyticsEventGuard guard, IngestQueue queue, AnalyticsPipelineState state,
            EventCatalog catalog, Clock clock) {
        this.guard = guard;
        this.queue = queue;
        this.state = state;
        this.catalog = catalog;
        this.clock = clock;
        for (DropReason r : DropReason.values()) {
            dropped.put(r, new AtomicLong());
        }
    }

    @Override
    public void captureForUser(long userId, String event, Map<String, Object> properties) {
        try {
            if (!guard.allowsEvent(event)) {
                drop(DropReason.GUARD_REJECTED);
                return;
            }
            enqueue(event, properties, null, AnalyticsDistinctId.of(userId), userId, RawEvent.Source.SERVER);
        } catch (RuntimeException e) {
            dropOnError(event, e);
        }
    }

    @Override
    public void captureForVisitor(String visitorId, String event, Map<String, Object> properties) {
        try {
            if (!guard.allowsEvent(event)) {
                drop(DropReason.GUARD_REJECTED);
                return;
            }
            if (!AnonymousVisitorId.isWellFormed(visitorId)) {
                drop(DropReason.INVALID_VISITOR_ID);
                return;
            }
            enqueue(event, properties, visitorId, null, null, RawEvent.Source.H5);
        } catch (RuntimeException e) {
            dropOnError(event, e);
        }
    }

    private void enqueue(String event, Map<String, Object> properties, String anonymousId, String userKey,
            Long userId, RawEvent.Source source) {
        // IngestQueue#offer 本身不判断管道停用（1.4 复审 R4），这里自己判。
        if (!state.isEnabled()) {
            drop(DropReason.PIPELINE_DISABLED);
            return;
        }
        Map<String, Object> props = guard.filterProperties(properties);
        // jsonb 拒收 \u0000：一条带 NUL 的属性会让 flusher 整批写失败、连累同批其他事件 —— 只丢该键。
        props.values().removeIf(v -> v instanceof String s && s.indexOf('\u0000') >= 0);
        Instant now = clock.instant();
        RawEvent raw = RawEvent.of(UUID.randomUUID().toString(), event, now, now, now, anonymousId, userKey, userId,
                source, toJson(props), EMPTY_JSON, false, !catalog.isRegistered(event));
        if (!queue.offer(raw)) {
            drop(queue.isClosed() ? DropReason.QUEUE_CLOSED : DropReason.QUEUE_FULL);
        }
    }

    private static String toJson(Map<String, Object> props) {
        return props.isEmpty() ? EMPTY_JSON : JSON.writeValueAsString(props);
    }

    private void dropOnError(String event, RuntimeException e) {
        // 事件名可入日志（受控字面量）；属性、用户 / 访客标识一律不打。
        log.warn("server analytics event dropped: event={} reason={}", event, e.getClass().getSimpleName());
        drop(DropReason.ERROR);
    }

    private void drop(DropReason reason) {
        dropped.get(reason).incrementAndGet();
        droppedCount.incrementAndGet();
    }

    /**
     * 5 分钟汇总日志：有丢弃时打一条 INFO（按原因分的条数）并清零窗口计数。
     *
     * @return 本窗口丢弃总条数（0 = 未打日志）
     */
    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    long logDroppedSummary() {
        long total = 0;
        StringJoiner parts = new StringJoiner(" ");
        for (Map.Entry<DropReason, AtomicLong> e : dropped.entrySet()) {
            long n = e.getValue().getAndSet(0);
            if (n > 0) {
                total += n;
                parts.add(e.getKey().name() + "=" + n);
            }
        }
        if (total > 0) {
            log.info("server analytics dropped count={} window=5m {}", total, parts);
        }
        return total;
    }

    /** 启动以来累计丢弃条数（不随汇总清零）。 */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** 当前窗口（尚未汇总）某原因的丢弃条数。 */
    long droppedSinceLastSummary(DropReason reason) {
        return dropped.get(reason).get();
    }
}
