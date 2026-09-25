package com.tailtopia.admin.pin.dto;

/**
 * B3 顶置抽屉的坑位内容预览（V1.3.0 Story 7.3 · AC3）。
 *
 * <p>顶置内容型 = 帖子快照（首图 + 正文摘要 + 作者）；推广卡片型 = 卡面预览（图 + 标题 + 跳转链接）。
 * 时间设置与操作条用的仍是 {@link PinRow} 的字段（{@code phase} / {@code editable()} 都在那里），
 * 本记录只补「预览要用、列表行用不上」的那几项。
 *
 * @param postImage   顶置内容的首图（可空）
 * @param authorName  顶置内容的作者昵称（注销者为空，由 {@code authorDeleted} 区分）
 * @param promoImage  推广卡片图（可空）
 * @param promoLink   推广卡片跳转目标（可空 —— 建卡时本就允许不填）
 */
public record PinDetail(PinRow row, String postImage, String authorName, boolean authorDeleted,
        String promoImage, String promoTitle, String promoLink) {

    /** 推广卡片型。 */
    public boolean promo() {
        return "PROMO".equals(row.objectType());
    }
}
