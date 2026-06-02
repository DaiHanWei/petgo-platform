package com.petgo.content.service;

import com.petgo.content.domain.Comment;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.domain.DeleteReason;
import com.petgo.content.domain.PostStatus;
import com.petgo.content.dto.ContentPostCreateRequest;
import com.petgo.content.dto.ContentPostResponse;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentLikeRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.profile.service.ProfileService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.IdempotencyService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容发布 + 删除服务（Story 2.3 / 3.6）。三类发布 + 成长日历绑宠物校验 + Idempotency-Key 去重；
 * 软删（作者删 / 运营下架复用）+ 级联清评论点赞。
 *
 * <p>模块边界：成长日历归属校验经 {@link ProfileService}（**禁 content 直接读 pet_profiles 表**）。
 * {@code authorId} 一律取自 JWT，不信任客户端。
 */
@Service
public class ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final ContentPostRepository posts;
    private final CommentRepository comments;
    private final ContentLikeRepository likes;
    private final ProfileService profileService;
    private final IdempotencyService idempotency;

    public ContentService(ContentPostRepository posts, CommentRepository comments,
            ContentLikeRepository likes, ProfileService profileService,
            IdempotencyService idempotency) {
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.profileService = profileService;
        this.idempotency = idempotency;
    }

    /**
     * 作者删除内容（Story 3.6）。仅作者本人；非作者 403；已删幂等。软删 + 级联清。
     */
    @Transactional
    public void deleteByAuthor(long postId, long userId) {
        ContentPost post = posts.findById(postId).orElse(null);
        if (post == null || post.getDeletedAt() != null) {
            return; // 不存在/已删：幂等成功（不暴露曾否存在）
        }
        if (post.getAuthorId() != userId) {
            throw AppException.forbidden("只能删除自己的内容");
        }
        doSoftDelete(post, DeleteReason.AUTHOR_DELETE);
    }

    /**
     * 软删内容（无权限校验，供 Story 3.7 运营下架复用）。已删幂等。
     */
    @Transactional
    public void softDelete(long postId, DeleteReason reason) {
        posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .ifPresent(p -> doSoftDelete(p, reason));
    }

    /** 软删 + 级联：评论级联软删、点赞物理清除。事务内完成保证一致性。 */
    private void doSoftDelete(ContentPost post, DeleteReason reason) {
        post.softDelete();
        posts.save(post);
        for (Comment c : comments.findByPostIdAndDeletedAtIsNull(post.getId())) {
            c.softDelete();
            comments.save(c);
        }
        likes.deleteByPostId(post.getId());
        log.info("内容软删 postId={} reason={}", post.getId(), reason);
    }

    /** 迷你主页发布数（Story 3.8）：某作者未软删的已发布内容数。经 service 暴露，不让 auth 直读 content 表。 */
    @Transactional(readOnly = true)
    public long countPublishedByAuthor(long authorId) {
        return posts.countByAuthorIdAndDeletedAtIsNullAndStatus(authorId, PostStatus.PUBLISHED);
    }

    /** 内容是否存在且可见（Story 3.7：举报前校验，经 service 暴露给 moderation，不让其直读 content 表）。 */
    @Transactional(readOnly = true)
    public boolean isVisible(long postId) {
        return posts.findById(postId).map(p -> p.getDeletedAt() == null).orElse(false);
    }

    /** 内容摘要（Story 3.7：Admin 举报队列快照，含已删态供运营核对）。 */
    @Transactional(readOnly = true)
    public Optional<PostSummary> findSummary(long postId) {
        return posts.findById(postId).map(p -> new PostSummary(
                p.getId(), p.getType(), p.getText(), p.getDeletedAt() != null));
    }

    /** 举报队列快照投影。 */
    public record PostSummary(long id, ContentType type, String textPreview, boolean deleted) {
    }

    @Transactional
    public ContentPostResponse publish(long authorId, ContentPostCreateRequest req, String idempotencyKey) {
        // 幂等重放：同 key 已落一条则取回，不重复创建。
        Optional<Long> existing = idempotency.findResourceId(idempotencyKey);
        if (existing.isPresent()) {
            return posts.findById(existing.get())
                    .map(ContentPostResponse::from)
                    .orElseThrow(() -> AppException.notFound("内容不存在"));
        }

        Long petId = req.petId();
        if (req.type() == ContentType.GROWTH_MOMENT) {
            // 成长日历必须绑定属于当前用户的宠物档案。
            if (petId == null) {
                throw AppException.validation("成长日历快乐时刻需绑定宠物档案");
            }
            if (!profileService.ownsPet(authorId, petId)) {
                throw AppException.validation("无法绑定该宠物档案");
            }
        } else {
            // 普通类型不绑宠物，忽略客户端误传的 petId。
            petId = null;
        }

        List<String> imageUrls = req.imageUrls();
        if (imageUrls != null && imageUrls.size() > 9) {
            throw AppException.validation("最多 9 张图片");
        }

        ContentPost saved = posts.save(ContentPost.publish(
                authorId, req.type(), petId, blankToNull(req.text()), imageUrls));

        idempotency.store(idempotencyKey, saved.getId());
        return ContentPostResponse.from(saved);
    }

    /**
     * 取某作者的成长日历「快乐时刻」（GROWTH_MOMENT，未删），createdAt 倒序游标分页。
     * 供 profile 时间线聚合经 service 接口调用（Story 2.4）。
     *
     * @param before 仅取该时刻之前的（null = 从最新开始）
     * @param limit  本批最多条数
     */
    @Transactional(readOnly = true)
    public List<GrowthMomentView> findGrowthMoments(long authorId, Instant before, int limit) {
        Instant cursor = before == null ? Instant.now() : before;
        return posts.findByAuthorIdAndTypeAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
                        authorId, ContentType.GROWTH_MOMENT, cursor, PageRequest.of(0, limit))
                .stream()
                .map(p -> new GrowthMomentView(p.getId(), p.getCreatedAt(), p.getImageUrls(), p.getText()))
                .toList();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
