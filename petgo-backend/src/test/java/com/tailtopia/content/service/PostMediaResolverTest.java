package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaAcl;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.dto.PostMedia;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shared.media.SignedUrlService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：专题线 video Story 2.1 —— 帖子媒体投影（公开地址 / 作者签名地址 / 图文原样）。 */
class PostMediaResolverTest {

    private static final long AUTHOR = 1L;
    private static final String COVER = "https://oss.test/public/1/c.jpg";

    private MediaProperties props;
    private SignedUrlService signer;
    private PostMediaResolver resolver;

    @BeforeEach
    void setUp() {
        props = new MediaProperties();
        props.getOss().setCdnBaseUrl("https://oss.test");
        props.getOss().setPublicBucket("bkt");
        signer = mock(SignedUrlService.class);
        when(signer.sign(eq("bkt"), anyString(), any(Duration.class)))
                .thenAnswer(inv -> "https://signed/" + inv.getArgument(1) + "?Signature=x");
        resolver = new PostMediaResolver(new VideoUrlResolver(new AliyunOssClient(props), signer, props), signer, props);
    }

    private static ContentPost video(boolean published, MediaAcl applied) {
        ContentPost p = published
                ? ContentPost.publish(AUTHOR, ContentType.DAILY, null, "t", List.of(COVER))
                : ContentPost.pendingReview(AUTHOR, ContentType.DAILY, null, "t", List.of(COVER), null, null,
                        ContentPost.REVIEW_REASON_VIDEO_FORCED);
        p.attachVideo("video/1/k.mp4", 48_000, 720, 1280, 10L);
        set(p, "mediaAclApplied", applied);
        return p;
    }

    private static void set(Object o, String name, Object v) {
        try {
            Field f = ContentPost.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void imagePostIsPassedThroughUnchanged() {
        ContentPost p = ContentPost.publish(AUTHOR, ContentType.DAILY, null, "t", List.of(COVER, "https://oss.test/public/1/d.jpg"));
        PostMedia m = resolver.resolve(p, AUTHOR);
        assertThat(m.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(m.video()).isNull();
        assertThat(m.imageUrls()).isSameAs(p.getImageUrls());
        verify(signer, never()).sign(anyString(), anyString(), any());
    }

    @Test
    void publicVideoUsesDistributionUrlAndStoredCover() {
        PostMedia m = resolver.resolve(video(true, MediaAcl.PUBLIC), 99L);
        assertThat(m.mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(m.video().url()).isEqualTo("https://oss.test/video/1/k.mp4");
        assertThat(m.video().durationMs()).isEqualTo(48_000);
        assertThat(m.video().width()).isEqualTo(720);
        assertThat(m.video().height()).isEqualTo(1280);
        assertThat(m.imageUrls()).containsExactly(COVER);
        assertThat(m.firstImageUrl()).isEqualTo(COVER);
    }

    @Test
    void publicVideoUsesCdnWhenConfigured() {
        props.getVideo().setCdnBaseUrl("https://v.cdn.test");
        assertThat(resolver.resolve(video(true, MediaAcl.PUBLIC), null).video().url())
                .isEqualTo("https://v.cdn.test/video/1/k.mp4");
        // 封面仍走 OSS 源站（V-38）。
        assertThat(resolver.resolve(video(true, MediaAcl.PUBLIC), null).imageUrls()).containsExactly(COVER);
    }

    @Test
    void authorSeesUnderReviewVideoWithSignedVideoAndCover() {
        PostMedia m = resolver.resolve(video(false, MediaAcl.PRIVATE), AUTHOR);
        assertThat(m.video().url()).isEqualTo("https://signed/video/1/k.mp4?Signature=x");
        assertThat(m.imageUrls()).containsExactly("https://signed/public/1/c.jpg?Signature=x");
        assertThat(m.firstImageUrl()).startsWith("https://signed/");
        verify(signer, org.mockito.Mockito.times(2)).sign(eq("bkt"),
                org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofMinutes(15)));
    }

    @Test
    void authorSeesPublishedButNotYetAppliedVideoSigned() {
        // 名单内刚发布：desired=PUBLIC、applied=PRIVATE（对账器还没跑到）。
        PostMedia m = resolver.resolve(video(true, MediaAcl.PRIVATE), AUTHOR);
        assertThat(m.video().url()).startsWith("https://signed/");
        assertThat(m.firstImageUrl()).startsWith("https://signed/");
    }

    @Test
    void otherViewerOfNotYetAppliedVideoGetsPublicUrl() {
        PostMedia m = resolver.resolve(video(true, MediaAcl.PRIVATE), 99L);
        assertThat(m.video().url()).isEqualTo("https://oss.test/video/1/k.mp4");
        assertThat(m.imageUrls()).containsExactly(COVER);
        verify(signer, never()).sign(anyString(), anyString(), any());
    }

    @Test
    void guestNeverGetsSignedUrls() {
        PostMedia m = resolver.resolve(video(false, MediaAcl.PRIVATE), null);
        assertThat(m.video().url()).doesNotContain("Signature");
        verify(signer, never()).sign(anyString(), anyString(), any());
    }

    @Test
    void coverKeyNotResolvableKeepsStoredCover() {
        ContentPost p = ContentPost.pendingReview(AUTHOR, ContentType.DAILY, null, "t",
                List.of("https://elsewhere.test/x.jpg"), null, null, ContentPost.REVIEW_REASON_VIDEO_FORCED);
        p.attachVideo("video/1/k.mp4", 48_000, 720, 1280, 10L);
        PostMedia m = resolver.resolve(p, AUTHOR);
        assertThat(m.video().url()).startsWith("https://signed/");
        assertThat(m.imageUrls()).containsExactly("https://elsewhere.test/x.jpg");
    }

    @Test
    void signingFailureFallsBackToPublicUrlInsteadOfFailingThePage() {
        when(signer.sign(eq("bkt"), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("no credential"));
        PostMedia m = resolver.resolve(video(false, MediaAcl.PRIVATE), AUTHOR);
        assertThat(m.video().url()).isEqualTo("https://oss.test/video/1/k.mp4");
        assertThat(m.imageUrls()).containsExactly(COVER);
    }

    // ===== 专题线 video Story 3.3：后台预览一律签名 =====

    @Test
    void adminAlwaysGetsSignedVideoAndCoverEvenWhenPublic() {
        PostMedia m = resolver.resolveForAdmin(video(true, MediaAcl.PUBLIC));
        assertThat(m.mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(m.video().url()).isEqualTo("https://signed/video/1/k.mp4?Signature=x");
        assertThat(m.imageUrls()).containsExactly("https://signed/public/1/c.jpg?Signature=x");
    }

    @Test
    void adminGetsSignedUrlsForUnderReviewOrTakenDownVideo() {
        ContentPost p = video(false, MediaAcl.PRIVATE);
        p.softDelete();
        PostMedia m = resolver.resolveForAdmin(p);
        assertThat(m.video().url()).startsWith("https://signed/");
        assertThat(m.firstImageUrl()).startsWith("https://signed/");
    }

    @Test
    void adminImagePostIsPassedThroughUnchanged() {
        ContentPost p = ContentPost.publish(AUTHOR, ContentType.DAILY, null, "t", List.of(COVER));
        PostMedia m = resolver.resolveForAdmin(p);
        assertThat(m.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(m.imageUrls()).isSameAs(p.getImageUrls());
        verify(signer, never()).sign(anyString(), anyString(), any());
    }

    @Test
    void adminSigningFailureFallsBackToStoredUrls() {
        when(signer.sign(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("no creds"));
        PostMedia m = resolver.resolveForAdmin(video(true, MediaAcl.PUBLIC));
        assertThat(m.mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(m.video().url()).isEqualTo("https://oss.test/video/1/k.mp4");
        assertThat(m.imageUrls()).containsExactly(COVER);
    }
}
