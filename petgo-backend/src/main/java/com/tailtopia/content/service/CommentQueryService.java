package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentPageResponse;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论只读分页（Story 3.3）。一级时间正序首批 10、二级内嵌前 3 + replyCount、回复展开端点。
 * 写入在 Story 3.5。作者注销匿名化（NFR-8）。
 */
@Service
public class CommentQueryService {

    /** 一级评论每批条数（FR-28）。 */
    public static final int TOP_LEVEL_PAGE_SIZE = 10;
    /** 二级回复每批条数 + 首屏内嵌条数。 */
    public static final int REPLY_PAGE_SIZE = 10;
    public static final int INLINE_REPLY_COUNT = 3;

    private final CommentRepository comments;
    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final UserHideRelationReader hideRelations;

    public CommentQueryService(CommentRepository comments, ContentPostRepository posts,
            AccountQueryService accountQueryService, UserHideRelationReader hideRelations) {
        this.comments = comments;
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.hideRelations = hideRelations;
    }

    /**
     * 一级评论分页（时间正序），每条内嵌前 3 条二级回复 + replyCount。
     * viewer 维度可见性过滤（§5.5）：非 VISIBLE 评论仅作者本人可见，游客（viewerId=null）仅见 VISIBLE。
     */
    @Transactional(readOnly = true)
    public CommentPageResponse topLevel(long postId, String cursor, Long viewerId) {
        // Story 1.3：这次要接住返回值——R2 的判据是**内容作者**，必须把 authorId 传进查询（AD-3）。
        // 原来它是 private void、把查出的实体直接扔了；改成返回实体是**零额外查询**。
        long postAuthorId = requireVisiblePost(postId).getAuthorId();
        FeedCursor decoded = decode(cursor);

        List<Comment> rows = comments.findTopLevel(postId,
                decoded != null,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                viewerId != null,
                viewerId,
                postAuthorId,
                PageRequest.of(0, TOP_LEVEL_PAGE_SIZE + 1));

        boolean hasMore = rows.size() > TOP_LEVEL_PAGE_SIZE;
        List<Comment> page = hasMore ? rows.subList(0, TOP_LEVEL_PAGE_SIZE) : rows;

        // 这一页一级评论的 viewer 可见二级回复（用于取前 3 + 计数）。
        List<Long> parentIds = page.stream().map(Comment::getId).toList();
        Map<Long, List<Comment>> repliesByParent = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            for (Comment r : comments.findRepliesForParents(parentIds, viewerId != null, viewerId,
                    postAuthorId)) {
                repliesByParent.computeIfAbsent(r.getParentId(), k -> new ArrayList<>()).add(r);
            }
        }

        // 批量取所有涉及作者的投影（一级 + 二级），匿名化注销作者。
        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(
                Stream.concat(
                        page.stream().map(Comment::getAuthorId),
                        repliesByParent.values().stream().flatMap(List::stream).map(Comment::getAuthorId))
                        .toList());

        List<CommentResponse> items = new ArrayList<>(page.size());
        for (Comment top : page) {
            List<Comment> replies = repliesByParent.getOrDefault(top.getId(), List.of());
            List<CommentResponse> first = replies.stream()
                    .limit(INLINE_REPLY_COUNT)
                    .map(r -> CommentResponse.reply(r, authors.get(r.getAuthorId())))
                    .toList();
            items.add(CommentResponse.topLevel(top, authors.get(top.getAuthorId()),
                    replies.size(), first));
        }

        return new CommentPageResponse(items, nextCursor(hasMore, page), hasMore);
    }

    /**
     * 展开某一级评论的全部二级回复（时间正序游标分页），viewer 维度可见性过滤（§5.5）
     * + 隐藏关系 R1/R2（Story 1.3）。
     *
     * <p>⚠️ 这条分支<b>原先一次 post / parent 查询都没有</b>（拿到 parentId 直接查回复），
     * 架构 AD-3 里写的「经 {@code Comment.postId} 反查一次」是<b>本 story 要新增的动作</b>，不是既有的。
     * R2 要 {@code postAuthorId}，所以这里必须反查两跳：parent → postId → post.authorId。
     */
    @Transactional(readOnly = true)
    public CommentPageResponse replies(long parentId, String cursor, Long viewerId) {
        Comment parent = comments.findById(parentId).orElse(null);
        if (parent == null || parent.getDeletedAt() != null) {
            return new CommentPageResponse(List.of(), null, false);
        }
        // ⚠️ 这里刻意**不**补「帖子是否仍可见」的校验：该分支原本就不校验（帖被下架后凭 parentId 仍能拉到回复），
        // 那是一个既有缺口，改它属于既有行为变更、不在本 story 范围内（已在 story 里记为待 PO 确认）。
        long postAuthorId = posts.findById(parent.getPostId())
                .map(ContentPost::getAuthorId)
                .orElse(NO_POST_AUTHOR);

        // AC4：父被隐藏 → 整串不展示（不出现「回复了某条看不见的评论」的孤儿回复）。
        // 父只有一个，在这里判一次即可；写进 SQL 会对每一行重复判定同一个父。
        if (isHiddenForViewer(parent.getAuthorId(), viewerId, postAuthorId)) {
            return new CommentPageResponse(List.of(), null, false);
        }

        FeedCursor decoded = decode(cursor);
        List<Comment> rows = comments.findReplies(parentId,
                decoded != null,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                viewerId != null,
                viewerId,
                postAuthorId,
                PageRequest.of(0, REPLY_PAGE_SIZE + 1));

        boolean hasMore = rows.size() > REPLY_PAGE_SIZE;
        List<Comment> page = hasMore ? rows.subList(0, REPLY_PAGE_SIZE) : rows;

        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(
                page.stream().map(Comment::getAuthorId).toList());
        List<CommentResponse> items = page.stream()
                .map(c -> CommentResponse.reply(c, authors.get(c.getAuthorId())))
                .toList();
        return new CommentPageResponse(items, nextCursor(hasMore, page), hasMore);
    }

    /**
     * 一条评论（这里用于**父评论**）对当前查看者是否被隐藏 —— R1 与 R2 的 Java 侧同义实现。
     *
     * <p>⚠️ 两条的自视豁免<b>不对称</b>，与 JPQL 里那两段必须保持一致：
     * <ul>
     *   <li><b>R1</b>「我不看」：holder = 查看者，<b>无需豁免</b>（不会拉黑自己）。游客无 R1。</li>
     *   <li><b>R2</b>「让我的地盘干净」：holder = <b>内容作者</b>，<b>必须豁免评论作者本人</b> ——
     *       被影子的人要看得见自己那条，否则他立刻知道被屏蔽了（AC3 无感知）。</li>
     * </ul>
     */
    private boolean isHiddenForViewer(long commentAuthorId, Long viewerId, long postAuthorId) {
        if (viewerId != null && hideRelations.isHidden(viewerId, commentAuthorId)) {
            return true; // R1
        }
        if (viewerId != null && viewerId == commentAuthorId) {
            return false; // R2 的自视豁免：本人永远看得见自己
        }
        return postAuthorId != NO_POST_AUTHOR && hideRelations.isHidden(postAuthorId, commentAuthorId); // R2
    }

    /** 帖子已不存在时的 postAuthorId 占位：不匹配任何 users.id，R2 恒不命中。 */
    private static final long NO_POST_AUTHOR = -1L;

    private ContentPost requireVisiblePost(long postId) {
        return posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> AppException.notFound(ContentDetailService.GONE_DETAIL));
    }

    private static FeedCursor decode(String cursor) {
        return (cursor == null || cursor.isBlank()) ? null : FeedCursor.decode(cursor);
    }

    private static String nextCursor(boolean hasMore, List<Comment> page) {
        if (!hasMore || page.isEmpty()) {
            return null;
        }
        Comment last = page.get(page.size() - 1);
        return new FeedCursor(last.getCreatedAt(), last.getId()).encode();
    }
}
