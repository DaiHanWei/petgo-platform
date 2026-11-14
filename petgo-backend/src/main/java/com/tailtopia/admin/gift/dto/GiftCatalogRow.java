package com.tailtopia.admin.gift.dto;

import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftKind;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台礼物目录的一行 / 抽屉头（V1.4.0 gift Story 3.1）。后台按自增 id 寻址（与用户标签同口径，不对 App 暴露）；
 * {@link #code()} 只是展示用的 {@code gift-<id>}，不落库、不对外。
 */
public record GiftCatalogRow(long id, String nameEn, String nameId, String iconUrl, long price,
        boolean forCat, boolean forDog, boolean forOther, String kind, Integer minLevel, int sortOrder,
        String status, Long categoryId, String categoryNameEn, String categoryNameId, boolean categoryOffline) {

    public static GiftCatalogRow of(GiftCatalogItem g) {
        return of(g, null);
    }

    /** V1.5.0 gift-pack Story 1-2：带所属分类（{@code category} 为空 = 专属 / 未归类 / 分类不存在）。 */
    public static GiftCatalogRow of(GiftCatalogItem g, com.tailtopia.gift.domain.GiftCategory category) {
        return new GiftCatalogRow(g.getId(), g.getNameEn(), g.getNameId(), g.getIconUrl(), g.getPrice(),
                g.isForCat(), g.isForDog(), g.isForOther(), g.getKind().name(), g.getMinLevel(), g.getSortOrder(),
                g.getStatus().name(), g.getCategoryId(), category == null ? null : category.getNameEn(),
                category == null ? null : category.getNameId(), category != null && !category.isOnline());
    }

    /** 普通礼物、已下线、且所在分类已下线（或缺失）→ 不能重新上线（L5）。 */
    public boolean onlineBlockedByCategory() {
        return offline() && !exclusive() && (categoryNameEn == null || categoryOffline);
    }

    public String code() {
        return "gift-" + id;
    }

    public boolean offline() {
        return GiftCatalogStatus.OFFLINE.name().equals(status);
    }

    public boolean exclusive() {
        return GiftKind.EXCLUSIVE.name().equals(kind);
    }

    /** 三个物种标记皆 false = 通用。 */
    public boolean universal() {
        return !forCat && !forDog && !forOther;
    }

    /** 适用物种（按「猫 · 狗 · 其他」顺序），模板按 {@code admin.v140.gift.species.<值>} 取文案。 */
    public List<String> species() {
        List<String> out = new ArrayList<>(3);
        if (forCat) {
            out.add("cat");
        }
        if (forDog) {
            out.add("dog");
        }
        if (forOther) {
            out.add("other");
        }
        return out;
    }
}
