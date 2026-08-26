package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.ImageSize;
import java.time.Instant;
import java.util.List;

/**
 * Feed 卡片投影（Story 3.2，AC2）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>{@code likeCount} 卡片点赞数（PRD-642：点赞数显示在内容卡片上；commentCount 仍不在卡片）。
 * {@code body} 给全文，前端截前 2 行。{@code firstImageUrl} 可空（无图 → 纯文字卡）。作者注销时
 * {@code authorDeleted=true}、昵称/头像为 null（前端本地化「已注销用户」+ 默认头像，头像不可点 —
 * NFR-8 / Story 3.8）。
 *
 * @param id            内容 id
 * @param authorId      作者 id（注销后仍返回）
 * @param authorNickname 作者昵称（注销时 null）
 * @param authorAvatarUrl 作者头像（注销时 null）
 * @param authorDeleted 作者是否已注销
 * @param type          内容类型
 * @param body          正文全文（可空）
 * @param firstImageUrl 首图（可空，无图为纯文字卡）
 * @param likeCount     卡片点赞数（PRD-642）
 * @param createdAt     发布时刻（ISO UTC）
 */
public record FeedItemResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        // 运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销作者恒为空表。
        java.util.List<com.tailtopia.auth.dto.UserTagView> authorTags,
        // 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。空表不下发（NON_NULL 省略）。
        java.util.List<ContentTagView> decorationTags,
        ContentType type,
        String body,
        String firstImageUrl,
        long likeCount,
        Instant createdAt,
        ContentVisibility visibility,
        /**
         * 整组图片（V1.1.6 Story 3.1 · AD-7 Rule 1）—— 多图轮播必需。
         *
         * <p>⚠️ {@code firstImageUrl} <b>保留不动</b>：老客户端还在读它，且时间线 / 名片 /
         * 后台等处的取数口径也没变。新客户端读这一列。
         */
        List<String> imageUrls,
        /**
         * 图片原始宽高（V1.1.6 Story 3.1 · AD-5），与 {@link #imageUrls()} <b>同序等长</b>；
         * 测不出来的位置为 {@code null}，存量内容整列为 {@code null}。
         *
         * <p>🛡 <b>只有原始宽高</b>。展示比例的收敛与高度护栏<b>一律客户端算</b> ——
         * 服务端先算一遍、客户端再算一遍就是双重裁切（AD-6 Rule 6）。
         * ⚠️ 想在这里加「比例」或「高度」字段之前，先读一遍这段。
         */
        List<ImageSize> imageSizes,
        /** 当前访客是否已赞（V1.1.6 Story 3.1）。未登录访客<b>恒为 false</b>。 */
        boolean liked,
        /**
         * 评论数（V1.1.6 Story 3.1）。
         *
         * <p>🔴 口径与内容详情页<b>完全一致</b>（含访客自己那条尚未对外可见的评论）——
         * 两处显示的是同一个东西，数字不一致用户只会以为出 bug 了。
         */
        long commentCount) {

    /**
     * ⚠️ {@code visibility} 恒下发（Story 4.1 · AC7）。Feed 里只会出现 PUBLIC，
     * 但「我的发布」复用同一 DTO —— 前端据此给私密内容打「仅自己可见」标识（Story 4.2）。
     */
    /**
     * ⚠️ <b>首页与「我的发布」共用这一个工厂</b>（AD-7 Rule 4：复用同一投影的出口口径不得分叉）。
     * 要加字段就加在这里，<b>不要为某一个出口另写一个</b> —— 那正是口径分叉的起点。
     */
    public static FeedItemResponse of(ContentPost p, AuthorView author, long likeCount,
            boolean liked, long commentCount, List<ContentTagView> decorationTags) {
        List<String> images = p.getImageUrls();
        String firstImage = (images != null && !images.isEmpty()) ? images.get(0) : null;
        return new FeedItemResponse(
                p.getId(),
                p.getAuthorId(),
                author.nickname(),
                author.avatarUrl(),
                author.deleted(),
                // 空标签不下发（NON_NULL 省略）：Feed 一页 20 行，每行一个空数组白占体积。
                author.tags().isEmpty() ? null : author.tags(),
                (decorationTags == null || decorationTags.isEmpty()) ? null : decorationTags,
                p.getType(),
                p.getText(),
                firstImage,
                likeCount,
                p.getCreatedAt(),
                p.getVisibility(),
                images,
                p.getImageSizes(),
                liked,
                commentCount);
    }
}
