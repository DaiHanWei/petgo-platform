package com.tailtopia.topic.service;

/**
 * 一条帖子写入的一个话题关联（V1.4.2 social-topic · Story 4.1 · AC4）。
 *
 * @param topicId 话题 id —— <b>只在进程内使用</b>（事件 {@code topicIds}），绝不下发
 * @param name    标准名（{@code name_norm}，对外标识）
 * @param official 是否官方话题
 * @param created 本次发帖是否新建了这个话题（{@code INSERT} 影响行数为 1）
 */
public record AttachedTopic(long topicId, String name, boolean official, boolean created) {
}
