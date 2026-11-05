package com.tailtopia.admin.topic.service;

import com.tailtopia.topic.domain.TopicNameRules;
import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicType;

/**
 * 话题列表筛选（V1.4.2 Story 6.1 · AC2.2）。非法取值一律当「全部」（筛选栏下拉之外的手写 URL 不报错）。
 *
 * @param q 搜索词的<b>标准形</b>（去前导 {@code #}、NFC + 小写，空 = 不搜）；匹配 {@code name_norm} 包含
 */
public record TopicFilter(TopicType type, TopicStatus status, String q) {

    public static final TopicFilter ALL = new TopicFilter(null, null, null);

    public static TopicFilter of(String type, String status, String q) {
        return new TopicFilter(parse(TopicType.class, type), parse(TopicStatus.class, status), normalizeQuery(q));
    }

    /** 忽略用户输入的前导 {@code #}（可多个）与首尾空白；标准化与 {@code name_norm} 同一实现。 */
    static String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String s = q.strip();
        while (s.startsWith("#")) {
            s = s.substring(1).strip();
        }
        return s.isEmpty() ? null : TopicNameRules.normalize(s);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 回显给筛选栏 / 分页链接的原值（枚举名或空串）。 */
    public String typeParam() {
        return type == null ? "" : type.name();
    }

    public String statusParam() {
        return status == null ? "" : status.name();
    }
}
