package com.tailtopia.content.dto;

/**
 * 发帖成功响应里的一个话题（V1.4.2 social-topic · Story 4.1 · AC6.3 · R-2）。
 * JSON：{@code {"name":"kucinglucu","official":false,"created":true}}。
 *
 * <p>App 发布成功后按它上报 {@code topic_inserted}（R-2）；{@code name} 是<b>标准名</b>，不出 {@code topics.id}。
 *
 * @param name     标准名
 * @param official 是否官方话题
 * @param created  本次发帖新建了该话题（幂等重放恒 false）
 */
public record TopicRef(String name, boolean official, boolean created) {
}
