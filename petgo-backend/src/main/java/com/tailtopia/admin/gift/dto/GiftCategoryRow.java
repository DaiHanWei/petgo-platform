package com.tailtopia.admin.gift.dto;

import com.tailtopia.gift.domain.GiftCategory;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import java.time.Instant;

/**
 * 后台礼物分类的一行 / 抽屉头（V1.5.0 gift-pack Story 1-2 · K1 / K4）。后台按自增 id 寻址；
 * {@link #code()} 只是展示用的 {@code cat-<id>}。
 *
 * @param onlineGifts 该分类下在线礼物数（下线前置条件）
 * @param totalGifts  该分类下全部礼物数（含已下线）
 */
public record GiftCategoryRow(long id, String nameEn, String nameId, int sortOrder, String status,
        Instant updatedAt, long onlineGifts, long totalGifts) {

    public static GiftCategoryRow of(GiftCategory c, long onlineGifts, long totalGifts) {
        return new GiftCategoryRow(c.getId(), c.getNameEn(), c.getNameId(), c.getSortOrder(), c.getStatus().name(),
                c.getUpdatedAt(), onlineGifts, totalGifts);
    }

    public String code() {
        return "cat-" + id;
    }

    public boolean offline() {
        return GiftCategoryStatus.OFFLINE.name().equals(status);
    }

    /** 在线但没有在线礼物：App 不显示（K1 灰徽标）。 */
    public boolean hiddenInApp() {
        return !offline() && onlineGifts == 0;
    }

    /** 能否下线：分类下没有在线礼物（K4；服务端另行校验）。 */
    public boolean canOffline() {
        return onlineGifts == 0;
    }
}
