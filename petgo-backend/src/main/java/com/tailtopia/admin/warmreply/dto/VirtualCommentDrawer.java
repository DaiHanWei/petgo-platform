package com.tailtopia.admin.warmreply.dto;

import java.time.Instant;
import java.util.List;

/**
 * 「去评论」抽屉数据（V1.3.0 Story 4.2 AC1 / AC2 / AC4 / AC5）。
 *
 * @param postId          帖子 id
 * @param postSummary     正文摘要（前 80 字）
 * @param thumbnailUrl    首图缩略（现签 URL；无图 null）
 * @param authorName      作者昵称
 * @param authorVirtual   作者是否虚拟账号
 * @param species         帖子有效物种（resolver：覆写 → 账号定位 → 宠物档案；无 → null）
 * @param visibleCount    当前可见评论数
 * @param virtualCount    虚拟账号评论数（可见 + 审核中）；≥3 → 黄条提示（D-20 不拦截）
 * @param comments        已有评论（一级 + 二级，时间正序；排软删）
 * @param matching        与帖子物种匹配的虚拟身份（帖子物种为 GENERAL / 空 → 全部）
 * @param others          其他物种的虚拟身份（收起，可展开）
 * @param recentIdentityIds 10 分钟内已评过本帖的虚拟身份 id（首次点击软提示）
 * @param nextPostId      按 4-1 当前筛选结果集顺序的下一帖（无 → null）
 * @param idempotencyKey  本次抽屉的幂等键（UUID，提交成功后刷新）
 */
public record VirtualCommentDrawer(long postId, String postSummary, String thumbnailUrl, String authorName, boolean authorVirtual,
        String species, long visibleCount, long virtualCount, List<ExistingComment> comments, List<IdentityOption> matching,
        List<IdentityOption> others, List<Long> recentIdentityIds, Long nextPostId, String idempotencyKey) {

    /** 身份下拉一项。 */
    public record IdentityOption(long id, String nickname, String accountSpecies, long todayCount) {
    }

    /** 已有评论一条（后台全量视角：含审核中；「虚拟」标识只在后台渲染）。 */
    public record ExistingComment(long id, Long parentId, String authorName, boolean authorVirtual, String body, Instant createdAt,
            String status, boolean underReview, boolean justSubmitted) {
    }

    public boolean manyVirtual() {
        return virtualCount >= 3;
    }
}
