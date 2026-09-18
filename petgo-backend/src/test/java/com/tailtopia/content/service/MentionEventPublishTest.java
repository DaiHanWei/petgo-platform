package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.event.ContentMentionedEvent;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.mention.repository.MentionCandidateRepository;
import com.tailtopia.mention.service.MentionSanitizer;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0：@ 事件的**发布时机**（V1.3.0 batch-b1 Story 3.4）。
 *
 * <h2>🔴 发布时机 = 那条内容变成别人看得见的那一刻，不是提交的那一刻</h2>
 * 这一条在 story AC 里没有独立编号，但它决定了被 @ 的人点进通知会看到什么：
 * 提交那一刻就发 → 审核挂起的帖让人点进去是 404。
 *
 * <h2>🔴 非 PUBLIC 不发（Story 3.2 留下的硬约束）</h2>
 * PRIVATE Diary 照样落 @ 名单（作者自视那条时间线要能高亮能点 —— Story 3.3），
 * 但可见范围创建后不可更改（FR-83 AC7），被 @ 的人永远打不开它。
 */
class MentionEventPublishTest {

    private static final long AUTHOR = 1L;
    private static final long MENTIONED = 42L;

    private ContentPostRepository posts;
    private ApplicationEventPublisher events;
    private ManualReviewGate manualReviewGate;
    private MentionCandidateRepository candidates;
    private AccountQueryService accounts;
    private ContentService service;

    @BeforeEach
    void setUp() {
        posts = Mockito.mock(ContentPostRepository.class);
        events = Mockito.mock(ApplicationEventPublisher.class);
        manualReviewGate = Mockito.mock(ManualReviewGate.class);
        candidates = Mockito.mock(MentionCandidateRepository.class);
        accounts = Mockito.mock(AccountQueryService.class);
        UserHideRelationReader hideRelations = Mockito.mock(UserHideRelationReader.class);
        IdempotencyService idempotency = Mockito.mock(IdempotencyService.class);

        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(manualReviewGate.enabled()).thenReturn(false);
        when(posts.save(any(ContentPost.class))).thenAnswer(inv -> {
            ContentPost p = inv.getArgument(0);
            if (p.getId() == null) {
                setId(p, 100L);
            }
            return p;
        });
        // @ 名单洗完仍然留得下 MENTIONED（他在候选集里、没注销、没拉黑关系）。
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection()))
                .thenAnswer(inv -> List.copyOf(inv.<java.util.Collection<Long>>getArgument(1)));
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());
        when(accounts.findAuthorViewsWithoutTags(anyCollection())).thenAnswer(inv -> {
            Map<Long, AuthorView> out = new HashMap<>();
            for (Long id : inv.<java.util.Collection<Long>>getArgument(0)) {
                out.put(id, new AuthorView(id, "u" + id, null, false, List.of()));
            }
            return out;
        });

        service = new ContentService(posts,
                Mockito.mock(CommentRepository.class),
                Mockito.mock(ContentLikeRepository.class),
                Mockito.mock(ProfileService.class),
                idempotency,
                new ContentModerationService(),
                events, manualReviewGate,
                new ImageSizeResolver(),
                Mockito.mock(ImageSizeBackfillService.class),
                Mockito.mock(ContentPinService.class),
                new MentionSanitizer(accounts, hideRelations, candidates));
    }

    private static void setId(ContentPost p, long id) {
        try {
            var f = ContentPost.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private ContentPostCreateRequest req(String text, ContentVisibility visibility,
            List<Long> mentioned) {
        return new ContentPostCreateRequest(ContentType.DAILY, null, text, null, null, visibility,
                null, mentioned);
    }

    private List<ContentMentionedEvent> mentionEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(ContentMentionedEvent.class::isInstance)
                .map(ContentMentionedEvent.class::cast)
                .toList();
    }

    @Test
    void 公开发布且有at名单时发一条at事件() {
        service.publish(AUTHOR, req("hai @u42", ContentVisibility.PUBLIC, List.of(MENTIONED)), null);
        assertThat(mentionEvents()).singleElement().satisfies(e -> {
            assertThat(e.postId()).isEqualTo(100L);
            assertThat(e.commentId()).isNull(); // 正文提及
            assertThat(e.isComment()).isFalse();
            assertThat(e.actorId()).isEqualTo(AUTHOR);
            assertThat(e.contentAuthorId()).isEqualTo(AUTHOR);
            assertThat(e.mentionedUserIds()).containsExactly(MENTIONED);
        });
    }

    @Test
    void 没有at名单时不发at事件() {
        service.publish(AUTHOR, req("普通正文", ContentVisibility.PUBLIC, null), null);
        assertThat(mentionEvents()).isEmpty();
    }

    @Test
    void 私密内容不发at事件() {
        // PRIVATE Diary 照样落名单（Story 3.3 要高亮），但被 @ 的人永远打不开它 ——
        // 通知发出去就是一条点进去是空态的骚扰。
        service.publish(AUTHOR, req("hai @u42", ContentVisibility.PRIVATE, List.of(MENTIONED)), null);
        assertThat(mentionEvents()).isEmpty();
    }

    @Test
    void 私密内容仍然把at名单落了库() {
        // 与上一条成对：不发通知 ≠ 不落库。落库是 Story 3.3 高亮的前提。
        service.publish(AUTHOR, req("hai @u42", ContentVisibility.PRIVATE, List.of(MENTIONED)), null);
        ArgumentCaptor<ContentPost> saved = ArgumentCaptor.forClass(ContentPost.class);
        verify(posts).save(saved.capture());
        assertThat(saved.getValue().getMentionedUserIds()).containsExactly(MENTIONED);
    }

    @Test
    void 审核挂起时不发at事件() {
        // 🔴 提交那一刻就发的话，被 @ 的人点进去是 404（帖子还在挂起态，他看不到）。
        when(manualReviewGate.enabled()).thenReturn(true);
        service.publish(AUTHOR,
                req("stub-high borderline text @u42", ContentVisibility.PUBLIC, List.of(MENTIONED)),
                null);
        ArgumentCaptor<ContentPost> saved = ArgumentCaptor.forClass(ContentPost.class);
        verify(posts).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PostStatus.UNDER_REVIEW);
        assertThat(saved.getValue().getMentionedUserIds()).containsExactly(MENTIONED); // 名单照样落
        assertThat(mentionEvents()).isEmpty();                                        // 但不发通知
    }

    @Test
    void 挂起帖过审那一刻才发at事件() {
        ContentPost pending = ContentPost.pendingReview(AUTHOR, ContentType.DAILY, null,
                "hai @u42", null, null, null, "RISK_HIGH");
        pending.setVisibility(ContentVisibility.PUBLIC);
        pending.setMentionedUserIds(List.of(MENTIONED));
        setId(pending, 100L);
        when(posts.findById(100L)).thenReturn(Optional.of(pending));

        service.approveReview(100L);

        assertThat(mentionEvents()).singleElement()
                .satisfies(e -> assertThat(e.mentionedUserIds()).containsExactly(MENTIONED));
    }

    @Test
    void 挂起的私密帖过审也不发at事件() {
        ContentPost pending = ContentPost.pendingReview(AUTHOR, ContentType.DAILY, null,
                "hai @u42", null, null, null, "RISK_HIGH");
        pending.setVisibility(ContentVisibility.PRIVATE);
        pending.setMentionedUserIds(List.of(MENTIONED));
        setId(pending, 100L);
        when(posts.findById(100L)).thenReturn(Optional.of(pending));

        service.approveReview(100L);

        assertThat(mentionEvents()).isEmpty();
    }

    @Test
    void 运营免审发布刻意不带at名单() {
        // @ 会给人发通知，由运营账号批量发出去就是骚扰；那条路径本就免审（Story 3.2 的口径）。
        service.publishTrusted(AUTHOR, req("hai @u42", ContentVisibility.PUBLIC, List.of(MENTIONED)),
                "k1");
        ArgumentCaptor<ContentPost> saved = ArgumentCaptor.forClass(ContentPost.class);
        verify(posts).save(saved.capture());
        assertThat(saved.getValue().getMentionedUserIds()).isEmpty();
        assertThat(mentionEvents()).isEmpty();
        verify(events, never()).publishEvent(any(ContentMentionedEvent.class));
    }
}
