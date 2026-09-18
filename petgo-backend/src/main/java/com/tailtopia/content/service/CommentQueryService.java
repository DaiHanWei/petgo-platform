package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentPageResponse;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.repository.CommentLikeRepository;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** V1.3.0 Story 2.4：点赞数与已赞状态的**批量**取数（AC7，本类不做逐条查询）。 */
    private final CommentLikeRepository commentLikes;
    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final UserHideRelationReader hideRelations;

    public CommentQueryService(CommentRepository comments, CommentLikeRepository commentLikes,
            ContentPostRepository posts,
            AccountQueryService accountQueryService, UserHideRelationReader hideRelations) {
        this.comments = comments;
        this.commentLikes = commentLikes;
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.hideRelations = hideRelations;
    }

    /**
     * 一级评论分页，每条内嵌前 3 条二级回复 + replyCount。
     * viewer 维度可见性过滤（§5.5）：非 VISIBLE 评论仅作者本人可见，游客（viewerId=null）仅见 VISIBLE。
     *
     * <p><b>V1.3.0 Story 2.4 起默认序为热度序</b>（{@code 点赞数 DESC, createdAt ASC, id ASC}，AD-A8.1）。
     * 游标随之改用三元组的 {@link CommentHotCursor} —— <b>不是</b>共用的 {@link FeedCursor}
     * （改那个会溢出到 Feed，见 CommentHotCursor 的类注释）。
     *
     * <p>⚠️ <b>不做服务端排序快照</b>（AD-A8.3，决策 A-6 已接受代价）：排序键每次查询实时取。
     * 代价是某条评论的赞数恰在翻页瞬间跨越游标位置时，可能重复或漏一条 ——
     * <b>后果是多看/少看一条评论，不是数据错误</b>。
     * 「已渲染的列表不重排」由客户端承担，服务端不留状态。
     */
    @Transactional(readOnly = true)
    public CommentPageResponse topLevel(long postId, String cursor, Long viewerId) {
        // Story 1.3：这次要接住返回值——R2 的判据是**内容作者**，必须把 authorId 传进查询（AD-3）。
        // 原来它是 private void、把查出的实体直接扔了；改成返回实体是**零额外查询**。
        long postAuthorId = requireVisiblePost(postId).getAuthorId();
        CommentHotCursor decoded = decodeHot(cursor);

        List<Comment> rows = comments.findTopLevelByHot(postId,
                decoded != null,
                decoded == null ? 0L : decoded.likeCount(),
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

        // AC7：点赞数与「我赞没赞」**一次批量取完**（一级 + 内嵌的二级一起），绝不逐条查。
        LikeView likes = loadLikes(
                Stream.concat(
                        page.stream().map(Comment::getId),
                        repliesByParent.values().stream().flatMap(List::stream).map(Comment::getId))
                        .toList(),
                viewerId);

        List<CommentResponse> items = new ArrayList<>(page.size());
        for (Comment top : page) {
            List<Comment> replies = repliesByParent.getOrDefault(top.getId(), List.of());
            List<CommentResponse> first = replies.stream()
                    .limit(INLINE_REPLY_COUNT)
                    .map(r -> CommentResponse.reply(r, authors.get(r.getAuthorId()),
                            likes.countOf(r.getId()), likes.likedBy(r.getId())))
                    .toList();
            items.add(CommentResponse.topLevel(top, authors.get(top.getAuthorId()),
                    replies.size(), first,
                    likes.countOf(top.getId()), likes.likedBy(top.getId())));
        }

        // 🔴 下一页游标取**本页最后一条**的三元组。赞数必须来自同一次批量结果，
        //    不能再查一遍 —— 两次查询之间赞数可能变，那样游标会指到一个不存在的位置。
        return new CommentPageResponse(items, nextHotCursor(hasMore, page, likes), hasMore);
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
        // 二级回复同样可被点赞（一级二级共用 comment_likes），批量取。
        LikeView likes = loadLikes(page.stream().map(Comment::getId).toList(), viewerId);
        List<CommentResponse> items = page.stream()
                .map(c -> CommentResponse.reply(c, authors.get(c.getAuthorId()),
                        likes.countOf(c.getId()), likes.likedBy(c.getId())))
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

    /**
     * 二级回复仍用共享的 {@link FeedCursor}（时间正序，二元组够用）——
     * AD-A8.7：**二级不跟着改三元组**。
     */
    private static FeedCursor decode(String cursor) {
        return (cursor == null || cursor.isBlank()) ? null : FeedCursor.decode(cursor);
    }

    /** 一级评论热度序专用的三元组游标（AD-A8.2）。 */
    private static CommentHotCursor decodeHot(String cursor) {
        return (cursor == null || cursor.isBlank()) ? null : CommentHotCursor.decode(cursor);
    }

    /**
     * 一批评论的点赞数与「viewer 是否已赞」——**两次查询搞定一整页**（AC7）。
     *
     * <p>游客（{@code viewerId == null}）不查已赞集合：他不可能赞过任何东西，
     * 多发一次查询只为拿一个恒空的集合。
     */
    private LikeView loadLikes(List<Long> commentIds, Long viewerId) {
        if (commentIds.isEmpty()) {
            return LikeView.empty();
        }
        Map<Long, Long> counts = new java.util.HashMap<>();
        for (CommentLikeRepository.CommentLikeCount row : commentLikes.countByCommentIdIn(commentIds)) {
            counts.put(row.getCommentId(), row.getLikeCount());
        }
        Set<Long> liked = viewerId == null
                ? Set.of()
                : Set.copyOf(commentLikes.findLikedCommentIds(commentIds, viewerId));
        return new LikeView(counts, liked);
    }

    /** 一页评论的点赞视图。无人点赞的评论不在 map 里 → 计 0。 */
    private record LikeView(Map<Long, Long> counts, Set<Long> likedIds) {
        static LikeView empty() {
            return new LikeView(Map.of(), Set.of());
        }

        long countOf(long commentId) {
            return counts.getOrDefault(commentId, 0L);
        }

        boolean likedBy(long commentId) {
            return likedIds.contains(commentId);
        }
    }

    private static String nextHotCursor(boolean hasMore, List<Comment> page, LikeView likes) {
        if (!hasMore || page.isEmpty()) {
            return null;
        }
        Comment last = page.get(page.size() - 1);
        return new CommentHotCursor(likes.countOf(last.getId()), last.getCreatedAt(), last.getId())
                .encode();
    }

    private static String nextCursor(boolean hasMore, List<Comment> page) {
        if (!hasMore || page.isEmpty()) {
            return null;
        }
        Comment last = page.get(page.size() - 1);
        return new FeedCursor(last.getCreatedAt(), last.getId()).encode();
    }
}
