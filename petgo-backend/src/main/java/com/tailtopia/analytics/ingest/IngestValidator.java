package com.tailtopia.analytics.ingest;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 逐条校验批次里的事件（analytics-pipeline Story 1.4 AC2）—— 纯函数，无状态、无 IO。
 *
 * <p>必填（缺任一或格式非法 → <b>只拒该条</b>，计入 {@code rejected}）：
 * <ul>
 *   <li>{@code messageId}：非空字符串，≤64 字符；</li>
 *   <li>{@code event}：非空字符串，≤128 字符（原样，不改大小写）；</li>
 *   <li>{@code timestamp} / {@code sentAt}：ISO-8601 且<b>带时区偏移</b>，年份 0001–9999；</li>
 *   <li>{@code anonymousId}：标准 UUID 字符串（{@code UUID.fromString} 回写后与原值忽略大小写一致，防 {@code 1-1-1-1-1}）。</li>
 * </ul>
 * 可选（类型不对 → <b>丢掉该字段</b>，不拒整条）：{@code userId}（字符串，≤64）、{@code properties}（对象）、
 * {@code context}（对象），缺省按空。
 *
 * <p>额外防护：字符串里含 {@code \u0000} 时 PostgreSQL 的 varchar / jsonb 都拒收，会让 flusher 整批写失败、
 * 连累同批其他事件。必填字段含 NUL → 拒该条；可选字段含 NUL → 丢该字段。年份越界同理（PG timestamptz 有范围）。
 */
public final class IngestValidator {

    static final int MAX_MESSAGE_ID = 64;
    static final int MAX_EVENT = 128;
    static final int MAX_USER_ID = 64;

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Instant MIN_TS = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TS = Instant.parse("9999-12-31T23:59:59.999999999Z");

    /** 校验结果：通过的事件（身份未判定，{@code userKey} 放声明的 {@code userId} 原值）+ 拒收条数。 */
    public record Result(List<RawEvent> accepted, int rejected) {
    }

    private IngestValidator() {
    }

    /**
     * 校验一批。
     *
     * @param batch      {@code batch} 数组的元素
     * @param receivedAt 服务端收到时间（同批同值）
     * @param registered 事件名是否已在清单登记（{@code EventCatalog::isRegistered}），未登记照收、标 {@code unknownEvent}
     */
    public static Result validate(Iterable<JsonNode> batch, Instant receivedAt, Predicate<String> registered) {
        List<RawEvent> accepted = new ArrayList<>();
        int rejected = 0;
        for (JsonNode node : batch) {
            RawEvent e = validateOne(node, receivedAt, registered);
            if (e == null) {
                rejected++;
            } else {
                accepted.add(e);
            }
        }
        return new Result(List.copyOf(accepted), rejected);
    }

    /** 单条；不合格返回 {@code null}。 */
    static RawEvent validateOne(JsonNode node, Instant receivedAt, Predicate<String> registered) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String messageId = requiredString(node, "messageId", MAX_MESSAGE_ID);
        String event = requiredString(node, "event", MAX_EVENT);
        Instant clientTs = requiredInstant(node, "timestamp");
        Instant sentAt = requiredInstant(node, "sentAt");
        String anonymousId = requiredUuid(node, "anonymousId");
        if (messageId == null || event == null || clientTs == null || sentAt == null || anonymousId == null) {
            return null;
        }
        String userId = optionalString(node, "userId", MAX_USER_ID);
        String properties = optionalObjectJson(node, "properties");
        String context = optionalObjectJson(node, "context");
        return RawEvent.of(messageId, event, clientTs, sentAt, receivedAt, anonymousId, userId, null,
                RawEvent.Source.APP, properties, context, false, !registered.test(event));
    }

    private static String requiredString(JsonNode node, String field, int maxLen) {
        JsonNode v = node.get(field);
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.stringValue();
        if (s.isBlank() || s.length() > maxLen || s.indexOf('\u0000') >= 0) {
            return null;
        }
        return s;
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isString()) {
            return null;
        }
        try {
            Instant t = OffsetDateTime.parse(v.stringValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            return t.isBefore(MIN_TS) || t.isAfter(MAX_TS) ? null : t;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String requiredUuid(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.stringValue();
        try {
            String canonical = UUID.fromString(s).toString();
            return canonical.equalsIgnoreCase(s) ? s.toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String optionalString(JsonNode node, String field, int maxLen) {
        JsonNode v = node.get(field);
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.stringValue();
        if (s.isEmpty() || s.length() > maxLen || s.indexOf('\u0000') >= 0) {
            return null;
        }
        return s;
    }

    private static String optionalObjectJson(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isObject()) {
            return null;
        }
        String json = JSON.writeValueAsString(v);
        // Jackson 把 NUL 转义成 \u0000；jsonb 拒收这个转义。
        return json.contains("\\u0000") ? null : json;
    }
}
