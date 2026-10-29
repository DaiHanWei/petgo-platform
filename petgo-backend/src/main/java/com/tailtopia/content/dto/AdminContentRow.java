package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * 后台全量内容浏览行投影（Story 4.2）。content 模块对外只读 DTO（admin 不直拿 ContentPost 实体）。
 * {@code deleted} = {@code deleted_at IS NOT NULL}（含作者自删/注销级联/运营下架，无 delete_reason 列区分）。
 * {@code imageUrls} 为公开桶 CDN 全 URL 列表（可空），供后台直接预览内容图（bug 20260630-157）。
 */
public record AdminContentRow(long id, ContentType type, Long authorId, String textPreview,
        boolean deleted, Instant createdAt, List<String> imageUrls,
        /**
         * 行级物种覆写的**原始值**（V1.1.6 Story 14.1）。可空。
         *
         * <p>⚠️ 这里刻意只带原始覆写值，<b>不带推导结果</b> ——
         * 推导要 join 作者与宠物档案，逐行做就是 N+1；由 admin 层用
         * {@code ContentSpeciesResolver#resolveAll} 对**整页**一次算完。
         */
        String speciesOverride,
        /**
         * 该帖未删评论数（含一级 + 二级，不按 viewer 过滤 —— 后台全量口径）。
         *
         * <p>⚠️ **点赞数不在这里**：它由 {@code AdminContentManageService#likeCounts} 整页批量取，
         * 走一张独立的 Map。看着不对称，但不是随手写的 ——
         * 「按点赞时间」那档口径要的是**时间窗内**的赞数，与这一行"至今累计"是两个数；
         * 塞进行投影就得为同一个字段准备两种含义，那是更糟的一种对称。
         */
        long commentCount,
        /** 媒体类型（专题线 video Story 3.3）；恒有值，旧构造器给 IMAGE。 */
        MediaType mediaType,
        /**
         * 视频播放地址（专题线 video Story 3.3）：只视频帖、且经 {@code PostMediaResolver#resolveForAdmin}
         * 投影后才有值（签名 URL，勿入日志）；此时 {@code imageUrls} = [签名封面]。
         */
        String videoUrl) {

    /** 兼容旧调用（图文帖口径）。 */
    public AdminContentRow(long id, ContentType type, Long authorId, String textPreview,
            boolean deleted, Instant createdAt, List<String> imageUrls, String speciesOverride,
            long commentCount) {
        this(id, type, authorId, textPreview, deleted, createdAt, imageUrls, speciesOverride, commentCount,
                MediaType.IMAGE, null);
    }

    /** 是否视频帖（模板用）。 */
    public boolean isVideo() {
        return mediaType == MediaType.VIDEO;
    }

    /** 换上后台媒体投影（视频帖：签名视频 + 签名封面）。 */
    public AdminContentRow withMedia(PostMedia media) {
        return new AdminContentRow(id, type, authorId, textPreview, deleted, createdAt, media.imageUrls(),
                speciesOverride, commentCount, media.mediaType(),
                media.video() == null ? null : media.video().url());
    }

    /** 兼容旧调用（评论数 = 0）。 */
    public AdminContentRow(long id, ContentType type, Long authorId, String textPreview,
            boolean deleted, Instant createdAt, List<String> imageUrls, String speciesOverride) {
        this(id, type, authorId, textPreview, deleted, createdAt, imageUrls, speciesOverride, 0L);
    }
}
