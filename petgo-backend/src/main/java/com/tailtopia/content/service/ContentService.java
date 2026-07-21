package com.tailtopia.content.service;

import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ContentModerationService moderation;
    private final ApplicationEventPublisher events;
    private final ManualReviewGate manualReviewGate;

    public ContentService(ContentPostRepository posts, CommentRepository comments,
            ContentLikeRepository likes, ProfileService profileService,
            IdempotencyService idempotency, ContentModerationService moderation,
            ApplicationEventPublisher events, ManualReviewGate manualReviewGate) {
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.profileService = profileService;
        this.idempotency = idempotency;
        this.moderation = moderation;
        this.events = events;
        this.manualReviewGate = manualReviewGate;
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
        // AC3 ②：仅运营下架通知作者「内容因违规被移除」；作者自删不发事件（不自通知）。
        // 经领域事件 → notify 消费（content 不直调 notify）；不说明举报人、V1 无申诉入口。
        if (reason == DeleteReason.ADMIN_TAKEDOWN) {
            events.publishEvent(new ContentRemovedEvent(
                    post.getId(), post.getAuthorId(), Instant.now()));
        }
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

    /** 内容摘要（Story 3.7：Admin 举报队列快照，含已删态供运营核对；4.1 加 authorId）。 */
    @Transactional(readOnly = true)
    public Optional<PostSummary> findSummary(long postId) {
        return posts.findById(postId).map(p -> new PostSummary(
                p.getId(), p.getType(), p.getText(), p.getDeletedAt() != null, p.getAuthorId()));
    }

    /** 举报队列快照投影。 */
    public record PostSummary(long id, ContentType type, String textPreview, boolean deleted, Long authorId) {
    }

    /** 后台用户详情（Story 3.1）：某作者全部内容摘要（含已下架，运营视角，createdAt 倒序）。 */
    @Transactional(readOnly = true)
    public List<PostSummary> listByAuthorForAdmin(long authorId) {
        return posts.findByAuthorIdOrderByCreatedAtDesc(authorId).stream()
                .map(p -> new PostSummary(p.getId(), p.getType(), p.getText(), p.getDeletedAt() != null,
                        p.getAuthorId()))
                .toList();
    }

    /** 后台全量内容浏览/筛选/搜索（Story 4.2）：跨作者，含已下架。经 Criteria 动态条件。 */
    @Transactional(readOnly = true)
    public List<com.tailtopia.content.dto.AdminContentRow> adminSearch(ContentType type, Long authorId,
            java.time.Instant from, java.time.Instant to, Boolean deleted, String keyword,
            int limit, int offset) {
        return posts.adminSearch(type, authorId, from, to, deleted, keyword, limit, offset);
    }

    /** 后台恢复已下架内容（Story 4.2）：清 deletedAt（幂等：未删 no-op）。评论保持软删、点赞不还原（V1 接受）。 */
    @Transactional
    public void restore(long postId) {
        posts.findById(postId).ifPresent(p -> {
            if (p.getDeletedAt() != null) {
                p.restore();
                posts.save(p);
            }
        });
    }

    /**
     * 人工审核通过（Story 4.3）：{@code UNDER_REVIEW → PUBLISHED} + 发 {@link ContentPublishedEvent}
     * （进 Feed、触发既有里程碑等副作用）。幂等：非挂起态 no-op。content 拥有表，admin 经此方法变更。
     */
    @Transactional
    public void approveReview(long postId) {
        posts.findById(postId).ifPresent(p -> {
            if (p.getStatus() == PostStatus.UNDER_REVIEW && p.getDeletedAt() == null) {
                p.approveReview();
                posts.save(p);
                long growthCount = p.getType() == ContentType.GROWTH_MOMENT
                        ? posts.countByAuthorIdAndTypeAndDeletedAtIsNullAndStatus(
                                p.getAuthorId(), ContentType.GROWTH_MOMENT, PostStatus.PUBLISHED)
                        : 0L;
                events.publishEvent(new ContentPublishedEvent(p.getId(), p.getAuthorId(), p.getType(),
                        p.getPetId(), growthCount, p.getCreatedAt()));
            }
        });
    }

    /**
     * 人工审核拒绝/超时丢弃（Story 4.3）：挂起内容软删丢弃（不发布、不进公开口径）。幂等：仅处置 UNDER_REVIEW 未删项。
     */
    @Transactional
    public void discardReview(long postId) {
        posts.findById(postId).ifPresent(p -> {
            if (p.getStatus() == PostStatus.UNDER_REVIEW && p.getDeletedAt() == null) {
                p.softDelete();
                posts.save(p);
            }
        });
    }

    /**
     * 违规处置 D2（Story 3.3）：下架某作者全部未删内容（{@code ADMIN_TAKEDOWN}），Feed/我的发布/成长档案移除。
     * 幂等（已软删跳过）。经既有 {@link #softDelete} 复用关联清理。返回本次下架数。
     */
    @Transactional
    public int takedownAllByAuthor(long authorId) {
        int count = 0;
        for (var p : posts.findByAuthorIdOrderByCreatedAtDesc(authorId)) {
            if (p.getDeletedAt() == null) {
                softDelete(p.getId(), DeleteReason.ADMIN_TAKEDOWN);
                count++;
            }
        }
        return count;
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

        String text = blankToNull(req.text());
        List<String> imageUrls = req.imageUrls();
        if (imageUrls != null && imageUrls.size() > 9) {
            throw AppException.validation("最多 9 张图片");
        }

        // AC6（R2）：最低内容门槛——文字与图片皆空不可发布（服务端权威，前端置灰仅辅助）。
        if (text == null && (imageUrls == null || imageUrls.isEmpty())) {
            throw AppException.validation("请填写文字或上传图片");
        }

        Long petId = req.petId();
        LocalDate eventDate = null;
        if (req.type() == ContentType.GROWTH_MOMENT) {
            // 成长日历必须绑定属于当前用户的宠物档案。
            if (petId == null) {
                throw AppException.validation("成长日历快乐时刻需绑定宠物档案");
            }
            if (!profileService.ownsPet(authorId, petId)) {
                throw AppException.validation("无法绑定该宠物档案");
            }
            // AC5（R2 · F9）：事件日期仅 GROWTH_MOMENT 有值；缺省取今天（UTC）；不可未来。
            eventDate = req.eventDate() != null ? req.eventDate() : LocalDate.now(java.time.ZoneOffset.UTC);
            if (eventDate.isAfter(LocalDate.now(java.time.ZoneOffset.UTC))) {
                throw AppException.validation("事件日期不能晚于今天");
            }
        } else {
            // 普通类型不绑宠物、不写事件日期，忽略客户端误传的 petId/eventDate。
            petId = null;
        }

        // AC8（R2 · F10）：发布写库前三方自动审核——文字关键词 + 图像识别。
        ContentModerationService.Verdict verdict = moderation.moderate(text, imageUrls);
        if (verdict != ContentModerationService.Verdict.PASS) {
            // Story 4.3：未过自动审核——分支取决于人工审核开关（默认关 = 现网 FR-12 行为）。
            if (!manualReviewGate.enabled()) {
                // 默认路径：直接发布失败、不落库、不进队列（现网行为，AC2 必须保持不变）。
                switch (verdict) {
                    case TEXT_BLOCKED -> throw AppException.contentTextBlocked("内容包含不当词汇，请修改后重试");
                    case IMAGE_BLOCKED -> throw AppException.contentImageBlocked("图片包含违规内容，请替换后重试");
                    default -> { /* 不可达：已确保非 PASS 即 TEXT/IMAGE_BLOCKED */ }
                }
            }
            // 人工审核已激活：落 UNDER_REVIEW + 入队挂起，不发 ContentPublishedEvent（不进 Feed）。
            ContentPost pending = posts.save(ContentPost.pendingReview(
                    authorId, req.type(), petId, text, imageUrls, eventDate));
            idempotency.store(idempotencyKey, pending.getId());
            manualReviewGate.enqueue(pending.getId());
            return ContentPostResponse.from(pending);
        }

        ContentPost saved = posts.save(ContentPost.publish(
                authorId, req.type(), petId, text, imageUrls, eventDate));

        idempotency.store(idempotencyKey, saved.getId());

        // 里程碑自动完成（Story 8.3）：发布领域事件供 profile 订阅（首张成长日历 S2 / 首条日常 S5 /
        // 计数类 M10·L5）。GROWTH_MOMENT 携发布后总数供计数判定，非该类为 0。content 不直调 profile 里程碑。
        long growthCount = req.type() == ContentType.GROWTH_MOMENT
                ? posts.countByAuthorIdAndTypeAndDeletedAtIsNullAndStatus(
                        authorId, ContentType.GROWTH_MOMENT, PostStatus.PUBLISHED)
                : 0L;
        events.publishEvent(new ContentPublishedEvent(
                saved.getId(), authorId, req.type(), petId, growthCount, saved.getCreatedAt()));
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
    public List<GrowthMomentView> findGrowthMoments(long authorId, long petId, Instant before, int limit) {
        Instant cursor = before == null ? Instant.now() : before;
        return posts.findByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
                        authorId, petId, ContentType.GROWTH_MOMENT, cursor, PageRequest.of(0, limit))
                .stream()
                .map(ContentService::toGrowthMomentView)
                .toList();
    }

    /**
     * 某作者在某月（按 {@code event_date}）的全部快乐时刻（Story 2.4 R2 · F9 日历视图）。
     * event_date 升序、同日 created_at 升序——供 profile 按日聚合时取「该日最早 created_at」首图。
     */
    @Transactional(readOnly = true)
    public List<GrowthMomentView> findGrowthMomentsInMonth(long authorId, long petId, LocalDate from, LocalDate to) {
        return posts.findByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndEventDateBetweenOrderByEventDateAscCreatedAtAsc(
                        authorId, petId, ContentType.GROWTH_MOMENT, from, to)
                .stream()
                .map(ContentService::toGrowthMomentView)
                .toList();
    }

    /**
     * 某作者在某 {@code event_date} 当天的快乐时刻（Story 2.4 R2 · F9 当天详情），created_at 正序。
     */
    @Transactional(readOnly = true)
    public List<GrowthMomentView> findGrowthMomentsOnDate(long authorId, long petId, LocalDate eventDate) {
        return posts.findByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndEventDateOrderByCreatedAtAsc(
                        authorId, petId, ContentType.GROWTH_MOMENT, eventDate)
                .stream()
                .map(ContentService::toGrowthMomentView)
                .toList();
    }

    /**
     * 名片快乐时刻流（Story 2.6 AC7 · F9）：按 {@code event_date} 倒序取最近 limit 条 GROWTH_MOMENT。
     * 经 service 接口供 H5 名片取数（禁 join）。
     */
    @Transactional(readOnly = true)
    public List<GrowthMomentView> findRecentGrowthMomentsByEventDate(long authorId, int limit) {
        return posts.findByAuthorIdAndTypeAndDeletedAtIsNullOrderByEventDateDescCreatedAtDesc(
                        authorId, ContentType.GROWTH_MOMENT, PageRequest.of(0, limit))
                .stream()
                .map(ContentService::toGrowthMomentView)
                .toList();
    }

    /** 某作者快乐时刻总数（Story 2.4 AC5 统计栏）。 */
    @Transactional(readOnly = true)
    public long countGrowthMoments(long authorId, long petId) {
        return posts.countByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndStatus(
                authorId, petId, ContentType.GROWTH_MOMENT, PostStatus.PUBLISHED);
    }

    /**
     * 校验 {@code postId} 是否为 {@code ownerId} 本人的、未删的成长日历内容（里程碑用户打卡关联，Story 8.4）。
     * 经 service 接口供 profile 模块调用，**避免 profile 直读 content_posts 表**（架构边界）。
     */
    @Transactional(readOnly = true)
    public boolean isOwnGrowthMoment(long ownerId, long postId) {
        return posts.findById(postId)
                .map(p -> p.getAuthorId() == ownerId
                        && p.getType() == ContentType.GROWTH_MOMENT
                        && p.getDeletedAt() == null
                        && p.getStatus() == PostStatus.PUBLISHED)
                .orElse(false);
    }

    private static GrowthMomentView toGrowthMomentView(ContentPost p) {
        return new GrowthMomentView(
                p.getId(), p.getCreatedAt(), p.getEventDate(), p.getImageUrls(), p.getText());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
