package com.tailtopia.notify.lifecycle;

import com.tailtopia.notify.domain.NotificationType;

/**
 * 一条待投递的生命周期推送（留存手册抓手 1）。由 {@link LifecyclePushPlanner} 纯逻辑产出。
 *
 * @param userId    收件人。
 * @param type      生命周期节点（LIFECYCLE_D1/D3/D7/WINBACK）。
 * @param nodeKey   去重键的第三段：D1/D3/D7 恒为 {@code ONCE}（一辈子一次）；
 *                  召回为 {@code yyyy-MM}（每月至多一次 —— 每周召回一次的人只会更快卸载）。
 * @param variant   落点（通知 {@code targetRef} = 变体名；{@code SKIPPED_ACTIVE} 只写 mark、从不下发）。
 * @param petName   默认宠物名；A / B 层（没宠物）为 {@code null}。
 * @param layer     分层（V1.5.0 Story 6.1 · OR-22），mark 的 variant 列存 {@code <层>_<变体>}。
 * @param copy      文案键后缀（{@code notify.<type>.<copy>.title/body}，如 {@code SQUAD}、{@code TASKS_LEFT}）。
 * @param breedWord 默认宠物品种（trim 非空），否则 {@code null}（dispatcher 按收件人语言回退物种词）。
 * @param petType   默认宠物物种（{@code CAT / DOG / OTHER}），品种为空时用来取物种词；无宠物为 {@code null}。
 * @param remaining 第 7 天还差的新手任务数 N（⑦ 计为完成）；其余节点 0。
 */
public record LifecyclePlannedPush(
        long userId,
        NotificationType type,
        String nodeKey,
        LifecycleVariant variant,
        String petName,
        LifecycleLayer layer,
        String copy,
        String breedWord,
        String petType,
        int remaining) {

    /** D1/D3/D7 的固定 nodeKey：这三个节点一个用户一辈子只经历一次。 */
    public static final String ONCE = "ONCE";

    /** 5 参兼容构造器（V1.5.0 前的形态）：{@code copy = variant 名}，无分层 / 品种。 */
    public LifecyclePlannedPush(long userId, NotificationType type, String nodeKey, LifecycleVariant variant,
            String petName) {
        this(userId, type, nodeKey, variant, petName, null, variant.name(), null, null, 0);
    }

    /** 文案键后缀，完整 i18n 键为 {@code notify.<copyKey>.title/body}。 */
    public String copyKey() {
        return type.name() + "." + copy;
    }

    /** {@code lifecycle_push_marks.variant}：{@code <层>_<变体>}（无分层时只写变体名），≤ 32 字符。 */
    public String markVariant() {
        return layer == null ? variant.name() : layer.name() + "_" + variant.name();
    }
}
