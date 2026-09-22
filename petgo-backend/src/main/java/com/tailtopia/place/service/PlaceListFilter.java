package com.tailtopia.place.service;

import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.shared.error.AppException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 场所列表筛选（V1.3.0 batch-b1 Story 1.11 · B1-D14）。
 *
 * <p>语义（AC2）：<b>类型之间「或」、标签之间「且」、类型与标签之间「且」</b>。空集合 = 该维度不筛。
 *
 * <p>🔴 筛选必须进 SQL 的 WHERE（AC3），在 {@code MAX_LIST_SIZE} 截断之前生效 —— 见
 * {@code PlaceRepository} 的两条带筛选原生查询。本类只负责「把 query 参数解析成已校验的枚举集合」
 * 与「把枚举集合转成 SQL 绑定值」，<b>拼进 SQL 的永远只有枚举名，绝不含用户原始输入</b>。
 */
public record PlaceListFilter(Set<PlaceType> types, Set<PlaceTag> tags) {

    /** 不筛（与 Story 1.11 之前的行为完全一致）。 */
    public static final PlaceListFilter NONE = new PlaceListFilter(Set.of(), Set.of());

    public PlaceListFilter {
        types = types == null || types.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(types));
        tags = tags == null || tags.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(tags));
    }

    public boolean isEmpty() {
        return types.isEmpty() && tags.isEmpty();
    }

    /**
     * 解析 {@code type} / {@code tag} 可重复 query 参数（AC1 / AC4）。
     *
     * <p>🔴 <b>不认识的值 → 422，不静默忽略</b>（同坐标：静默忽略会让用户以为筛过了）。
     * 取值必须与枚举名<b>完全一致</b>（UPPER_SNAKE，大小写敏感）。
     * 单个参数出现次数上限 = 该枚举成员数（类型 7 / 标签 6），超出 422 —— 挡的是一个匿名请求带几千个参数。
     * 同一值重复出现（在上限内）视为一个。
     */
    public static PlaceListFilter parse(List<String> rawTypes, List<String> rawTags) {
        Set<PlaceType> types = parseEnums(rawTypes, PlaceType.class, "type");
        Set<PlaceTag> tags = parseEnums(rawTags, PlaceTag.class, "tag");
        if (types.isEmpty() && tags.isEmpty()) {
            return NONE;
        }
        return new PlaceListFilter(types, tags);
    }

    private static <E extends Enum<E>> Set<E> parseEnums(List<String> raw, Class<E> cls, String param) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        int max = cls.getEnumConstants().length;
        if (raw.size() > max) {
            throw AppException.validation("参数 " + param + " 最多出现 " + max + " 次");
        }
        EnumSet<E> out = EnumSet.noneOf(cls);
        for (String v : raw) {
            E e = lookup(cls, v);
            if (e == null) {
                // 不回显原值：它来自匿名请求，回显等于给反射型内容开口子。
                throw AppException.validation("参数 " + param + " 取值不合法");
            }
            out.add(e);
        }
        return out;
    }

    private static <E extends Enum<E>> E lookup(Class<E> cls, String v) {
        if (v == null) {
            return null;
        }
        for (E e : cls.getEnumConstants()) {
            if (e.name().equals(v)) {
                return e;
            }
        }
        return null;
    }

    /** 类型绑定值：逗号拼接的枚举名；空 = 不筛（SQL 侧 {@code :types = ''} 短路）。 */
    public String typesParam() {
        return types.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /**
     * 标签绑定值：JSON 数组，只由已校验的枚举名拼成（{@code ["OUTDOOR_SEATING","PET_MENU"]}）。
     * 空 = {@code []}，而 {@code tags @> '[]'} 恒真 —— 于是「不筛标签」不需要额外分支。
     */
    public String tagsJsonParam() {
        return tags.stream().map(t -> "\"" + t.name() + "\"").sorted()
                .collect(Collectors.joining(",", "[", "]"));
    }
}
