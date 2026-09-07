package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.ContentDetailResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容详情读取（Story 3.3；点赞计数 Story 3.4 接入）。多态完整：不存在 / 软删 / 下架 → 统一 404
 * 文案（防枚举）；作者注销但内容留存 → 200 匿名化（非 404，NFR-8）。
 *
 * <p>{@code likeCount}/{@code liked} 取自 content_likes 实计（Story 3.4）；{@code commentCount} 取自 comments 表。
 */
@Service
public class ContentDetailService {

    /** 统一 404 文案：不暴露资源是否曾存在（防枚举）。 */
    static final String GONE_DETAIL = "这条内容已不存在";

    /** V1.1.6 Story 5.2：内容装饰标签。 */
    private final ContentTagQueryService contentTags;

    private final ContentPostRepository posts;
    private final CommentRepository comments;
    private final ContentLikeRepository likes;
    private final AccountQueryService accountQueryService;
    private final ReportService reportService;
    private final UserHideRelationReader hideRelations;

    public ContentDetailService(ContentPostRepository posts, CommentRepository comments,
            ContentLikeRepository likes, AccountQueryService accountQueryService,
            ReportService reportService, UserHideRelationReader hideRelations,
            ContentTagQueryService contentTags) {
        this.contentTags = contentTags;
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.accountQueryService = accountQueryService;
        this.reportService = reportService;
        this.hideRelations = hideRelations;
    }

    /**
     * 取内容详情。
     *
     * @param postId   内容 id
     * @param viewerId 当前用户 id（游客为 null，用于 isAuthor / liked）
     */
    @Transactional(readOnly = true)
    public ContentDetailResponse getDetail(long postId, Long viewerId) {
        // 可见性（内容审核 story 2 · D-CM2）：PUBLISHED 对所有人可见；UNDER_REVIEW 挂起帖仅作者本人可见
        // （审核中作者无感知，可正常点入详情），对他人 404 防枚举；拒绝即软删（deletedAt），对所有人隐藏。
        ContentPost post = posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED
                        || (p.getStatus() == PostStatus.UNDER_REVIEW
                                && viewerId != null && viewerId.equals(p.getAuthorId())))
                .orElseThrow(() -> AppException.notFound(GONE_DETAIL));

        // 内容审核 cm-6 §5.4：举报者对该帖视同不可见——返回统一 404（与 ReportService.isVisible 语义一致，防枚举）。
        if (viewerId != null && reportService.hasReported(postId, viewerId)) {
            throw AppException.notFound(GONE_DETAIL);
        }

        // Story 1.1（V1.1.4）：被隐藏作者的内容，详情页同样不可达——否则「举报一条帖 → 那条帖 404」
        // 而「拉黑整个人 → 他每一条帖详情页照样能开」，语义反而倒挂（PRD §7 方案甲要消灭的塌陷）。
        // Feed 那层是 JPQL 子查询，详情这条路径独立，改了 Feed 它不会跟着生效，必须单独拦。
        // ⚠️ 不区分 source（主动拉黑与举报隐藏都拦）——与主页访问校验只认 BLOCK 正好相反，别写混。
        // ⚠️ 沿用上方「取回后 exists 检查再抛统一 404」的既有形状，不改成 SQL 子查询：
        //    详情/列表两条路径的这处不对称是刻意的防枚举设计，不要顺手「统一」掉。
        if (viewerId != null && hideRelations.isHidden(viewerId, post.getAuthorId())) {
            throw AppException.notFound(GONE_DETAIL);
        }

        AuthorView author = accountQueryService.findAuthorViews(List.of(post.getAuthorId()))
                .get(post.getAuthorId());
        // viewer 维度可见性计数（story 3 §5.5）：公开可见 + viewer 自己的非可见评论，与渲染列表一致。
        // Story 1.3：同一口径再套 R1/R2——被 R2 影子的评论不计入对外评论数（否则「显示 5 条却只数得出 4 条」）。
        long commentCount = comments.countVisibleForViewer(postId, viewerId != null, viewerId,
                post.getAuthorId());
        // 用 equals 而非 ==：两者均为装箱 Long，== 是引用比较，id>127（越过 Long 缓存）会误判 false。
        boolean isAuthor = viewerId != null && viewerId.equals(post.getAuthorId());
        // Story 3.4：真实点赞计数 + 当前用户是否已赞（游客 false）。
        long likeCount = likes.countByPostId(postId);
        boolean liked = viewerId != null && likes.existsByPostIdAndUserId(postId, viewerId);
        // V1.1.6 Story 5.2：装饰标签（三处展示位之一）。单条也走批量方法 ——
        // 仓储刻意不提供逐条取法，免得别处照着写成逐条查。
        var decorations = contentTags.findVisibleTags(java.util.List.of(postId), java.time.Instant.now())
                .get(postId);
        return ContentDetailResponse.of(post, author, likeCount, commentCount, liked, isAuthor,
                decorations);
    }
}
