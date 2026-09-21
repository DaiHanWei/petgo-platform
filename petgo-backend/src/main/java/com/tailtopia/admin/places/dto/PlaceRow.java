package com.tailtopia.admin.places.dto;

import com.tailtopia.admin.places.domain.PlaceStatus;
import java.time.Instant;
import java.util.List;

/** B6 表格一行（Story 5.2 AC4 + D-39 城市列）。{@code markerDeleted} = 标记人已注销（置灰）；{@code typeName} = 类型显示名（未知码原样）。 */
public record PlaceRow(long id, String publicToken, String name, String placeType, String typeName, List<String> tags, String city,
        String addressText, String markerName, boolean markerDeleted, int photoCount, int commentCount, int checkinCount,
        int recommendCount, int notRecommendCount, PlaceStatus status, Long mergedIntoId, Instant createdAt) {

    /** 标签胶囊最多显示 3 个。 */
    public List<String> visibleTags() {
        return tags.size() <= 3 ? tags : tags.subList(0, 3);
    }

    public int hiddenTagCount() {
        return Math.max(0, tags.size() - 3);
    }
}
