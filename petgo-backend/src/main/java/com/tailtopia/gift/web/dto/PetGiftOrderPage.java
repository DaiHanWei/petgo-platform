package com.tailtopia.gift.web.dto;

import java.util.List;

/**
 * 逐笔礼物列表一页（V1.5.0 gift-pack Story 1-4 · AC1）。
 *
 * @param unopenedCount 主人视角的未拆笔数；访客为 null
 * @param categories    仅首页：全部在线分类（筛选胶囊）；翻页为 null
 * @param pet           仅首页：宠物头（<b>复用</b> {@link PetGiftShowcaseResponse.PetView}）；翻页为 null
 * @param viewerIsOwner 仅首页；翻页为 null
 */
public record PetGiftOrderPage(List<GiftOrderCard> items, String nextCursor, Integer unopenedCount,
        List<CategoryChip> categories, PetGiftShowcaseResponse.PetView pet, Boolean viewerIsOwner) {

    /** 筛选胶囊（分类 id 是参考数据例外，D-4）。 */
    public record CategoryChip(long categoryId, String nameEn, String nameId) {
    }
}
