package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shared.media.SignedUrlService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：专题线 video Story 1.4 —— 视频地址拼接（CDN / 源站）、签名有效期、只接受视频帖。 */
class VideoUrlResolverTest {

    private MediaProperties props;
    private SignedUrlService signer;
    private VideoUrlResolver resolver;

    @BeforeEach
    void setUp() {
        props = new MediaProperties();
        props.getOss().setCdnBaseUrl("https://oss.test");
        props.getOss().setPublicBucket("bkt");
        signer = mock(SignedUrlService.class);
        resolver = new VideoUrlResolver(new AliyunOssClient(props), signer, props);
    }

    private static ContentPost video(int durationMs) {
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "t",
                List.of("https://oss.test/stag/public/1/c.jpg"));
        p.attachVideo("stag/video/1/k.mp4", durationMs, 720, 1280, 10L);
        return p;
    }

    @Test
    void publicUrlFallsBackToOssOriginWithoutCdn() {
        assertThat(resolver.publicUrl(video(5000))).isEqualTo("https://oss.test/stag/video/1/k.mp4");
    }

    @Test
    void publicUrlUsesCdnWhenConfigured() {
        props.getVideo().setCdnBaseUrl("https://v.cdn.test//");
        assertThat(resolver.publicUrl(video(5000))).isEqualTo("https://v.cdn.test/stag/video/1/k.mp4");
    }

    @Test
    void signedTtlIsMax15MinOrThreeTimesDuration() {
        assertThat(resolver.signedTtl(video(5000))).isEqualTo(Duration.ofMinutes(15));
        assertThat(resolver.signedTtl(video(90_000))).isEqualTo(Duration.ofMinutes(15));
        assertThat(resolver.signedTtl(video(600_000))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void forAdminSignsInPublicBucket() {
        when(signer.sign(eq("bkt"), eq("stag/video/1/k.mp4"), eq(Duration.ofMinutes(15)))).thenReturn("signed");
        assertThat(resolver.forAdmin(video(5000))).isEqualTo("signed");
        verify(signer).sign("bkt", "stag/video/1/k.mp4", Duration.ofMinutes(15));
    }

    @Test
    void coverKeyIsResolvedFromFirstImage() {
        assertThat(resolver.coverObjectKey(video(5000))).contains("stag/public/1/c.jpg");
    }

    @Test
    void imagePostsAreRejected() {
        ContentPost img = ContentPost.publish(1L, ContentType.DAILY, null, "t", List.of("a.jpg"));
        assertThatThrownBy(() -> resolver.publicUrl(img)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.signedTtl(img)).isInstanceOf(IllegalArgumentException.class);
    }
}
