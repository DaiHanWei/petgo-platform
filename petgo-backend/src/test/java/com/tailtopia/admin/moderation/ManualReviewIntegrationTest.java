package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.moderation.service.AdminSettingsService;
import com.tailtopia.admin.moderation.service.ManualReviewService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：人工审核预建（Story 4.3，需 Docker postgres+redis）。验 V39/V40/V41 迁移 validate（上下文启动即证）、
 * 开关默认关＝现网行为、开关开后入队挂起 / 通过 / 拒绝 / 超时丢弃。开关跨测试显式设置（共享上下文）。
 */
class ManualReviewIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentService contentService;
    @Autowired
    private AdminSettingsService settingsService;
    @Autowired
    private ManualReviewService reviewService;
    @Autowired
    private ManualReviewItemRepository queue;
    @Autowired
    private ContentPostRepository posts;

    private ContentPostCreateRequest blocked() {
        // "judi" 命中 ContentModerationService 关键词黑名单 → 未过自动审核。
        return new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null);
    }

    @Test
    void disabledKeepsLegacyPublishFailure() {
        settingsService.setManualReviewEnabled(false, 430000L + SEQ.incrementAndGet());
        long author = newUser().getId();

        // 现网 FR-12：拦截即发布失败、不落库、不入队。
        assertThatThrownBy(() -> contentService.publish(author, blocked(), null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void enabledEnqueuesUnderReviewThenApprovePublishes() {
        settingsService.setManualReviewEnabled(true, 430000L + SEQ.incrementAndGet());
        long author = newUser().getId();

        long postId = contentService.publish(author, blocked(), null).id();

        // 落 UNDER_REVIEW（不进公开口径）+ 队列 PENDING。
        assertThat(posts.findById(postId).orElseThrow().getStatus()).isEqualTo(PostStatus.UNDER_REVIEW);
        ManualReviewItem item = pendingFor(postId);
        assertThat(item.getStatus()).isEqualTo(ReviewStatus.PENDING);

        reviewService.approve(item.getId(), 430000L + SEQ.incrementAndGet());

        assertThat(posts.findById(postId).orElseThrow().getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(queue.findById(item.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void enabledRejectDiscardsContent() {
        settingsService.setManualReviewEnabled(true, 430000L + SEQ.incrementAndGet());
        long author = newUser().getId();
        long postId = contentService.publish(author, blocked(), null).id();
        ManualReviewItem item = pendingFor(postId);

        reviewService.reject(item.getId(), 430000L + SEQ.incrementAndGet());

        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isTrue();
        assertThat(queue.findById(item.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void timeoutScanDiscardsExpiredPending() {
        settingsService.setManualReviewEnabled(true, 430000L + SEQ.incrementAndGet());
        long author = newUser().getId();
        // 直接造一条 4 天前的 UNDER_REVIEW 内容 + PENDING 队列项。
        ContentPost p = posts.save(
                ContentPost.pendingReview(author, ContentType.DAILY, null, "超时内容", List.of(), null));
        ManualReviewItem item = queue.save(
                ManualReviewItem.pending(p.getId(), Instant.now().minusSeconds(86400L * 4)));

        int processed = reviewService.scanTimeouts(Instant.now());

        assertThat(processed).isGreaterThanOrEqualTo(1);
        assertThat(queue.findById(item.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.TIMED_OUT);
        assertThat(contentService.findSummary(p.getId()).orElseThrow().deleted()).isTrue();
    }

    private ManualReviewItem pendingFor(long contentId) {
        return queue.findByStatusOrderBySubmittedAtAsc(ReviewStatus.PENDING).stream()
                .filter(it -> it.getContentId() == contentId).findFirst().orElseThrow();
    }
}
