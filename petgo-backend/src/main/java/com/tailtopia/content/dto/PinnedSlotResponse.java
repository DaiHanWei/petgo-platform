package com.tailtopia.content.dto;

/**
 * 顶置坑位的下发（V1.1.6 Story 4.2 · FR-68）。
 *
 * <p>🛡 **走独立取数**：与首页取数分开，首页的游标分页形态一点不变；
 * 本端点失败/为空时客户端当作没有顶置，首页照常显示。
 *
 * <p>{@code pin} 为空表示当前无生效配置 —— 客户端**什么都不渲染、不留占位**。
 *
 * @param pin 当前生效的顶置；无则 null
 */
public record PinnedSlotResponse(Pinned pin) {

    /**
     * @param pinConfigId 坑位配置标识（埋点要带）
     * @param pinType     顶置类型：{@code CONTENT}（已发布内容）/ {@code PROMO}（推广卡片）
     * @param item        顶置的内容条目 —— 与普通条目**完全同构的那一个 DTO**，
     *                    好让客户端用同一个卡片组件渲染。推广卡片（Story 4.3）时为 null
     */
    public record Pinned(long pinConfigId, String pinType, FeedItemResponse item) {
    }
}
