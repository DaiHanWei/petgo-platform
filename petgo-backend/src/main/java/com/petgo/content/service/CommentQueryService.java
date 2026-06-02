package com.petgo.content.service;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.Comment;
import com.petgo.content.domain.PostStatus;
import com.petgo.content.dto.CommentPageResponse;
import com.petgo.content.dto.CommentResponse;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.shared.error.AppException;
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

    public CommentQueryService(CommentRepository comments, ContentPostRepository posts,
            AccountQueryService accountQueryService) {
        this.comments = comments;
        this.posts = posts;
        this.accountQueryService = accountQueryService;
    }

    /** 一级评论分页（时间正序），每条内嵌前 3 条二级回复 + replyCount。 */
    @Transactional(readOnly = true)
    public CommentPageResponse topLevel(long postId, String cursor) {
        requireVisiblePost(postId);
        FeedCursor decoded = decode(cursor);

        List<Comment> rows = comments.findTopLevel(postId,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                PageRequest.of(0, TOP_LEVEL_PAGE_SIZE + 1));

        boolean hasMore = rows.size() > TOP_LEVEL_PAGE_SIZE;
        List<Comment> page = hasMore ? rows.subList(0, TOP_LEVEL_PAGE_SIZE) : rows;

        // 这一页一级评论的全部二级回复（用于取前 3 + 计数）。
        List<Long> parentIds = page.stream().map(Comment::getId).toList();
        Map<Long, List<Comment>> repliesByParent = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            for (Comment r : comments.findRepliesForParents(parentIds)) {
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

    /** 展开某一级评论的全部二级回复（时间正序游标分页）。 */
    @Transactional(readOnly = true)
    public CommentPageResponse replies(long parentId, String cursor) {
        FeedCursor decoded = decode(cursor);
        List<Comment> rows = comments.findReplies(parentId,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
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

    private void requireVisiblePost(long postId) {
        posts.findById(postId)
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
