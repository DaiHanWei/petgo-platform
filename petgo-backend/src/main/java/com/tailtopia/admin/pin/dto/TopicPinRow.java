package com.tailtopia.admin.pin.dto;

import com.tailtopia.admin.topic.service.TopicView;

/**
 * 话题页顶置 · 话题列表的一行（V1.4.2 social-topic · Story 6.2 · G1a）。
 *
 * @param active       当前生效的那一条（未结束且处于生效窗口；重叠校验保证至多一条），无 = {@code null}
 * @param pendingCount 排期中条数：未结束（含未被提前结束）且尚未开始
 */
public record TopicPinRow(TopicView topic, PinRow active, long pendingCount) {
}
