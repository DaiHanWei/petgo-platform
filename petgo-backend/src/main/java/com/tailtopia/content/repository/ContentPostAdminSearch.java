package com.tailtopia.content.repository;

import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.List;

/**
 * 后台全量内容查询自定义片段（Story 4.2）。Criteria 动态拼条件——仅为非 null 筛选项加谓词，
 * 规避 Postgres「无类型 null 参数无法推断类型」（42P18）。createdAt 倒序。
 */
public interface ContentPostAdminSearch {

    /**
     * @param type     内容类型（null=全部）
     * @param authorId 作者（null=全部）
     * @param from     创建时间下界（含，null 忽略）
     * @param to       创建时间上界（不含，null 忽略）
     * @param deleted  true=仅已下架 / false=仅上线中 / null=全部
     * @param keyword  正文关键词（ILIKE 子串，大小写不敏感；null/空忽略）
     */
    List<AdminContentRow> adminSearch(ContentType type, Long authorId, Instant from, Instant to,
            Boolean deleted, String keyword, int limit, int offset);

    /** 按 id 取单条后台行投影（HTMX 局部刷新用）；不存在返回 {@code null}。 */
    AdminContentRow adminRowById(long postId);
}
