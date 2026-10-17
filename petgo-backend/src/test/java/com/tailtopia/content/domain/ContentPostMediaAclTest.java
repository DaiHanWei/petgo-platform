package com.tailtopia.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** L0：专题线 video Story 1.3 —— {@code desiredOf} 推导与实体状态方法内的重算（AD-6）。 */
class ContentPostMediaAclTest {

    private static ContentPost video(PostStatus status) {
        ContentPost p = status == PostStatus.UNDER_REVIEW
                ? ContentPost.pendingReview(1L, ContentType.DAILY, null, "t", List.of("c"), null, null,
                        ContentPost.REVIEW_REASON_VIDEO_FORCED)
                : ContentPost.publish(1L, ContentType.DAILY, null, "t", List.of("c"));
        p.attachVideo("video/1/k.mp4", 5000, 720, 1280, 10L);
        return p;
    }

    @ParameterizedTest
    @EnumSource(PostStatus.class)
    void desiredOfFullMatrix(PostStatus status) {
        for (Instant deletedAt : new Instant[] {null, Instant.now()}) {
            for (ContentVisibility vis : ContentVisibility.values()) {
                MediaAcl expected = status == PostStatus.PUBLISHED && deletedAt == null
                        && vis == ContentVisibility.PUBLIC ? MediaAcl.PUBLIC : MediaAcl.PRIVATE;
                assertThat(ContentPost.desiredOf(status, deletedAt, vis))
                        .as("%s / deleted=%s / %s", status, deletedAt != null, vis)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void attachVideoInitialisesColumnsAndAcl() {
        ContentPost p = video(PostStatus.PUBLISHED);
        assertThat(p.getMediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(p.getImageSizes()).containsExactly(new ImageSize(720, 1280));
        assertThat(p.getImageCropOffsets()).isNull();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PUBLIC);
        assertThat(p.getMediaAclApplied()).isEqualTo(MediaAcl.PRIVATE);
        assertThat(p.getMediaAclDesiredAt()).isNotNull();

        ContentPost pending = video(PostStatus.UNDER_REVIEW);
        assertThat(pending.getMediaAclDesired()).isEqualTo(MediaAcl.PRIVATE);
    }

    @Test
    void stateMethodsRecomputeDesired() {
        ContentPost p = video(PostStatus.UNDER_REVIEW);
        p.approveReview();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PUBLIC);
        p.applyReportHold();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PRIVATE);
        p.releaseReportHold();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PUBLIC);
        p.softDelete();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PRIVATE);
        p.restore();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PUBLIC);
        p.setVisibility(ContentVisibility.PRIVATE);
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PRIVATE);
        // applied 只由对账器写，状态方法一律不碰。
        assertThat(p.getMediaAclApplied()).isEqualTo(MediaAcl.PRIVATE);
    }

    @Test
    void restoringAPostStillUnderReviewStaysPrivate() {
        ContentPost p = video(PostStatus.UNDER_REVIEW);
        p.softDelete();
        p.restore();
        assertThat(p.getMediaAclDesired()).isEqualTo(MediaAcl.PRIVATE);
    }

    @Test
    void desiredAtOnlyMovesWhenValueChanges() throws Exception {
        ContentPost p = video(PostStatus.PUBLISHED);
        Instant first = p.getMediaAclDesiredAt();
        Thread.sleep(2);
        p.restore(); // 未删 → 仍 PUBLIC
        p.setVisibility(ContentVisibility.PUBLIC);
        assertThat(p.getMediaAclDesiredAt()).isEqualTo(first);
        p.softDelete();
        assertThat(p.getMediaAclDesiredAt()).isAfter(first);
    }

    @Test
    void imagePostsNeverGetAclColumns() {
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "t", List.of("a.jpg"));
        p.setVisibility(ContentVisibility.PUBLIC);
        p.applyReportHold();
        p.releaseReportHold();
        p.softDelete();
        p.restore();
        assertThat(p.getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(p.getMediaAclDesired()).isNull();
        assertThat(p.getMediaAclApplied()).isNull();
        assertThat(p.getMediaAclDesiredAt()).isNull();
    }

    @Test
    void appliedColumnIsNotUpdatable() throws Exception {
        var col = ContentPost.class.getDeclaredField("mediaAclApplied")
                .getAnnotation(jakarta.persistence.Column.class);
        assertThat(col.updatable()).isFalse();
    }
}
