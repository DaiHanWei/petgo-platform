package com.petgo.content.service;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.PostStatus;
import com.petgo.content.dto.ContentDetailResponse;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentLikeRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.shared.error.AppException;
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

    private final ContentPostRepository posts;
    private final CommentRepository comments;
    private final ContentLikeRepository likes;
    private final AccountQueryService accountQueryService;

    public ContentDetailService(ContentPostRepository posts, CommentRepository comments,
            ContentLikeRepository likes, AccountQueryService accountQueryService) {
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.accountQueryService = accountQueryService;
    }

    /**
     * 取内容详情。
     *
     * @param postId   内容 id
     * @param viewerId 当前用户 id（游客为 null，用于 isAuthor / liked）
     */
    @Transactional(readOnly = true)
    public ContentDetailResponse getDetail(long postId, Long viewerId) {
        ContentPost post = posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> AppException.notFound(GONE_DETAIL));

        AuthorView author = accountQueryService.findAuthorViews(List.of(post.getAuthorId()))
                .get(post.getAuthorId());
        long commentCount = comments.countByPostIdAndDeletedAtIsNull(postId);
        // 用 equals 而非 ==：两者均为装箱 Long，== 是引用比较，id>127（越过 Long 缓存）会误判 false。
        boolean isAuthor = viewerId != null && viewerId.equals(post.getAuthorId());
        // Story 3.4：真实点赞计数 + 当前用户是否已赞（游客 false）。
        long likeCount = likes.countByPostId(postId);
        boolean liked = viewerId != null && likes.existsByPostIdAndUserId(postId, viewerId);
        return ContentDetailResponse.of(post, author, likeCount, commentCount, liked, isAuthor);
    }
}
