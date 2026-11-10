package com.tailtopia.topic.web.dto;

import com.tailtopia.topic.domain.Topic;

/**
 * 话题列表项（V1.4.2 social-topic · Story 4.2 · AC3 ~ AC5 · D-13 · R-2）：联想 / 首页话题行 / 推荐话题共用。
 *
 * <p>🔴 不出 {@code topics.id}。{@code featured} 用 {@link Boolean}，<b>只有首页话题行给值</b>
 * （App 埋点 {@code is_pinned_by_ops}），其余出口为 null → 全局 NON_NULL 省略。
 *
 * @param name        标准名
 * @param displayName 显示写法
 * @param official    是否官方话题
 * @param featured    是否运营指定（仅首页话题行）
 */
public record TopicSummaryResponse(String name, String displayName, boolean official, Boolean featured) {

    /** 联想 / 推荐：不带 {@code featured}。 */
    public static TopicSummaryResponse of(Topic t) {
        return new TopicSummaryResponse(t.getNameNorm(), t.getNameDisplay(), t.isOfficial(), null);
    }

    /** 首页话题行：带 {@code featured}。 */
    public static TopicSummaryResponse ofHomeRow(Topic t, boolean featured) {
        return new TopicSummaryResponse(t.getNameNorm(), t.getNameDisplay(), t.isOfficial(), featured);
    }
}
