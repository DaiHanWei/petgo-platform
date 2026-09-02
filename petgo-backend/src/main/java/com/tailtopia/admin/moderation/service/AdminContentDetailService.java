package com.tailtopia.admin.moderation.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.dto.ContentTagView;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.ContentTagQueryService;
import com.tailtopia.content.service.ContentViewStatsService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台内容详情只读聚合（2026-09-02）：与 App 详情页同一批元素（作者/类型/正文全文/大图/
 * 装饰标签/点赞数/评论区），外加后台才需要的状态（已下架/审核挂起）、可见性与浏览统计。
 *
 * <p><b>纯只读</b>：处置动作（下架/恢复/删评论）留在内容管理与评论管理页，本页不做。
 * 帖与统计经 owning service 读；评论经 {@code CommentRepository} 全量读
 * （不走 App 的 viewer 过滤查询 —— 运营要看的正是被过滤掉的那些，先例：Story 9.9 评论管理）。
 */
@Service
public class AdminContentDetailService {

    /** 一级评论每页条数（2026-09-02 产品定）。 */
    public static final int COMMENT_PAGE_SIZE = 20;
    /** 楼中楼默认收起，先显示前几条（与 App 首屏内嵌口径一致）。 */
    public static final int REPLY_PREVIEW = 3;

    private final ContentService contentService;
    private final CommentRepository comments;
    private final ContentLikeRepository likes;
    private final ContentViewStatsService viewStats;
    private final ContentTagQueryService contentTags;
    private final AccountQueryService accountQuery;

    public AdminContentDetailService(ContentService contentService, CommentRepository comments,
            ContentLikeRepository likes, ContentViewStatsService viewStats,
            ContentTagQueryService contentTags, AccountQueryService accountQuery) {
        this.contentService = contentService;
        this.comments = comments;
        this.likes = likes;
        this.viewStats = viewStats;
        this.contentTags = contentTags;
        this.accountQuery = accountQuery;
    }

    /**
     * 评论展示行。
     *
     * @param status  展示状态：{@code DELETED}（软删）优先，否则取审核态
     *                （VISIBLE / TAKEN_DOWN / UNDER_REVIEW / AUTHOR_DEACTIVATED）——
     *                后台全量列出并标注，不替运营隐藏（2026-09-02 产品定）
     * @param replies 二级回复（收起态只有前 {@value #REPLY_PREVIEW} 条；二级行本字段为空表）
     * @param replyTotal 二级回复总数（含已删；二级行恒为 0）
     */
    public record CommentView(long id, Long authorId, String authorName, boolean authorDeleted,
            String body, Instant createdAt, String status, List<CommentView> replies,
            long replyTotal) {
    }

    /** 详情聚合。评论分页信息用 {@code commentPage}/{@code commentTotalPages} 两个标量带出。 */
    public record Detail(ContentService.AdminPostDetail post, AuthorView author, long likeCount,
            long commentCount, ContentViewStatsService.ViewStat viewStat,
            List<ContentTagView> tags, List<CommentView> comments, int commentPage,
            int commentTotalPages, long commentTotalTopLevel) {
    }

    /**
     * @param commentPage     一级评论页码（0 起）
     * @param expandCommentId 展开全部回复的那条一级评论 id（可空；其余收起显示前 3 条）
     */
    @Transactional(readOnly = true)
    public Detail detail(long postId, int commentPage, Long expandCommentId) {
        ContentService.AdminPostDetail post = contentService.adminDetail(postId)
                .orElseThrow(() -> AppException.notFound("内容不存在")
                        .code("admin.err.content.notFound"));

        // 一级评论一页（含已删/全部状态，时间正序 —— 与 App 阅读顺序一致）。
        var page = comments.findByPostIdAndParentIdIsNull(postId,
                PageRequest.of(Math.max(commentPage, 0), COMMENT_PAGE_SIZE,
                        Sort.by("createdAt").ascending().and(Sort.by("id").ascending())));
        List<Comment> topLevel = page.getContent();

        // 本页父评论的全部回复一次取回（逐父查就是 N+1），按父分组。
        Map<Long, List<Comment>> repliesByParent = topLevel.isEmpty() ? Map.of()
                : comments.findByParentIdInOrderByCreatedAtAscIdAsc(
                        topLevel.stream().map(Comment::getId).toList()).stream()
                        .collect(java.util.stream.Collectors.groupingBy(Comment::getParentId));

        // 作者投影整批取：帖作者 + 本页全部评论/回复作者（注销者匿名化，模板兜底显示「已注销」）。
        java.util.Set<Long> authorIds = new java.util.HashSet<>();
        if (post.authorId() != null) {
            authorIds.add(post.authorId());
        }
        topLevel.forEach(c -> authorIds.add(c.getAuthorId()));
        repliesByParent.values().forEach(l -> l.forEach(c -> authorIds.add(c.getAuthorId())));
        Map<Long, AuthorView> authors = authorIds.isEmpty() ? Map.of()
                : accountQuery.findAuthorViews(authorIds);

        List<CommentView> rows = new ArrayList<>();
        for (Comment c : topLevel) {
            List<Comment> all = repliesByParent.getOrDefault(c.getId(), List.of());
            boolean expanded = expandCommentId != null && expandCommentId.equals(c.getId());
            List<CommentView> shown = all.stream()
                    .limit(expanded ? Long.MAX_VALUE : REPLY_PREVIEW)
                    .map(r -> toView(r, authors, List.of(), 0))
                    .toList();
            rows.add(toView(c, authors, shown, all.size()));
        }

        AuthorView author = post.authorId() == null ? null : authors.get(post.authorId());
        long likeCount = likes.countByPostId(postId);
        // 评论总数与列表页同口径：未删（一级 + 二级）。
        long commentCount = comments.countByPostIdAndDeletedAtIsNull(postId);
        var stats = viewStats.statsFor(List.of(postId)).get(postId);
        List<ContentTagView> tags = contentTags
                .findVisibleTags(List.of(postId), Instant.now())
                .getOrDefault(postId, List.of());
        return new Detail(post, author, likeCount, commentCount, stats, tags, rows,
                page.getNumber(), page.getTotalPages(), page.getTotalElements());
    }

    private static CommentView toView(Comment c, Map<Long, AuthorView> authors,
            List<CommentView> replies, long replyTotal) {
        AuthorView a = authors.get(c.getAuthorId());
        // 软删优先展示（评论可能"先下架后随帖软删"，运营要看到的是它最终不在了）。
        String status = c.isDeleted() ? "DELETED"
                : (c.getModerationStatus() == null ? CommentModerationStatus.VISIBLE.name()
                        : c.getModerationStatus().name());
        return new CommentView(c.getId(), c.getAuthorId(),
                a == null ? null : a.nickname(), a != null && a.deleted(),
                c.getBody(), c.getCreatedAt(), status, replies, replyTotal);
    }
}
