package com.tailtopia.notify.ops.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运营通知人群规格（V1.3.4 Story 6.1 · AC4.5），结构逐字取决策日志 D-16：
 *
 * <pre>{@code
 * { "mode": "ALL" | "FILTER" | "IDS",
 *   "registeredFrom"?: "yyyy-MM-dd", "registeredTo"?: "yyyy-MM-dd",
 *   "activeFrom"?: "yyyy-MM-dd",     "activeTo"?: "yyyy-MM-dd",
 *   "hasPet"?: true|false,
 *   "species"?: ["CAT","DOG","OTHER"],
 *   "hasShopOrder"?: true|false,
 *   "hasPhone"?: true|false }
 * }</pre>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li><b>未出现的键 = 不筛</b>（record 字段为 {@code null}）；JSON 里显式写 {@code null} 视同未出现。</li>
 *   <li>日期按 <b>WIB（{@code Asia/Jakarta}）自然日、含两端</b>（人群解析在 Story 6.2）。</li>
 *   <li>{@code IDS} 模式的用户 id <b>一律存子表</b> {@code ops_notification_campaign_targets}，不进 JSON。</li>
 *   <li>{@code ALL} / {@code IDS} 模式下其余筛选键<b>忽略</b>：解析时直接丢弃，{@link #toJson()} 不再写出。</li>
 *   <li>{@code species} 为空数组视同未出现。</li>
 * </ul>
 *
 * <h2>校验</h2>
 * 解析失败或不合法统一抛 {@code AppException.validation(...).code("admin.err.ops-notify.audience.invalid")}：
 * {@code mode} 缺失 / 非三值之一；日期非 {@code yyyy-MM-dd}；{@code registeredFrom > registeredTo} 或
 * {@code activeFrom > activeTo}；{@code species} 含三值以外的值；布尔键给了非布尔；出现 D-16 以外的键；
 * 另：非合法 JSON、尾随垃圾、重复键。
 */
public record AudienceSpec(
        Mode mode,
        LocalDate registeredFrom,
        LocalDate registeredTo,
        LocalDate activeFrom,
        LocalDate activeTo,
        Boolean hasPet,
        Set<PetType> species,
        Boolean hasShopOrder,
        Boolean hasPhone) {

    /** 人群模式。 */
    public enum Mode {
        ALL,
        FILTER,
        IDS
    }

    /** 文案码（四语文案随 admin 主题落）。 */
    public static final String INVALID_CODE = "admin.err.ops-notify.audience.invalid";

    /** 严格解析：尾随垃圾（{@code {...}xyz}）与重复键（后值静默覆盖前值）一律判非法。 */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Set<String> KEYS = Set.of("mode", "registeredFrom", "registeredTo", "activeFrom",
            "activeTo", "hasPet", "species", "hasShopOrder", "hasPhone");

    /**
     * 规范化：{@code mode} 必填；{@code ALL} / {@code IDS} 丢弃全部筛选键；{@code species} 空集 → null；
     * 两组日期区间起点不得晚于终点。
     */
    public AudienceSpec {
        if (mode == null) {
            throw invalid("mode 缺失");
        }
        if (mode != Mode.FILTER) {
            registeredFrom = null;
            registeredTo = null;
            activeFrom = null;
            activeTo = null;
            hasPet = null;
            species = null;
            hasShopOrder = null;
            hasPhone = null;
        }
        species = species == null || species.isEmpty()
                ? null
                : Collections.unmodifiableSet(EnumSet.copyOf(species));
        if (registeredFrom != null && registeredTo != null && registeredFrom.isAfter(registeredTo)) {
            throw invalid("registeredFrom 晚于 registeredTo");
        }
        if (activeFrom != null && activeTo != null && activeFrom.isAfter(activeTo)) {
            throw invalid("activeFrom 晚于 activeTo");
        }
    }

    public static AudienceSpec all() {
        return new AudienceSpec(Mode.ALL, null, null, null, null, null, null, null, null);
    }

    public static AudienceSpec ids() {
        return new AudienceSpec(Mode.IDS, null, null, null, null, null, null, null, null);
    }

    /** 解析并校验 D-16 JSON。 */
    public static AudienceSpec parse(String json) {
        if (json == null || json.isBlank()) {
            throw invalid("人群规格为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("人群规格不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid("人群规格必须是 JSON 对象");
        }
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            String key = e.getKey();
            if (!KEYS.contains(key)) {
                throw invalid("未知键 " + key);
            }
        }
        Mode mode = parseMode(root.get("mode"));
        if (mode != Mode.FILTER) {
            return new AudienceSpec(mode, null, null, null, null, null, null, null, null);
        }
        return new AudienceSpec(mode,
                date(root, "registeredFrom"), date(root, "registeredTo"),
                date(root, "activeFrom"), date(root, "activeTo"),
                bool(root, "hasPet"),
                species(root.get("species")),
                bool(root, "hasShopOrder"),
                bool(root, "hasPhone"));
    }

    /** 序列化：省略 null 键，日期写 {@code yyyy-MM-dd}，{@code species} 按枚举序。 */
    public String toJson() {
        ObjectNode o = JSON.createObjectNode();
        o.put("mode", mode.name());
        putDate(o, "registeredFrom", registeredFrom);
        putDate(o, "registeredTo", registeredTo);
        putDate(o, "activeFrom", activeFrom);
        putDate(o, "activeTo", activeTo);
        if (hasPet != null) {
            o.put("hasPet", hasPet);
        }
        if (species != null) {
            ArrayNode arr = o.putArray("species");
            species.forEach(s -> arr.add(s.name()));
        }
        if (hasShopOrder != null) {
            o.put("hasShopOrder", hasShopOrder);
        }
        if (hasPhone != null) {
            o.put("hasPhone", hasPhone);
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AudienceSpec 序列化失败", e);
        }
    }

    // ---------- 解析辅助 ----------

    private static Mode parseMode(JsonNode n) {
        if (n == null || n.isNull()) {
            throw invalid("mode 缺失");
        }
        if (!n.isTextual()) {
            throw invalid("mode 不是字符串");
        }
        try {
            return Mode.valueOf(n.asText());
        } catch (IllegalArgumentException e) {
            throw invalid("mode 非 ALL / FILTER / IDS");
        }
    }

    private static LocalDate date(JsonNode root, String key) {
        JsonNode n = root.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual() || !DATE.matcher(n.asText()).matches()) {
            throw invalid(key + " 不是 yyyy-MM-dd");
        }
        try {
            return LocalDate.parse(n.asText());
        } catch (DateTimeParseException e) {
            throw invalid(key + " 不是合法日期");
        }
    }

    private static Boolean bool(JsonNode root, String key) {
        JsonNode n = root.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isBoolean()) {
            throw invalid(key + " 不是布尔值");
        }
        return n.booleanValue();
    }

    private static Set<PetType> species(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw invalid("species 不是数组");
        }
        EnumSet<PetType> out = EnumSet.noneOf(PetType.class);
        for (JsonNode e : n) {
            if (!e.isTextual()) {
                throw invalid("species 含非字符串");
            }
            try {
                out.add(PetType.valueOf(e.asText()));
            } catch (IllegalArgumentException ex) {
                throw invalid("species 含 CAT / DOG / OTHER 以外的值");
            }
        }
        return out;
    }

    private static void putDate(ObjectNode o, String key, LocalDate d) {
        if (d != null) {
            o.put(key, d.toString());
        }
    }

    private static AppException invalid(String detail) {
        return AppException.validation("人群规格不合法：" + detail).code(INVALID_CODE);
    }
}
