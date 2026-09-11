package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import java.time.Instant;
import java.util.List;

/**
 * 内容详情投影（Story 3.3）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>{@code likeCount}/{@code liked} 在本 Story 为占位（0 / false），由 Story 3.4 接入真实点赞表；
 * {@code commentCount} 取自 comments 表实计。{@code isAuthor} 供前端「···」删除入口可见性（行为在 3.6）。
 * 作者注销匿名化（NFR-8）→ 仍 200，作者投影为「已注销」（前端本地化），非 404。
 *
 * @param liked    当前用户是否已赞（游客 false）；3.4 接入
 * @param isAuthor 当前用户是否作者（删除入口可见性）
 */
public record ContentDetailResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        // 运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销作者恒为空表。
        java.util.List<com.tailtopia.auth.dto.UserTagView> authorTags,
        // 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。空表不下发。
        java.util.List<ContentTagView> decorationTags,
        ContentType type,
        String body,
        List<String> imageUrls,
        /**
         * 图片原始宽高（V1.3.0 批次 A · Story 2.1 · AD-A12），与 {@link #imageUrls()}
         * <b>同序等长</b>；测不出来的位置为 {@code null}，存量内容（V1.1.6 之前发布的）整列各位为
         * {@code null}。无图时整个字段省略（NON_NULL）。
         *
         * <p><b>为什么详情页需要它</b>：没有尺寸，页面只能等图片下载完才知道该留多高 ——
         * 于是首屏加载时整页跳动。PRD 把 FR-113 归为「纯前端」是一处疏漏：详情响应此前
         * 只有 {@code imageUrls}（v1.1.6 AD-5 明写「既有五处图片读取点一处不动」，
         * 详情页正是其中之一）。
         *
         * <p>🛡 <b>只有原始宽高。</b>展示比例的收敛（clamp）与高度护栏<b>一律客户端算</b> ——
         * 护栏依赖可视区高度，服务端算不了；服务端先 clamp 一遍、客户端再 clamp 一遍
         * 就是<b>双重裁切</b>（v1.1.6 AD-6 Rule 6）。
         * ⚠️ 想在这里加「比例」或「已算好的高度」字段之前，先读一遍这段。
         *
         * <p>元素类型与 {@code FeedItemResponse.imageSizes()} <b>完全一致</b>，客户端两处共用一套解析。
         */
        List<com.tailtopia.content.domain.ImageSize> imageSizes,
        long likeCount,
        long commentCount,
        boolean liked,
        boolean isAuthor,
        /**
         * 可见性（V1.1.6 Story 10.1 补下发）。<b>恒下发</b>，与 {@code FeedItemResponse} 同口径。
         *
         * <p>补它的唯一理由是埋点 E-11 的 {@code is_private_diary} —— 那是个<b>加粗属性</b>，
         * 用来回答「用户到底会不会把私密日记分享出去」。分享私密内容本身是<b>允许</b>的
         * （AD-15 Rule 6：visibility 约束平台自动分发，不约束用户自己按分享键），
         * 所以这个数是产品判断，不是拦人的依据。
         */
        ContentVisibility visibility,
        Instant createdAt) {

    /**
     * @param imageSizes 已由 {@code ImageSizeResolver.alignForRead} 对齐到 {@code imageUrls} 长度的尺寸列；
     *                   无图时为 {@code null}。**不要在这里现场取 {@code p.getImageSizes()}** ——
     *                   那是未对齐的原始列，存量内容整列为 null，下标会对不上（Story 2.1 · AC1/AC2）。
     */
    public static ContentDetailResponse of(ContentPost p, AuthorView author, long likeCount,
            long commentCount, boolean liked, boolean isAuthor,
            List<ContentTagView> decorationTags,
            List<com.tailtopia.content.domain.ImageSize> imageSizes) {
        return new ContentDetailResponse(
                p.getId(), p.getAuthorId(), author.nickname(), author.avatarUrl(),
                author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                (decorationTags == null || decorationTags.isEmpty()) ? null : decorationTags,
                p.getType(), p.getText(), p.getImageUrls(), imageSizes,
                likeCount, commentCount, liked, isAuthor, p.getVisibility(),
                p.getCreatedAt());
    }
}
