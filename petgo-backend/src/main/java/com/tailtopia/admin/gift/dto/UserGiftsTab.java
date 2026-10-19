package com.tailtopia.admin.gift.dto;

import java.util.List;

/**
 * 用户抽屉「收送礼记录」页签（V1.4.0 ③ gift Story 3.2 · AC5，UI M4 ~ M6）。
 *
 * @param dir              当前子列表 {@code sent} / {@code received}
 * @param sentCount        该用户作为送礼人的订单总数（全部状态）
 * @param sentTotal        累计送出：作为送礼人且 {@code DELIVERED} 的 Σ total
 * @param receivedCount    该用户作为主人收到的 {@code DELIVERED} 订单数
 * @param receivedQty      收到礼物个数（{@code DELIVERED} 的 Σ quantity）
 * @param receivedSenders  收到礼物的去重送礼人数
 * @param receivedPets     收到礼物涉及的宠物数（已删宠物按快照名计）
 * @param receivedPetName  只涉及 1 只宠物时它的快照名（多只时任取，模板不用）
 * @param rows             当前子列表当前页（每页 {@code 10}）
 * @param page             当前页（0 起）
 * @param totalPages       当前子列表总页数（无数据为 0）
 */
public record UserGiftsTab(long userId, String dir, long sentCount, long sentTotal, long receivedCount,
        long receivedQty, long receivedSenders, long receivedPets, String receivedPetName,
        List<GiftOrderAdminRow> rows, int page, int totalPages) {

    public static final String SENT = "sent";
    public static final String RECEIVED = "received";

    public boolean isSent() {
        return SENT.equals(dir);
    }

    public boolean hasPrev() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages;
    }

    /** 「收到」摘要：名下只涉及 1 只宠物时写宠物名，多只时写只数（为 ② 多宠物预留，AC5.4）。 */
    public boolean receivedOnePet() {
        return receivedPets <= 1;
    }
}
