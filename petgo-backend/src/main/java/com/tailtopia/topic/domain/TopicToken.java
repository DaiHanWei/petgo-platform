package com.tailtopia.topic.domain;

/**
 * 正文中一处合法话题出现（V1.4.2 social-topic · Story 4.1 · P-1）。
 *
 * @param start     起点（UTF-16 下标，<b>含</b> {@code #}）
 * @param end       终点（UTF-16 下标，不含）
 * @param display   原文写法（不含 {@code #}，未做标准化）
 * @param norm      标准名（{@link TopicNameRules#normalize}）
 * @param effective 是否属于前 5 个不同话题（第 6 个起为普通文字）
 */
public record TopicToken(int start, int end, String display, String norm, boolean effective) {
}
