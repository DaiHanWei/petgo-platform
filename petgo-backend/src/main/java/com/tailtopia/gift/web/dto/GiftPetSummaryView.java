package com.tailtopia.gift.web.dto;

import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.service.GiftShowcaseQuery.PetGiftSummary;
import com.tailtopia.gift.service.GiftShowcaseQuery.WallRow;
import com.tailtopia.shared.media.AliyunOssClient;
import java.util.List;

/**
 * Diary 详情礼物区的一只宠物（V1.4.0 gift Story 2.1 · AC5.3 · UI 稿 A3 / A5 / A6）。
 *
 * @param petId       宠物 id（App 点行进 {@code /pets/{petId}} 礼物页，与访客视图同一寻址口径）
 * @param avatarUrl   已去 EXIF 的分发地址；无头像为 null
 * @param totalValue  收到的礼物总价值（IDR）；0 礼物为 0
 * @param giftCount   收到的礼物个数；0 礼物为 0
 * @param kindCount   礼物墙总行数（App 算「+N」= {@code kindCount − wallPreview.length}）
 * @param wallPreview 礼物墙前 3 行（与礼物页同序）；0 礼物为空表
 */
public record GiftPetSummaryView(
        long petId,
        String name,
        String avatarUrl,
        long totalValue,
        long giftCount,
        int kindCount,
        List<GiftWallItemView> wallPreview) {

    public static GiftPetSummaryView of(GiftPet pet, PetGiftSummary summary, List<WallRow> preview) {
        PetGiftSummary s = summary == null ? PetGiftSummary.EMPTY : summary;
        List<GiftWallItemView> rows = preview == null ? List.of()
                : preview.stream().map(GiftWallItemView::of).toList();
        return new GiftPetSummaryView(pet.petId(), pet.name(),
                pet.avatarUrl() == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(pet.avatarUrl()),
                s.totalValue(), s.giftCount(), s.kindCount(), rows);
    }
}
