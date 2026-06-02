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
    private ProfileService profileService;
    private IdempotencyService idempotency;
    private ContentService service;

    @BeforeEach
    void setUp() {
        posts = Mockito.mock(ContentPostRepository.class);
        profileService = Mockito.mock(ProfileService.class);
        idempotency = Mockito.mock(IdempotencyService.class);
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(posts.save(any(ContentPost.class))).thenAnswer(inv -> {
            ContentPost p = inv.getArgument(0);
            setId(p, 100L); // 模拟 DB IDENTITY 回填
            return p;
        });
        service = new ContentService(posts, profileService, idempotency);
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
}
