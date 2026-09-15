package com.tailtopia.place.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.place.domain.PlacePhoto;
import com.tailtopia.shared.media.AliyunOssClient;

/**
 * 场所详情里的一张照片（V1.3.0 batch-b1 Story 1.9 · AC2「标注上传者」）。Jackson NON_NULL。
 *
 * <p>⚠️ 这取代了 Story 1.5 的 {@code photoUrls}（一个字符串数组）——
 * 数组装不下"这张是谁传的"。客户端的照片流据此在每张图上标注上传者。
 *
 * @param url              公开桶 CDN URL，**已附去 EXIF 的 `x-oss-process`**（E4 服务端兜底）
 * @param uploaderNickname 上传者昵称（注销时省略 → 客户端渲染「已注销用户」）
 * @param uploaderDeleted  上传者是否已注销
 * @param moderationStatus 审核态。非 VISIBLE 的行**只会下发给上传者本人**，
 *                         客户端据此渲染「审核中 / 仅你可见」
 * @param mine             是不是本人传的 —— 决定要不要给删除入口。
 *                         🔴 **服务端算给它**，不让客户端拿 id 自己比
 */
public record PlacePhotoView(
        Long id,
        String url,
        long uploaderId,
        String uploaderNickname,
        boolean uploaderDeleted,
        String moderationStatus,
        boolean mine) {

    public static PlacePhotoView of(PlacePhoto photo, AuthorView uploader, Long viewerId,
            int widthPx) {
        CommentModerationStatus s = photo.getModerationStatus();
        return new PlacePhotoView(
                photo.getId(),
                AliyunOssClient.exifStrippedThumbUrl(photo.getUrl(), widthPx),
                photo.getUploaderId(),
                uploader == null ? null : uploader.nickname(),
                uploader == null || uploader.deleted(),
                (s == null ? CommentModerationStatus.VISIBLE : s).name(),
                viewerId != null && viewerId.equals(photo.getUploaderId()));
    }
}
