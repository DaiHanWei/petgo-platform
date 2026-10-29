package com.tailtopia.ops.audience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.profile.domain.PetType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 运营推送的人群条件（V1.4.1，2026-10-29 拍板）：开屏弹窗与运营通知<b>共用同一套口径</b>。
 *
 * <ul>
 *   <li>{@code levelTiers}：用户等级档 —— 成长等级段位 NEWBIE（1–4）/ BUDDY（5–9）/ GUARDIAN（10–14）/ STAR（15–20），
 *       档位边界以库里 {@code growth_levels.tier} 为准；没有成长账户的用户按 NEWBIE。</li>
 *   <li>{@code species}：宠物类型 CAT / DOG / OTHER —— 有一只该类型的宠物就算。</li>
 *   <li>{@code activity}：活跃时间档，见 {@link ActivityBand}。</li>
 *   <li>{@code paid}：付费状态 —— true = 有过一笔成功的真钱支付（QRIS / 混合支付，含充值、问诊、商城、身份证高清等），
 *       只用 PawCoin 的不算；false = 一笔都没有。</li>
 * </ul>
 *
 * <p>语义：字段为 {@code null}（或空集）= 该维度不筛；同一维度多选为「或」；维度之间为「且」。
 * SQL 落在 {@code OpsAudienceSql#appendTargeting}（两处共用，口径只写一份）。
 *
 * <p>JSON 形状（开屏弹窗 {@code ops_popups.audience_filter} 列）：
 * {@code {"levelTiers":["NEWBIE"],"species":["CAT"],"activity":["WITHIN_7D"],"paid":true}}，未出现的键 = 不筛。
 */
public record AudienceFilters(
        Set<GrowthTier> levelTiers,
        Set<PetType> species,
        Set<ActivityBand> activity,
        Boolean paid) {

    public static final String KEY_LEVEL_TIERS = "levelTiers";
    public static final String KEY_SPECIES = "species";
    public static final String KEY_ACTIVITY = "activity";
    public static final String KEY_PAID = "paid";

    private static final Set<String> KEYS = Set.of(KEY_LEVEL_TIERS, KEY_SPECIES, KEY_ACTIVITY, KEY_PAID);

    /** 严格解析：尾随垃圾与重复键一律判非法（同 AudienceSpec）。 */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** 空集统一归一成 null，保证「不筛」只有一种表示。 */
    public AudienceFilters {
        levelTiers = normalize(levelTiers);
        species = normalize(species);
        activity = normalize(activity);
    }

    public static AudienceFilters none() {
        return new AudienceFilters(null, null, null, null);
    }

    /** 四个维度一个都没选。 */
    public boolean isEmpty() {
        return levelTiers == null && species == null && activity == null && paid == null;
    }

    /** 解析 {@code ops_popups.audience_filter}；null / 空串 = 不筛。非法 → {@link IllegalArgumentException}。 */
    public static AudienceFilters parse(String json) {
        if (json == null || json.isBlank()) {
            return none();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("人群条件不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("人群条件必须是 JSON 对象");
        }
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!KEYS.contains(e.getKey())) {
                throw new IllegalArgumentException("未知键 " + e.getKey());
            }
        }
        return new AudienceFilters(
                enums(root.get(KEY_LEVEL_TIERS), GrowthTier.class, KEY_LEVEL_TIERS),
                enums(root.get(KEY_SPECIES), PetType.class, KEY_SPECIES),
                enums(root.get(KEY_ACTIVITY), ActivityBand.class, KEY_ACTIVITY),
                bool(root.get(KEY_PAID), KEY_PAID));
    }

    /** 序列化：省略不筛的键，集合按枚举序；全不筛 → null（列存 NULL）。 */
    public String toJsonOrNull() {
        if (isEmpty()) {
            return null;
        }
        ObjectNode o = JSON.createObjectNode();
        writeInto(o);
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AudienceFilters 序列化失败", e);
        }
    }

    /** 把非空维度写进已有 JSON 对象（AudienceSpec 复用，键名一致）。 */
    public void writeInto(ObjectNode o) {
        putEnums(o, KEY_LEVEL_TIERS, levelTiers);
        putEnums(o, KEY_SPECIES, species);
        putEnums(o, KEY_ACTIVITY, activity);
        if (paid != null) {
            o.put(KEY_PAID, paid);
        }
    }

    // ---------- 解析辅助（AudienceSpec 复用） ----------

    /** 枚举数组：null / JSON null → null；非数组、含非字符串或未知值 → {@link IllegalArgumentException}。 */
    public static <E extends Enum<E>> Set<E> enums(JsonNode n, Class<E> type, String key) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw new IllegalArgumentException(key + " 不是数组");
        }
        EnumSet<E> out = EnumSet.noneOf(type);
        for (JsonNode e : n) {
            if (!e.isTextual()) {
                throw new IllegalArgumentException(key + " 含非字符串");
            }
            try {
                out.add(Enum.valueOf(type, e.asText()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(key + " 含未知值 " + e.asText());
            }
        }
        return out;
    }

    /** 布尔：null / JSON null → null；非布尔 → {@link IllegalArgumentException}。 */
    public static Boolean bool(JsonNode n, String key) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isBoolean()) {
            throw new IllegalArgumentException(key + " 不是布尔值");
        }
        return n.booleanValue();
    }

    private static <E extends Enum<E>> void putEnums(ObjectNode o, String key, Set<E> values) {
        if (values != null) {
            ArrayNode arr = o.putArray(key);
            values.stream().sorted().map((Function<E, String>) Enum::name).forEach(arr::add);
        }
    }

    private static <E extends Enum<E>> Set<E> normalize(Set<E> s) {
        return s == null || s.isEmpty() ? null : Collections.unmodifiableSet(EnumSet.copyOf(s));
    }
}
