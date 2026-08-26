package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentType;
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
        String speciesOverride) {
}
