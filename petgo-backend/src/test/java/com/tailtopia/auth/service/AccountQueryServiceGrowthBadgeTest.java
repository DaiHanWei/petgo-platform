package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.UserTagView;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 ① Story 3.1 · AC2（L0）：作者投影在 {@code basicViews} 层整批贴等级，两个公开方法都覆盖。 */
class AccountQueryServiceGrowthBadgeTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserTagQueryService tags = mock(UserTagQueryService.class);
    private final GrowthQueryService growth = mock(GrowthQueryService.class);
    private final AccountQueryService service = new AccountQueryService(users, tags, growth);

    private static final GrowthBadge BADGE = new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED);

    private static User user(long id, boolean deleted) {
        User u = User.newGoogleUser("sub" + id, id + "@x.io", "u" + id, null);
        ReflectionTestUtils.setField(u, "id", id);
        if (deleted) {
            u.anonymizeForDeletion(Instant.parse("2026-10-01T00:00:00Z"));
        }
        return u;
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothPublicMethodsAttachGrowthWithOneBatchCallExcludingDeleted() {
        when(users.findAllById(any())).thenReturn(List.of(user(1L, false), user(2L, false), user(3L, true)));
        when(growth.getBadges(anyCollection())).thenReturn(Map.of(1L, BADGE));

        Map<Long, AuthorView> withTags = service.findAuthorViews(List.of(1L, 2L, 3L, 4L));
        Map<Long, AuthorView> withoutTags = service.findAuthorViewsWithoutTags(List.of(1L, 2L, 3L, 4L));

        for (Map<Long, AuthorView> v : List.of(withTags, withoutTags)) {
            assertThat(v.get(1L).growth()).isEqualTo(BADGE);
            assertThat(v.get(2L).growth()).isNull(); // 停用 / 不展示等级：getBadges 不返回
            assertThat(v.get(3L).growth()).isNull(); // 已注销
            assertThat(v.get(4L).growth()).isNull(); // 查无此人 → 匿名
        }
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(growth, times(2)).getBadges(ids.capture()); // 每次调用只 1 次
        for (Collection<Long> c : ids.getAllValues()) {
            assertThat(c).containsExactlyInAnyOrder(1L, 2L);
        }
    }

    @Test
    void growthSurvivesWithTags() {
        when(users.findAllById(any())).thenReturn(List.of(user(1L, false)));
        when(growth.getBadges(anyCollection())).thenReturn(Map.of(1L, BADGE));
        when(tags.findVisibleTags(any(), any())).thenReturn(
                Map.of(1L, List.of(new UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609"))));

        AuthorView v = service.findAuthorViews(List.of(1L)).get(1L);
        assertThat(v.tags()).hasSize(1);
        assertThat(v.growth()).isEqualTo(BADGE);
    }

    @Test
    void allDeletedSkipsGrowthQuery() {
        when(users.findAllById(any())).thenReturn(List.of(user(3L, true)));

        service.findAuthorViews(List.of(3L, 4L));
        verify(growth, never()).getBadges(anyCollection());
    }
}
