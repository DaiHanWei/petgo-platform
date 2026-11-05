package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.event.AccountMilestoneCompletedEvent;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import com.tailtopia.profile.slot.PetSlotLock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.4.2 Story 2.1 · D-12 / G-3）：账号级里程碑触发与补写。 */
class AccountMilestoneServiceTest {

    private static final long OWNER = 1L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetMilestoneRepository milestones = mock(PetMilestoneRepository.class);
    private final MilestoneCompletionRepository completions = mock(MilestoneCompletionRepository.class);
    private final UserMilestoneFlagRepository flags = mock(UserMilestoneFlagRepository.class);
    private final PetSlotLock lock = mock(PetSlotLock.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
    private final AccountMilestoneService service = new AccountMilestoneService(profiles, milestones, completions,
            flags, lock, events, accounts, deletions, Clock.fixed(NOW, ZoneOffset.UTC));

    /** (petId, code) → roster 行 id。 */
    private final Map<String, Long> rosterIds = new HashMap<>();
    private final List<MilestoneCompletion> saved = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        when(accounts.isActiveAccount(anyLong())).thenReturn(true);
        when(deletions.findByUserId(anyLong())).thenReturn(Optional.empty());
        when(milestones.findByPetProfileIdAndCode(anyLong(), anyString())).thenAnswer(inv -> {
            Long id = rosterIds.get(inv.getArgument(0) + "/" + inv.getArgument(1));
            if (id == null) {
                return Optional.empty();
            }
            PetMilestone m = PetMilestone.of(inv.getArgument(0), MilestoneCatalog.byCode(inv.getArgument(1)));
            ReflectionTestUtils.setField(m, "id", id);
            return Optional.of(m);
        });
        when(completions.save(any(MilestoneCompletion.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private PetProfile rostered(long id, PetType type, int minutes) {
        PetProfile p = pet(OWNER, id, type, T0.plusSeconds(minutes * 60L));
        for (AccountMilestoneFlag f : AccountMilestoneFlag.values()) {
            rosterIds.put(id + "/" + f.codeOf(type), nextId++);
        }
        return p;
    }

    private void readOnly(PetProfile p) {
        ReflectionTestUtils.setField(p, "status", com.tailtopia.profile.domain.PetStatus.READ_ONLY);
    }

    private AccountMilestoneCompletedEvent publishedEvent() {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(cap.capture());
        return (AccountMilestoneCompletedEvent) cap.getValue();
    }

    // ===== onFlagTriggered =====

    @Test
    void flagConflictIsNoOp() {
        when(flags.insertIfAbsent(OWNER, "FIRST_FOLLOW_USER")).thenReturn(0);

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);

        verify(lock).lockUser(OWNER);
        verifyNoInteractions(profiles, completions, events);
    }

    /** 复审 2-1 #2：注销已受理（account_deletions 有行，含 PENDING）后才跑到的 @Async 触发 → 不写回 flag。 */
    @Test
    void deletionRequestedSkipsTrigger() {
        when(deletions.findByUserId(OWNER)).thenReturn(Optional.of(AccountDeletion.request(OWNER)));

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);

        verify(flags, never()).insertIfAbsent(anyLong(), anyString());
        verifyNoInteractions(profiles, completions, events);
    }

    /** 复审 2-1 #2：已注销（deleted_at 已落）/ 已停用 → isActiveAccount 为假 → 不写 flag。 */
    @Test
    void deletedOrDeactivatedAccountSkipsTrigger() {
        when(accounts.isActiveAccount(OWNER)).thenReturn(false);

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_COLLECT_CONTENT);

        verify(flags, never()).insertIfAbsent(anyLong(), anyString());
        verifyNoInteractions(profiles, completions, events);
    }

    /** 复审 2-1 #2：注销判定在账号锁之内（与 deleteByUserId 串行）。 */
    @Test
    void deletionCheckHappensUnderTheAccountLock() {
        when(flags.insertIfAbsent(OWNER, "FIRST_FOLLOW_USER")).thenReturn(0);
        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);
        InOrder order = inOrder(lock, deletions, flags);
        order.verify(lock).lockUser(OWNER);
        order.verify(deletions).findByUserId(OWNER);
        order.verify(flags).insertIfAbsent(OWNER, "FIRST_FOLLOW_USER");
    }

    @Test
    void locksBeforeInsertingFlag() {
        when(flags.insertIfAbsent(OWNER, "FIRST_FOLLOW_USER")).thenReturn(0);
        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);
        InOrder order = inOrder(lock, flags);
        order.verify(lock).lockUser(OWNER);
        order.verify(flags).insertIfAbsent(OWNER, "FIRST_FOLLOW_USER");
    }

    /** AC2：2 ACTIVE + 1 READ_ONLY → 两只各 1 条完成（未庆祝）、READ_ONLY 不写；只发 1 个账号事件。 */
    @Test
    void twoActiveOneReadOnly() {
        PetProfile a = rostered(10L, PetType.CAT, 0);
        PetProfile b = rostered(11L, PetType.CAT, 1);
        PetProfile c = rostered(12L, PetType.CAT, 2);
        readOnly(c);
        when(flags.insertIfAbsent(OWNER, "FIRST_FOLLOW_USER")).thenReturn(1);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b, c));

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);

        assertThat(saved).extracting(MilestoneCompletion::getPetMilestoneId)
                .containsExactly(rosterIds.get("10/C-S19"), rosterIds.get("11/C-S19"));
        assertThat(saved).allSatisfy(m -> {
            assertThat(m.getCelebratedAt()).as("进补庆祝").isNull();
            assertThat(m.getSource()).isEqualTo(MilestoneCompletionSource.SYSTEM_AUTO);
        });
        AccountMilestoneCompletedEvent e = publishedEvent();
        assertThat(e.userId()).isEqualTo(OWNER);
        assertThat(e.flagKey()).isEqualTo("FIRST_FOLLOW_USER");
        assertThat(e.petIds()).containsExactly(10L, 11L);
        assertThat(e.codes()).containsExactly("C-S19", "C-S19");
        assertThat(e.targetCode()).isEqualTo("C-S19");
    }

    /** AC5.1：0 只宠物 → 写 flag、不写完成，事件照发（成长值要用），petIds 空、targetCode null。 */
    @Test
    void noPetsStillPublishesForGrowth() {
        when(flags.insertIfAbsent(OWNER, "FIRST_COLLECT_CONTENT")).thenReturn(1);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of());

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_COLLECT_CONTENT);

        assertThat(saved).isEmpty();
        AccountMilestoneCompletedEvent e = publishedEvent();
        assertThat(e.petIds()).isEmpty();
        assertThat(e.codes()).isEmpty();
        assertThat(e.targetCode()).isNull();
    }

    /** AC5.1：全部 READ_ONLY 同「没有 ACTIVE 宠物」。 */
    @Test
    void allReadOnlyBehavesLikeNoPets() {
        PetProfile a = rostered(10L, PetType.DOG, 0);
        readOnly(a);
        when(flags.insertIfAbsent(OWNER, "FIRST_FOLLOW_USER")).thenReturn(1);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a));

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);

        assertThat(saved).isEmpty();
        assertThat(publishedEvent().targetCode()).isNull();
    }

    /** AC7 / R-4：混养（只读的狗在最前、猫、其他）→ 各完成本物种编码；targetCode = 第一只 ACTIVE（猫）。 */
    @Test
    void mixedSpeciesTargetIsFirstActivePet() {
        PetProfile dogRo = rostered(9L, PetType.DOG, 0);
        readOnly(dogRo);
        PetProfile cat = rostered(10L, PetType.CAT, 1);
        PetProfile other = rostered(11L, PetType.OTHER, 2);
        when(flags.insertIfAbsent(OWNER, "FIRST_FOLLOW_USER")).thenReturn(1);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(dogRo, cat, other));

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_FOLLOW_USER);

        AccountMilestoneCompletedEvent e = publishedEvent();
        assertThat(e.petIds()).containsExactly(10L, 11L);
        assertThat(e.codes()).containsExactly("C-S19", "G-S12");
        assertThat(e.targetCode()).isEqualTo("C-S19");
    }

    /** roster 行缺失（极老无 roster 宠物）→ 跳过，交惰性物化钩子；已有完成行 → 不重写。 */
    @Test
    void missingRosterRowAndExistingCompletionAreSkipped() {
        PetProfile noRoster = pet(OWNER, 10L, PetType.CAT, T0);
        PetProfile done = rostered(11L, PetType.DOG, 1);
        when(completions.existsByPetMilestoneId(rosterIds.get("11/D-S20"))).thenReturn(true);
        when(flags.insertIfAbsent(OWNER, "FIRST_COLLECT_CONTENT")).thenReturn(1);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(noRoster, done));

        service.onFlagTriggered(OWNER, AccountMilestoneFlag.FIRST_COLLECT_CONTENT);

        assertThat(saved).isEmpty();
        AccountMilestoneCompletedEvent e = publishedEvent();
        assertThat(e.petIds()).as("通知侧按 petIds 判空不发").isEmpty();
        assertThat(e.targetCode()).isEqualTo("C-S20");
    }

    // ===== applyExistingFlagsToPet =====

    /** AC5.2：账号已有两个 flag → 新宠物两项直接「已完成且已庆祝」（completed_at = celebrated_at），不发事件。 */
    @Test
    void applyWritesCelebratedCompletionsWithoutEvents() {
        PetProfile p = rostered(10L, PetType.OTHER, 0);
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of("FIRST_FOLLOW_USER", "FIRST_COLLECT_CONTENT"));

        service.applyExistingFlagsToPet(p);

        assertThat(saved).extracting(MilestoneCompletion::getPetMilestoneId)
                .containsExactlyInAnyOrder(rosterIds.get("10/G-S12"), rosterIds.get("10/G-S13"));
        assertThat(saved).allSatisfy(m -> {
            assertThat(m.getCelebratedAt()).isEqualTo(NOW);
            assertThat(m.getCompletedAt()).isEqualTo(NOW);
            assertThat(m.isUncelebrated()).isFalse();
        });
        verifyNoInteractions(events);
    }

    /** 复审 2-1 #1：补写先取账号锁、再读 flag（与首次触发串行，防并发漏写）。 */
    @Test
    void applyLocksOwnerBeforeReadingFlags() {
        PetProfile p = rostered(10L, PetType.CAT, 0);
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of("FIRST_FOLLOW_USER"));

        service.applyExistingFlagsToPet(p);

        InOrder order = inOrder(lock, flags);
        order.verify(lock).lockUser(OWNER);
        order.verify(flags).findFlagKeysByUserId(OWNER);
    }

    @Test
    void applyOnlyWritesFlagsTheAccountHas() {
        PetProfile p = rostered(10L, PetType.CAT, 0);
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of("FIRST_COLLECT_CONTENT"));

        service.applyExistingFlagsToPet(p);

        assertThat(saved).extracting(MilestoneCompletion::getPetMilestoneId)
                .containsExactly(rosterIds.get("10/C-S20"));
    }

    @Test
    void applySkipsReadOnlyPet() {
        PetProfile p = rostered(10L, PetType.CAT, 0);
        readOnly(p);
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of("FIRST_FOLLOW_USER"));

        service.applyExistingFlagsToPet(p);

        assertThat(saved).isEmpty();
        verify(flags, never()).findFlagKeysByUserId(anyLong());
    }

    @Test
    void applyDoesNotRewriteExistingCompletion() {
        PetProfile p = rostered(10L, PetType.CAT, 0);
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of("FIRST_FOLLOW_USER"));
        when(completions.existsByPetMilestoneId(rosterIds.get("10/C-S19"))).thenReturn(true);

        service.applyExistingFlagsToPet(p);

        assertThat(saved).isEmpty();
    }

    @Test
    void applyWithoutFlagsDoesNothing() {
        PetProfile p = rostered(10L, PetType.CAT, 0);
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of());

        service.applyExistingFlagsToPet(p);

        verifyNoInteractions(completions);
    }

    @Test
    void applyByIdLooksUpThePet() {
        PetProfile p = rostered(10L, PetType.DOG, 0);
        when(profiles.findById(10L)).thenReturn(Optional.of(p));
        when(flags.findFlagKeysByUserId(OWNER)).thenReturn(List.of("FIRST_FOLLOW_USER"));

        service.applyExistingFlagsToPet(10L);
        service.applyExistingFlagsToPet(99L); // 不存在 → 静默

        assertThat(saved).extracting(MilestoneCompletion::getPetMilestoneId)
                .containsExactly(rosterIds.get("10/D-S19"));
    }

    /** 注销删 flag 也取账号锁：进行中的触发先提交，之后的触发读到注销受理行 no-op。 */
    @Test
    void deleteByUserIdLocksThenDeletes() {
        service.deleteByUserId(OWNER);
        InOrder order = inOrder(lock, flags);
        order.verify(lock).lockUser(OWNER);
        order.verify(flags).deleteByUserId(OWNER);
    }
}
