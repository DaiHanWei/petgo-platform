package com.tailtopia.admin.places.dto;

import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * B6 详情抽屉（Story 5.2 AC5，只读）五区：① 基本信息 ② 照片墙（签名 URL 现签、不落库、不进日志）③ 评论首页（20 条）④ 打卡计数
 * ⑤ 操作条占位（5.3 填充）。{@code mergedIntoName} 仅 MERGED 时非空。
 */
public record PlaceDrawerView(long id, String publicToken, String name, String placeType, String typeName, List<String> tags,
        String description, String city, String addressText, BigDecimal lat, BigDecimal lng, String markerName, boolean markerDeleted,
        PlaceStatus status, Long mergedIntoId, String mergedIntoName, Instant createdAt, Instant updatedAt,
        int photoCount, int commentCount, int checkinCount, int recommendCount, int notRecommendCount,
        List<PhotoView> photos, CommentsPage comments) {

    /** 照片：{@code url} 为短时效签名 URL（模板一次性使用，禁止记日志）；签名不可用时为 null → 占位图。 */
    public record PhotoView(long id, String url, String uploaderName, Instant createdAt) {
    }

    public record CommentView(long id, String body, String authorName, boolean authorDeleted, PlaceAttitude attitude, Instant createdAt) {
    }

    /** 评论分页（每页 20）。 */
    public record CommentsPage(List<CommentView> items, int page, boolean hasNext, long total) {
    }
}
