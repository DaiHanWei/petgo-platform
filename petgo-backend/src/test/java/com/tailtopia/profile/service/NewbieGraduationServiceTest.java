package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 2.2 · AC4 —— 7 项全满才毕业；缺一项 / ⑦ 未满 / 非 REAL / 已毕业都不触发。 */
class NewbieGraduationServiceTest {

    private static final long U = 7L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z"); // WIB 11-15

    private UserNewbieMarkDao marks;
    private UserMilestoneFlagRepository flags;
    private AccountQueryService accounts;
    private AccountMilestoneService accountMilestones;
    private NewbieGraduationService service;

    private static final Set<String> SIX = Set.of("FIRST_DIARY", "FIRST_LIKE_RECEIVED", "FIRST_FOLLOW",
            "FIRST_LIKE_GIVEN", "FIRST_COMMENT", "FIRST_REPLY");

    @BeforeEach
    void setUp() {
        marks = mock(UserNewbieMarkDao.class);
        flags = mock(UserMilestoneFlagRepository.class);
        accounts = mock(AccountQueryService.class);
        accountMilestones = mock(AccountMilestoneService.class);
        service = new NewbieGraduationService(marks, flags, accounts, accountMilestones, Clock.fixed(NOW, ZoneOffset.UTC));
        when(marks.keysOf(U)).thenReturn(SIX);
        when(accounts.createdAtOf(U)).thenReturn(Optional.of(Instant.parse("2026-11-07T23:00:00Z"))); // WIB 11-08
        when(accounts.isActiveRegularUser(U)).thenReturn(true);
    }

    @Test
    void 七项齐全触发账号级解锁() {
        assertThat(service.checkAndGraduate(U)).isTrue();
        verify(accountMilestones).onFlagTriggered(U, AccountMilestoneFlag.LULUS_PEMULA);
    }

    @Test
    void 缺任一项不毕业() {
        for (String missing : SIX) {
            Set<String> five = new HashSet<>(SIX);
            five.remove(missing);
            when(marks.keysOf(U)).thenReturn(five);
            assertThat(service.checkAndGraduate(U)).as(missing).isFalse();
        }
        verify(accountMilestones, never()).onFlagTriggered(anyLong(), any());
    }

    @Test
    void 第七项未满不毕业() {
        when(accounts.createdAtOf(U)).thenReturn(Optional.of(Instant.parse("2026-11-09T01:00:00Z"))); // WIB 11-09
        assertThat(service.checkAndGraduate(U)).isFalse();
        verify(accountMilestones, never()).onFlagTriggered(anyLong(), any());
    }

    @Test
    void 非真人普通用户不毕业() {
        when(accounts.isActiveRegularUser(U)).thenReturn(false);
        assertThat(service.checkAndGraduate(U)).isFalse();
        verify(accountMilestones, never()).onFlagTriggered(anyLong(), any());
    }

    @Test
    void 已毕业直接返回不再读标记() {
        when(flags.existsByUserIdAndFlagKey(U, "LULUS_PEMULA")).thenReturn(true);
        assertThat(service.checkAndGraduate(U)).isFalse();
        verify(marks, never()).keysOf(anyLong());
        verify(accountMilestones, never()).onFlagTriggered(anyLong(), any());
    }

    @Test
    void 作为标记扩展点调入() {
        service.afterMarked(U, NewbieTask.FIRST_REPLY);
        verify(accountMilestones).onFlagTriggered(U, AccountMilestoneFlag.LULUS_PEMULA);
    }

    @Test
    void 通知落点兜底_无ACTIVE宠物取最早一只的编码_再没有用猫编码() {
        PetProfile otherRo = PetProfile.create(U, PetType.OTHER, "A", null, null, null, null, "T1");
        assertThat(AccountMilestoneService.targetCodeOf(AccountMilestoneFlag.LULUS_PEMULA, List.of(), List.of(otherRo)))
                .isEqualTo("G-S9");
        assertThat(AccountMilestoneService.targetCodeOf(AccountMilestoneFlag.LULUS_PEMULA, List.of(), List.of()))
                .isEqualTo("C-S16");
        // 另两个 flag 无 ACTIVE 宠物维持 null（通知侧不发）。
        assertThat(AccountMilestoneService.targetCodeOf(AccountMilestoneFlag.FIRST_FOLLOW_USER, List.of(), List.of(otherRo)))
                .isNull();
        PetProfile dog = PetProfile.create(U, PetType.DOG, "B", null, null, null, null, "T2");
        assertThat(AccountMilestoneService.targetCodeOf(AccountMilestoneFlag.LULUS_PEMULA, List.of(dog), List.of(otherRo, dog)))
                .isEqualTo("D-S16");
    }
}
