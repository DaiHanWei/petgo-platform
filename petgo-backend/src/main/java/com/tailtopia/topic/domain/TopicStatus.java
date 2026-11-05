package com.tailtopia.topic.domain;

/**
 * 话题状态（V1.4.2 social-topic · Story 4.1 · D-2）。落库 varchar UPPER_SNAKE（{@code ck_topics_status}）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：正常；</li>
 *   <li>{@link #HIDDEN}：不进推荐 / 联想 / 首页话题行（4.2 / 6.1），但新帖照写关联、帖子出参照下发 {@code topicNames}；</li>
 *   <li>{@link #DISABLED}：新帖不写关联、出参不再下发（正文按普通文字渲染），但仍占单帖 5 个名额（规则纯文本可判）。</li>
 * </ul>
 */
public enum TopicStatus {
    ACTIVE,
    HIDDEN,
    DISABLED
}
