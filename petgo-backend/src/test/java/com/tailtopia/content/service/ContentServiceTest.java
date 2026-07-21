package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：三类发布 + 成长日历绑宠物校验 + 幂等重放（AC1/AC2/AC4 逻辑面）。 */
class ContentServiceTest {

    private ContentPostRepository posts;
    private com.tailtopia.content.repository.CommentRepository comments;
    private com.tailtopia.content.repository.ContentLikeRepository likes;
    private ProfileService profileService;
    private IdempotencyService idempotency;
    private org.springframework.context.ApplicationEventPublisher events;
    private ManualReviewGate manualReviewGate;
    private ContentService service;

    @BeforeEach
    void setUp() {
        posts = Mockito.mock(ContentPostRepository.class);
        comments = Mockito.mock(com.tailtopia.content.repository.CommentRepository.class);
        likes = Mockito.mock(com.tailtopia.content.repository.ContentLikeRepository.class);
        profileService = Mockito.mock(ProfileService.class);
        idempotency = Mockito.mock(IdempotencyService.class);
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(posts.save(any(ContentPost.class))).thenAnswer(inv -> {
            ContentPost p = inv.getArgument(0);
            if (p.getId() == null) {
                setId(p, 100L); // 模拟 DB IDENTITY 回填（仅新建）
            }
            return p;
        });
        events = Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        manualReviewGate = Mockito.mock(ManualReviewGate.class);
        // 默认开关关（现网行为）；按需在用例内 stub enabled()=true。
        when(manualReviewGate.enabled()).thenReturn(false);
        // 审核用真实 stub：既有用例文本均良性 → PASS；R2 用例显式触发拦截标记。
        service = new ContentService(posts, comments, likes, profileService, idempotency,
                new ContentModerationService(), events, manualReviewGate);
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

    @Test
    void dailyPublishIgnoresPetId() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, 99L, "hi", List.of("u1")), null);
        assertThat(resp.type()).isEqualTo(ContentType.DAILY);
        assertThat(resp.petId()).isNull(); // 普通类型不绑宠物
        assertThat(resp.imageUrls()).containsExactly("u1");
    }

    @Test
    void growthMomentRequiresOwnedPet() {
        when(profileService.ownsPet(1L, 5L)).thenReturn(true);
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "moment", null), null);
        assertThat(resp.type()).isEqualTo(ContentType.GROWTH_MOMENT);
        assertThat(resp.petId()).isEqualTo(5L);
    }

    @Test
    void growthMomentWithoutPetIdRejected() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, null, "x", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void growthMomentWithUnownedPetRejected() {
        when(profileService.ownsPet(1L, 7L)).thenReturn(false);
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 7L, "x", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void idempotentReplayReturnsExistingWithoutCreating() {
        ContentPost existing = ContentPost.publish(1L, ContentType.DAILY, null, "old", null);
        when(idempotency.findResourceId("KEY1")).thenReturn(Optional.of(42L));
        when(posts.findById(42L)).thenReturn(Optional.of(existing));

        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "new", null), "KEY1");

        assertThat(resp.text()).isEqualTo("old"); // 取回既有，不创建
        verify(posts, never()).save(any());
    }

    @Test
    void storesIdempotencyKeyAfterCreate() {
        service.publish(1L, new ContentPostCreateRequest(ContentType.DAILY, null, "x", null), "KEY2");
        verify(idempotency).store(org.mockito.ArgumentMatchers.eq("KEY2"), anyLong());
    }

    // ===== R2 · AC6 最低内容 =====

    @Test
    void bothEmptyContentRejected() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "  ", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void imageOnlyContentAllowed() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, null, List.of("u1")), null);
        assertThat(resp.imageUrls()).containsExactly("u1");
        assertThat(resp.text()).isNull();
    }

    // ===== R2 · AC5 成长日历事件日期（F9） =====

    @Test
    void growthMomentStoresGivenEventDate() {
        when(profileService.ownsPet(1L, 5L)).thenReturn(true);
        LocalDate past = LocalDate.of(2020, 1, 2);
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null, past), null);
        assertThat(resp.eventDate()).isEqualTo(past);
    }

    @Test
    void growthMomentDefaultsEventDateToToday() {
        when(profileService.ownsPet(1L, 5L)).thenReturn(true);
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null, null), null);
        // 缺省事件日期按印尼业务时区 WIB 判定（Asia/Jakarta），勿用 UTC（prod 事故 2026-07-20）。
        assertThat(resp.eventDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")));
    }

    @Test
    void growthMomentFutureEventDateRejected() {
        when(profileService.ownsPet(1L, 5L)).thenReturn(true);
        // WIB 今天 +1 天：无论服务器 UTC 处于一天中哪个时刻，都严格晚于 WIB 今天。
        LocalDate future = LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).plusDays(1);
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null, future), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void nonGrowthIgnoresEventDate() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null,
                        LocalDate.of(2020, 1, 2)), null);
        assertThat(resp.eventDate()).isNull();
    }

    // ===== R2 · AC8 发布时三方自动审核（F10） =====

    @Test
    void textKeywordBlockedNotSaved() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void imageViolationBlockedNotSaved() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ok",
                        List.of("https://cdn.petgo.test/moderation-blocked-1.jpg")), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void cleanContentPasses() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "cuaca cerah hari ini", List.of("u1")), null);
        assertThat(resp.id()).isNotNull();
    }

    // ===== Story 4.3：人工审核开关分支 =====

    @Test
    void blockedContentEnqueuedAsUnderReviewWhenManualReviewEnabled() {
        when(manualReviewGate.enabled()).thenReturn(true);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null), null);

        verify(posts).save(captor.capture()); // 落库（未过审 → 挂起，不再 throw）
        assertThat(captor.getValue().getStatus())
                .isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        verify(manualReviewGate).enqueue(anyLong()); // 入队挂起
        verify(events, never()).publishEvent(any( // 不进 Feed
                com.tailtopia.content.event.ContentPublishedEvent.class));
        assertThat(resp.id()).isNotNull();
    }

    @Test
    void blockedContentStillThrowsWhenManualReviewDisabled() {
        // 默认 enabled()=false（现网行为）：拦截即 throw、不落库、不入队。
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
        verify(manualReviewGate, never()).enqueue(anyLong());
    }

    // ===== Story 3.6 删除 =====

    private ContentPost ownedPost(long id, long authorId) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, "x", null);
        setId(p, id);
        return p;
    }

    @Test
    void authorCanSoftDeleteOwnPostAndCascade() {
        ContentPost p = ownedPost(5L, 1L);
        when(posts.findById(5L)).thenReturn(Optional.of(p));
        when(comments.findByPostIdAndDeletedAtIsNull(5L)).thenReturn(List.of());

        service.deleteByAuthor(5L, 1L);

        assertThat(p.getDeletedAt()).isNotNull(); // 软删置位
        verify(posts).save(p);
        verify(likes).deleteByPostId(5L); // 点赞物理清
    }

    @Test
    void nonAuthorCannotDelete() {
        when(posts.findById(5L)).thenReturn(Optional.of(ownedPost(5L, 1L)));
        assertThatThrownBy(() -> service.deleteByAuthor(5L, 999L)).isInstanceOf(AppException.class);
        verify(likes, never()).deleteByPostId(anyLong());
    }

    @Test
    void deleteMissingOrAlreadyDeletedIsIdempotent() {
        when(posts.findById(404L)).thenReturn(Optional.empty());
        service.deleteByAuthor(404L, 1L); // 不抛异常
        verify(posts, never()).save(any());
    }

    @Test
    void softDeleteReusableForTakedownCascadesComments() {
        ContentPost p = ownedPost(6L, 1L);
        when(posts.findById(6L)).thenReturn(Optional.of(p));
        com.tailtopia.content.domain.Comment c1 = newComment(70L);
        com.tailtopia.content.domain.Comment c2 = newComment(71L);
        when(comments.findByPostIdAndDeletedAtIsNull(6L)).thenReturn(List.of(c1, c2));

        service.softDelete(6L, com.tailtopia.content.domain.DeleteReason.ADMIN_TAKEDOWN);

        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(c1.getDeletedAt()).isNotNull();
        assertThat(c2.getDeletedAt()).isNotNull();
        verify(likes).deleteByPostId(6L);
    }

    // ===== R2 · AC3 运营下架 → 通知作者领域事件 =====

    @Test
    void adminTakedownPublishesContentRemovedEvent() {
        ContentPost p = ownedPost(8L, 42L);
        when(posts.findById(8L)).thenReturn(Optional.of(p));
        when(comments.findByPostIdAndDeletedAtIsNull(8L)).thenReturn(List.of());

        service.softDelete(8L, com.tailtopia.content.domain.DeleteReason.ADMIN_TAKEDOWN);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.tailtopia.content.event.ContentRemovedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().postId()).isEqualTo(8L);
        assertThat(captor.getValue().authorId()).isEqualTo(42L); // 推送目标=作者
    }

    @Test
    void authorDeleteDoesNotPublishRemovedEvent() {
        ContentPost p = ownedPost(9L, 1L);
        when(posts.findById(9L)).thenReturn(Optional.of(p));
        when(comments.findByPostIdAndDeletedAtIsNull(9L)).thenReturn(List.of());

        service.deleteByAuthor(9L, 1L); // 作者自删不自通知

        verify(events, never()).publishEvent(any(
                com.tailtopia.content.event.ContentRemovedEvent.class));
    }

    // ===== Story 4.2：运营恢复已下架内容 =====

    @Test
    void restoreClearsDeletedAtAndSaves() {
        ContentPost p = ownedPost(11L, 1L);
        p.softDelete(); // 先置为已下架
        assertThat(p.getDeletedAt()).isNotNull();
        when(posts.findById(11L)).thenReturn(Optional.of(p));

        service.restore(11L);

        assertThat(p.getDeletedAt()).isNull(); // 重回公开口径
        verify(posts).save(p);
    }

    @Test
    void restoreIsNoOpWhenNotDeleted() {
        ContentPost p = ownedPost(12L, 1L); // 未删
        when(posts.findById(12L)).thenReturn(Optional.of(p));

        service.restore(12L); // 幂等：未删不写库

        assertThat(p.getDeletedAt()).isNull();
        verify(posts, never()).save(any(ContentPost.class));
    }

    private static com.tailtopia.content.domain.Comment newComment(long id) {
        try {
            var ctor = com.tailtopia.content.domain.Comment.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            com.tailtopia.content.domain.Comment c = ctor.newInstance();
            var f = com.tailtopia.content.domain.Comment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
