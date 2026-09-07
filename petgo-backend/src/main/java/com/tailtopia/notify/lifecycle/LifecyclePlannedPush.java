package com.tailtopia.notify.lifecycle;

import com.tailtopia.notify.domain.NotificationType;

/**
 * 一条待投递的生命周期推送（留存手册抓手 1）。由 {@link LifecyclePushPlanner} 纯逻辑产出。
 *
 * @param userId   收件人。
 * @param type     生命周期节点（LIFECYCLE_D1/D3/D7/WINBACK）。
 * @param nodeKey  去重键的第三段：D1/D3/D7 恒为 {@code ONCE}（一辈子一次）；
 *                 召回为 {@code yyyy-MM}（每月至多一次 —— 每周召回一次的人只会更快卸载）。
 * @param variant  命中分层，决定文案与深链落点。
 * @param petName  宠物名，{@code null} 表示未建档（此时 variant 必为 {@code CREATE_PROFILE}）。
 */
public record LifecyclePlannedPush(
        long userId,
        NotificationType type,
        String nodeKey,
        LifecycleVariant variant,
        String petName) {

    /** D1/D3/D7 的固定 nodeKey：这三个节点一个用户一辈子只经历一次。 */
    public static final String ONCE = "ONCE";

    /**
     * 文案键后缀，完整 i18n 键为 {@code notify.<copyKey>.title/body}。
     * 按「节点 × 分层」取串 —— 这正是手册要的「理由必须具体」：
     * D1 对已建档的人说「今天记录 Mochi 的一个瞬间吧」，对没建档的人说「30 秒把档案建好」。
     */
    public String copyKey() {
        return type.name() + "." + variant.name();
    }
}
