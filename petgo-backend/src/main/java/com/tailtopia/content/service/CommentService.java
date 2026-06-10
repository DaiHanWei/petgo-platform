package com.petgo.content.service;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.Comment;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.PostStatus;
import com.petgo.content.dto.CommentResponse;
import com.petgo.content.event.ContentCommentedEvent;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论写入/删除（Story 3.5，FR-24）。两级约束（回复二级归并到其一级，绝不三级）、删除权限矩阵
 * （评论作者 / 内容作者）+ 删一级级联软删二级、互动事件产出。读取在 Story 3.3。
 */
@Service
public class CommentService {

    private final CommentRepository comments;
    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final ApplicationEventPublisher events;

    public CommentService(CommentRepository comments, ContentPostRepository posts,
            AccountQueryService accountQueryService, ApplicationEventPublisher events) {
        this.comments = comments;
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.events = events;
    }

    /** 发表一级评论。 */
    @Transactional
    public CommentResponse createTopLevel(long postId, long authorId, String body) {
        ContentPost post = requireVisible(postId);
        Comment saved = comments.save(Comment.create(postId, null, authorId, body));
        events.publishEvent(new ContentCommentedEvent(
                postId, saved.getId(), authorId, post.getAuthorId(), null, Instant.now()));
        return CommentResponse.topLevel(saved, authorView(authorId), 0, List.of());
    }

    /** 回复（二级）。回复二级评论时归并到其一级父（两级约束，绝不三级）。 */
    @Transactional
    public CommentResponse createReply(long parentId, long authorId, String body) {
        Comment parent = comments.findById(parentId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        ContentPost post = requireVisible(parent.getPostId());

        // 两级约束：若被回复者本身是二级，则归并到它的一级父。
        long topLevelParentId = parent.isTopLevel() ? parent.getId() : parent.getParentId();
        long parentAuthorId = parent.isTopLevel()
                ? parent.getAuthorId()
                : comments.findById(topLevelParentId).map(Comment::getAuthorId).orElse(parent.getAuthorId());

        Comment saved = comments.save(Comment.create(post.getId(), topLevelParentId, authorId, body));
        events.publishEvent(new ContentCommentedEvent(
                post.getId(), saved.getId(), authorId, post.getAuthorId(), parentAuthorId, Instant.now()));
        return CommentResponse.reply(saved, authorView(authorId));
    }

    /**
     * 删除评论。权限 = 评论作者本人 OR 该评论所属内容作者；否则 403。
     * 删一级 → 级联软删其全部二级；删二级 → 仅该条。
     */
    @Transactional
    public void delete(long commentId, long userId) {
        Comment c = comments.findById(commentId)
                .filter(cm -> cm.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在"));

        long contentAuthorId = posts.findById(c.getPostId())
                .map(ContentPost::getAuthorId)
                .orElse(-1L);
        boolean allowed = userId == c.getAuthorId() || userId == contentAuthorId;
        if (!allowed) {
            throw AppException.forbidden("无权删除该评论");
        }

        c.softDelete();
        comments.save(c);
        if (c.isTopLevel()) {
            // 级联软删全部二级（事务内）。
            for (Comment reply : comments.findByParentIdAndDeletedAtIsNull(c.getId())) {
                reply.softDelete();
                comments.save(reply);
            }
        }
    }

    private ContentPost requireVisible(long postId) {
        return posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> AppException.notFound(ContentDetailService.GONE_DETAIL));
    }

    private AuthorView authorView(long authorId) {
        return accountQueryService.findAuthorViews(List.of(authorId)).get(authorId);
    }
}
