package com.tailtopia.ops.audience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.profile.domain.PetType;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：开屏弹窗人群条件 JSON（{@code ops_popups.audience_filter}，V1.4.1）。 */
class AudienceFiltersTest {

    @Test
    void emptyIsNullColumnAndParsesBackToNone() {
        assertThat(AudienceFilters.none().isEmpty()).isTrue();
        assertThat(AudienceFilters.none().toJsonOrNull()).isNull();
        assertThat(new AudienceFilters(Set.of(), Set.of(), Set.of(), null).isEmpty()).isTrue();
        assertThat(AudienceFilters.parse(null)).isEqualTo(AudienceFilters.none());
        assertThat(AudienceFilters.parse("  ")).isEqualTo(AudienceFilters.none());
    }

    @Test
    void roundTripSortedByEnumOrder() {
        AudienceFilters f = new AudienceFilters(Set.of(GrowthTier.STAR, GrowthTier.NEWBIE), Set.of(PetType.DOG),
                Set.of(ActivityBand.OTHER, ActivityBand.WITHIN_7D), true);
        String json = f.toJsonOrNull();
        assertThat(json).isEqualTo("{\"levelTiers\":[\"NEWBIE\",\"STAR\"],\"species\":[\"DOG\"],"
                + "\"activity\":[\"WITHIN_7D\",\"OTHER\"],\"paid\":true}");
        assertThat(AudienceFilters.parse(json)).isEqualTo(f);
    }

    @Test
    void invalidRejected() {
        assertThatThrownBy(() -> AudienceFilters.parse("{\"vip\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AudienceFilters.parse("{\"species\":[\"FISH\"]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AudienceFilters.parse("{\"paid\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AudienceFilters.parse("[]")).isInstanceOf(IllegalArgumentException.class);
    }
}
