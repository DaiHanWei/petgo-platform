package com.petgo.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.dto.ContentPostCreateRequest;
import com.petgo.content.dto.ContentPostResponse;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.profile.service.ProfileService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.IdempotencyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：三类发布 + 成长日历绑宠物校验 + 幂等重放（AC1/AC2/AC4 逻辑面）。 */
class ContentServiceTest {

    private ContentPostRepository posts;
    private com.petgo.content.repository.CommentRepository comments;
    private com.petgo.content.repository.ContentLikeRepository likes;
    private ProfileService profileService;
    private IdempotencyService idempotency;
    private ContentService service;

    @BeforeEach
    void setUp() {
        posts = Mockito.mock(ContentPostRepository.class);
        comments = Mockito.mock(com.petgo.content.repository.CommentRepository.class);
        likes = Mockito.mock(com.petgo.content.repository.ContentLikeRepository.class);
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
        service = new ContentService(posts, comments, likes, profileService, idempotency);
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
        com.petgo.content.domain.Comment c1 = newComment(70L);
        com.petgo.content.domain.Comment c2 = newComment(71L);
        when(comments.findByPostIdAndDeletedAtIsNull(6L)).thenReturn(List.of(c1, c2));

        service.softDelete(6L, com.petgo.content.domain.DeleteReason.ADMIN_TAKEDOWN);

        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(c1.getDeletedAt()).isNotNull();
        assertThat(c2.getDeletedAt()).isNotNull();
        verify(likes).deleteByPostId(6L);
    }

    private static com.petgo.content.domain.Comment newComment(long id) {
        try {
            var ctor = com.petgo.content.domain.Comment.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            com.petgo.content.domain.Comment c = ctor.newInstance();
            var f = com.petgo.content.domain.Comment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
