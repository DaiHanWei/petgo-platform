package com.tailtopia.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 3.1 · AC2.1 / AC3.4：{@code growth} 组件、5 参兼容构造器、{@code withTags} 带过去、注销防御。 */
class AuthorViewGrowthTest {

    private static final GrowthBadge BADGE = new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED);

    @Test
    void fiveArgConstructorAndAnonymizedHaveNoGrowth() {
        assertThat(new AuthorView(1L, "a", null, false, List.of()).growth()).isNull();
        assertThat(AuthorView.anonymized(1L).growth()).isNull();
    }

    @Test
    void withTagsKeepsGrowth() {
        AuthorView v = new AuthorView(1L, "a", null, false, List.of()).withGrowth(BADGE)
                .withTags(List.of(new UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609")));
        assertThat(v.growth()).isEqualTo(BADGE);
        assertThat(v.tags()).hasSize(1);
    }

    @Test
    void badgeAccessors() {
        AuthorView v = new AuthorView(1L, "a", null, false, List.of()).withGrowth(BADGE);
        assertThat(v.badgeLevel()).isEqualTo(12);
        assertThat(v.badgeTier()).isEqualTo("GUARDIAN");
        assertThat(v.badgeFrame()).isEqualTo("UPGRADED");
        AuthorView noFrame = v.withGrowth(new GrowthBadge(1, GrowthTier.NEWBIE, null));
        assertThat(noFrame.badgeFrame()).isNull();
        AuthorView none = new AuthorView(1L, "a", null, false, List.of());
        assertThat(none.badgeLevel()).isNull();
        assertThat(none.badgeTier()).isNull();
    }

    @Test
    void deletedAuthorNeverExposesGrowthEvenIfAttached() {
        AuthorView deleted = AuthorView.anonymized(1L).withGrowth(BADGE);
        assertThat(deleted.badgeLevel()).isNull();
        assertThat(deleted.badgeTier()).isNull();
        assertThat(deleted.badgeFrame()).isNull();
    }
}
