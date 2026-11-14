package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.UserMilestoneFlag;
import com.tailtopia.profile.dto.AccountMilestoneItemResponse;
import com.tailtopia.profile.dto.AccountMilestoneListResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import com.tailtopia.profile.support.PetFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.5.0 onboarding-recall Story 2.3 · AC1 —— 账号级里程碑接口。 */
class AccountMilestoneQueryServiceTest {

    private static final Instant T = Instant.parse("2026-11-15T03:00:00Z");

    private UserMilestoneFlagRepository flags;
    private PetProfileRepository profiles;
    private AccountMilestoneQueryService service;

    @BeforeEach
    void setUp() {
        flags = mock(UserMilestoneFlagRepository.class);
        profiles = mock(PetProfileRepository.class);
        service = new AccountMilestoneQueryService(flags, profiles);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of());
        when(flags.findAllByUserId(7L)).thenReturn(List.of());
    }

    private static UserMilestoneFlag flag(String key, Instant celebratedAt) {
        UserMilestoneFlag f = org.springframework.beans.BeanUtils.instantiateClass(UserMilestoneFlag.class);
        ReflectionTestUtils.setField(f, "userId", 7L);
        ReflectionTestUtils.setField(f, "flagKey", key);
        ReflectionTestUtils.setField(f, "occurredAt", T);
        ReflectionTestUtils.setField(f, "celebratedAt", celebratedAt);
        return f;
    }

    @Test
    void 无宠物取猫编码且固定顺序() {
        AccountMilestoneListResponse r = service.list(7L);
        assertThat(r.items()).extracting(AccountMilestoneItemResponse::flagKey)
                .containsExactly("FIRST_FOLLOW_USER", "FIRST_COLLECT_CONTENT", "LULUS_PEMULA");
        assertThat(r.items()).extracting(AccountMilestoneItemResponse::code).containsExactly("C-S19", "C-S20", "C-S16");
        assertThat(r.completedCount()).isZero();
        assertThat(r.totalCount()).isEqualTo(3);
    }

    @Test
    void 多宠取默认宠物物种_最早ACTIVE() {
        PetProfile ro = PetFixtures.pet(7L, 1L, PetType.CAT, T.minusSeconds(100));
        ReflectionTestUtils.setField(ro, "status", PetStatus.READ_ONLY);
        PetProfile other = PetFixtures.pet(7L, 2L, PetType.OTHER, T);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of(ro, other));
        assertThat(service.list(7L).items()).extracting(AccountMilestoneItemResponse::code)
                .containsExactly("G-S12", "G-S13", "G-S9");
    }

    @Test
    void 待补弹只对Lulus且flag未庆祝() {
        when(flags.findAllByUserId(7L)).thenReturn(List.of(flag("FIRST_FOLLOW_USER", null), flag("LULUS_PEMULA", null)));
        AccountMilestoneListResponse r = service.list(7L);
        assertThat(r.completedCount()).isEqualTo(2);
        assertThat(r.items().get(0).completed()).isTrue();
        assertThat(r.items().get(0).completedAt()).isEqualTo(T);
        assertThat(r.items().get(0).pendingCelebration()).isFalse();
        assertThat(r.items().get(1).completed()).isFalse();
        assertThat(r.items().get(1).completedAt()).isNull();
        assertThat(r.items().get(2).pendingCelebration()).isTrue();

        when(flags.findAllByUserId(7L)).thenReturn(List.of(flag("LULUS_PEMULA", T)));
        assertThat(service.list(7L).items().get(2).pendingCelebration()).isFalse();
    }
}
