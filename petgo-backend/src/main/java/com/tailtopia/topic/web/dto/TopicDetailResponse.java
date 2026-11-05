package com.tailtopia.topic.web.dto;

/**
 * 话题详情（V1.4.2 social-topic · Story 4.2 · AC1 · D-13）。{@code GET /api/v1/topics/{name}}。
 *
 * <p>🔴 不出 {@code topics.id}，也<b>不出帖子数</b>（PRD「本版本不显示帖子数」）。全局 NON_NULL：
 * {@code description} 为 null（用户话题 / 官方两语皆空）时省略。
 *
 * @param name        标准名（对外唯一标识，埋点 {@code topic_id} 同值 · R-2）
 * @param displayName 显示写法（{@code name_display}，后台改大小写即刻生效）
 * @param official    是否官方话题（R-2）
 * @param description 官方说明（按 Accept-Language 选 id / en，空则回退另一语言）；用户话题恒省略
 */
public record TopicDetailResponse(String name, String displayName, boolean official, String description) {
}
