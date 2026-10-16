package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaAcl;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.CdnCacheRefresher;
import com.tailtopia.shared.media.MediaProperties;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** L0：专题线 video Story 1.4 —— 对账顺序、条件回写、单条失败隔离、对象缺失收敛、卡住告警。 */
class MediaAclReconcilerTest {

    private ContentPostRepository posts;
    private AliyunOssClient oss;
    private CdnCacheRefresher cdn;
    private MediaProperties props;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        oss = mock(AliyunOssClient.class);
        cdn = mock(CdnCacheRefresher.class);
        props = new MediaProperties();
        props.getOss().setPublicBucket("bkt");
        props.getOss().setCdnBaseUrl("https://oss.test");
        when(oss.hasCredentials()).thenReturn(true);
        when(oss.objectKeyOfPublicUrl(anyString())).thenAnswer(inv ->
                new AliyunOssClient(props).objectKeyOfPublicUrl(inv.getArgument(0)));
        when(oss.publicUrl(anyString())).thenAnswer(inv -> "https://oss.test/" + inv.getArgument(0));
        when(oss.setObjectAcl(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(posts.markMediaAclApplied(anyLong(), anyString())).thenReturn(1);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(MediaAclReconciler.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(MediaAclReconciler.class)).detachAppender(logs);
    }

    private MediaAclReconciler reconciler(Instant now) {
        return new MediaAclReconciler(posts, oss, cdn, new VideoUrlResolver(oss, null, props), props,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static ContentPost video(long id, boolean published) {
        ContentPost p = published
                ? ContentPost.publish(1L, ContentType.DAILY, null, "t", List.of("https://oss.test/public/1/c" + id + ".jpg"))
                : ContentPost.pendingReview(1L, ContentType.DAILY, null, "t",
                        List.of("https://oss.test/public/1/c" + id + ".jpg"), null, null, "VIDEO_FORCED");
        p.attachVideo("video/1/v" + id + ".mp4", 5000, 720, 1280, 10L);
        setField(p, "id", id);
        return p;
    }

    private static void setField(Object o, String name, Object v) {
        try {
            Field f = ContentPost.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void publicDirectionAppliesVideoThenCoverThenCdnThenConditionalWriteback() {
        ContentPost p = video(1, true);
        when(posts.findMediaAclPending(any())).thenReturn(List.of(p));

        reconciler(Instant.now()).run();

        var order = inOrder(oss, cdn, posts);
        order.verify(oss).setObjectAcl("bkt", "video/1/v1.mp4", true);
        order.verify(oss).setObjectAcl("bkt", "public/1/c1.jpg", true);
        order.verify(cdn).refresh("https://oss.test/video/1/v1.mp4");
        order.verify(posts).markMediaAclApplied(1L, "PUBLIC");
    }

    @Test
    void privateDirectionAlsoRefreshesCdn() {
        ContentPost p = video(2, true);
        p.softDelete(); // desired → PRIVATE；applied 仍是 PRIVATE 初值，手工设成 PUBLIC 模拟已公开过
        setField(p, "mediaAclApplied", MediaAcl.PUBLIC);
        when(posts.findMediaAclPending(any())).thenReturn(List.of(p));

        reconciler(Instant.now()).run();

        verify(oss).setObjectAcl("bkt", "video/1/v2.mp4", false);
        verify(oss).setObjectAcl("bkt", "public/1/c2.jpg", false);
        verify(cdn).refresh(anyString());
        verify(posts).markMediaAclApplied(2L, "PRIVATE");
    }

    @Test
    void zeroRowsWrittenBackIsSilentAndRetriedNextRound() {
        ContentPost p = video(3, true);
        when(posts.findMediaAclPending(any())).thenReturn(List.of(p));
        when(posts.markMediaAclApplied(3L, "PUBLIC")).thenReturn(0);

        reconciler(Instant.now()).run();

        assertThat(messages()).noneMatch(m -> m.startsWith("video_acl"));
    }

    @Test
    void oneFailureDoesNotBlockTheRestOfTheBatch() {
        ContentPost a = video(10, true);
        ContentPost b = video(11, true);
        ContentPost c = video(12, true);
        when(posts.findMediaAclPending(any())).thenReturn(List.of(a, b, c));
        when(oss.setObjectAcl("bkt", "video/1/v11.mp4", true)).thenThrow(new IllegalStateException("boom"));

        reconciler(Instant.now()).run();

        verify(posts).markMediaAclApplied(10L, "PUBLIC");
        verify(posts, never()).markMediaAclApplied(eq(11L), anyString());
        verify(posts).markMediaAclApplied(12L, "PUBLIC");
        assertThat(messages()).anyMatch(m -> m.startsWith("video_acl_reconcile_failed postId=11"));
    }

    @Test
    void missingVideoOrCoverConvergesWithoutCdnRefresh() {
        ContentPost noVideo = video(20, true);
        ContentPost noCover = video(21, true);
        ContentPost badCoverUrl = video(22, true);
        setField(badCoverUrl, "imageUrls", List.of("https://evil.example/x.jpg"));
        when(posts.findMediaAclPending(any())).thenReturn(List.of(noVideo, noCover, badCoverUrl));
        when(oss.setObjectAcl("bkt", "video/1/v20.mp4", true)).thenReturn(false);
        when(oss.setObjectAcl("bkt", "public/1/c21.jpg", true)).thenReturn(false);

        reconciler(Instant.now()).run();

        verify(cdn, never()).refresh(anyString());
        verify(posts).markMediaAclApplied(20L, "PUBLIC");
        verify(posts).markMediaAclApplied(21L, "PUBLIC");
        verify(posts).markMediaAclApplied(22L, "PUBLIC");
        assertThat(messages()).filteredOn(m -> m.startsWith("video_acl_object_missing")).hasSize(3);
    }

    @Test
    void stuckAlertAfterFiveMinutesWithoutUrls() {
        ContentPost p = video(30, true);
        when(posts.findMediaAclPending(any())).thenReturn(List.of(p));
        when(oss.setObjectAcl(anyString(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("x"));

        reconciler(p.getMediaAclDesiredAt().plus(Duration.ofMinutes(4))).run();
        assertThat(messages()).noneMatch(m -> m.startsWith("video_acl_reconcile_stuck"));

        reconciler(p.getMediaAclDesiredAt().plus(Duration.ofMinutes(6))).run();
        assertThat(messages()).anyMatch(m -> m.startsWith("video_acl_reconcile_stuck postId=30 desired=PUBLIC"));
        assertThat(messages()).noneMatch(m -> m.contains("http") || m.contains(".mp4") || m.contains(".jpg"));
    }

    @Test
    void noCredentialsSkipsEverything() {
        when(oss.hasCredentials()).thenReturn(false);
        reconciler(Instant.now()).run();
        assertThat(reconciler(Instant.now()).reconcileOne(1L)).isFalse();
        verifyNoInteractions(posts, cdn);
    }

    @Test
    void reconcileOneIsNoopForConsistentOrImagePosts() {
        ContentPost pending = video(40, false); // desired=PRIVATE=applied
        when(posts.findById(40L)).thenReturn(Optional.of(pending));
        ContentPost img = ContentPost.publish(1L, ContentType.DAILY, null, "t", List.of("a.jpg"));
        when(posts.findById(41L)).thenReturn(Optional.of(img));

        assertThat(reconciler(Instant.now()).reconcileOne(40L)).isTrue();
        assertThat(reconciler(Instant.now()).reconcileOne(41L)).isTrue();
        assertThat(reconciler(Instant.now()).reconcileOne(42L)).isTrue();
        verify(oss, never()).setObjectAcl(anyString(), anyString(), anyBoolean());
    }

    @Test
    void reconcileOneAppliesPendingPost() {
        ContentPost p = video(50, true);
        when(posts.findById(50L)).thenReturn(Optional.of(p));
        assertThat(reconciler(Instant.now()).reconcileOne(50L)).isTrue();
        verify(posts).markMediaAclApplied(50L, "PUBLIC");
    }
}
