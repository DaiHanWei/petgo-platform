package com.tailtopia.content.rank;

import com.tailtopia.content.domain.ContentType;

/**
 * 内容属性（V1.1.6 Story 16.2 · AC1）—— 属性穿插的三个类目。
 *
 * <p>与 {@link ContentType} 的映射：
 * <pre>
 *   KNOWLEDGE                        → EDU
 *   DAILY                            → FUN
 *   GROWTH_MOMENT 且 visibility=PUBLIC → LIFE
 * </pre>
 *
 * <p>⚠️ <b>这是「属性」不是「内容类型」</b>，刻意另起一个枚举而不是复用 {@link ContentType}：
 * 属性是<b>排序用的类目</b>（决定穿插节奏），内容类型是<b>产品概念</b>（决定发布表单与落地页）。
 * 现在恰好一对一，但把它们当同一个东西，将来加一个新内容类型时会被迫同时改动排序节奏。
 */
public enum FeedAttribute {

    /** 逗趣 / 日常。窗口内占比最高的那一类。 */
    FUN,

    /** 科普 / 知识。 */
    EDU,

    /** 生活记录（成长日历快乐时刻）。 */
    LIFE;

    /**
     * 由内容类型与可见性推出属性。
     *
     * <p>⚠️ 非公开的 {@code GROWTH_MOMENT} 返回 {@code null}（不属于任何属性）——
     * 候选池本来就只含 {@code PUBLIC}（16.3 的过滤），所以正常路径不会出现；
     * 这里返回 null 而不是抛错，是为了「万一漏过滤」时表现为<b>这条内容排不进去</b>，
     * 而不是整个首页 500。🛡 调用方须把 null 的候选剔掉，不要当成第四种属性。
     */
    public static FeedAttribute from(ContentType type, boolean isPublic) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case KNOWLEDGE -> EDU;
            case DAILY -> FUN;
            case GROWTH_MOMENT -> isPublic ? LIFE : null;
        };
    }
}
