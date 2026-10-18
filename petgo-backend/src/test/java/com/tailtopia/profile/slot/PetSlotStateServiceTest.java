package com.tailtopia.profile.slot;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * L0：V1.4.0 multi-pet Story 2.1 · AC3 / AC4 / AC8 —— reconcile 规则（降级只在 RENTAL_EXPIRED / 改选、恢复按序不超名额、
 * keep_active 优先、无变化不写库、单宠物恒 ACTIVE、加锁在前）与改选校验。
 */
class PetSlotStateServiceTest {

    private static final long OWNER = 7L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetSlotService slots = mock(PetSlotService.class);
    private final PetSlotLock lock = mock(PetSlotLock.class);
    private final PetSlotStateService service =
            new PetSlotStateService(profiles, slots, lock, new PetResolver(profiles));

    private PetProfile a;
    private PetProfile b;
    private PetProfile c;
    private PetProfile d;

    @BeforeEach
    void setUp() {
        a = pet(OWNER, 10L, T0);
        b = pet(OWNER, 11L, T0.plusSeconds(60));
        c = pet(OWNER, 12L, T0.plusSeconds(120));
        d = pet(OWNER, 13L, T0.plusSeconds(180));
        for (PetProfile p : List.of(a, b, c, d)) {
            when(profiles.findByIdAndOwnerId(p.getId(), OWNER)).thenReturn(Optional.of(p));
        }
    }

    private void pets(PetProfile... list) {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(list));
    }

    private void quota(int total) {
        when(slots.quotaOf(OWNER)).thenReturn(
                new PetSlotQuota(total, 1, total - 1, 0, 0, 5 - total, total < 5, null, false));
    }

    private static List<Long> ids(List<ReconcileResult.PetRef> refs) {
        return refs.stream().map(ReconcileResult.PetRef::id).toList();
    }

    @Nested
    class 到期降级 {

        /** AC13c：A、B、C、D 四只、名额 2、未做选择 → A、B 正常，C、D 只读；demoted = [C, D]。 */
        @Test
        void fourPetsQuotaTwoKeepsEarliestTwo() {
            pets(a, b, c, d);
            quota(2);

            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.RENTAL_EXPIRED);

            assertThat(ids(r.demoted())).containsExactly(12L, 13L);
            assertThat(r.restored()).isEmpty();
            assertThat(r.demoted()).extracting(ReconcileResult.PetRef::name).containsExactly("Pet12", "Pet13");
            assertThat(List.of(a.getStatus(), b.getStatus())).containsOnly(PetStatus.ACTIVE);
            assertThat(List.of(c.getStatus(), d.getStatus())).containsOnly(PetStatus.READ_ONLY);
            verify(profiles).saveAll(List.of(c, d));
        }

        @Test
        void keepActiveWinsOverCreationOrder() {
            d.setKeepActive(true);
            pets(a, b, c, d);
            quota(2);

            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.RENTAL_EXPIRED);

            assertThat(ids(r.demoted())).containsExactly(11L, 12L);
            assertThat(a.getStatus()).isEqualTo(PetStatus.ACTIVE);
            assertThat(d.getStatus()).isEqualTo(PetStatus.ACTIVE);
        }

        @Test
        void withinQuotaChangesNothingAndDoesNotWrite() {
            pets(a, b);
            quota(2);

            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.RENTAL_EXPIRED);

            assertThat(r.changed()).isFalse();
            verify(profiles, never()).saveAll(anyList());
        }

        @Test
        void locksBeforeReading() {
            pets(a, b, c);
            quota(1);

            service.reconcile(OWNER, PetSlotTrigger.RENTAL_EXPIRED);

            InOrder o = inOrder(lock, profiles, slots);
            o.verify(lock).lockUser(OWNER);
            o.verify(profiles).findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER);
            o.verify(slots).quotaOf(OWNER);
        }

        /** decision-log ②：默认名额 ≥ 1 ⇒ 有宠物的账号恒至少一只正常。 */
        @Test
        void atLeastOnePetStaysActive() {
            pets(a, b, c, d);
            quota(1);

            service.reconcile(OWNER, PetSlotTrigger.RENTAL_EXPIRED);

            assertThat(List.of(a, b, c, d)).filteredOn(p -> p.getStatus() == PetStatus.ACTIVE).hasSize(1);
            assertThat(a.getStatus()).isEqualTo(PetStatus.ACTIVE);
        }
    }

    @Nested
    class 只恢复不降级 {

        /** AC9：Lv10 三只全部正常、降到 Lv9（名额 3 → 2）→ LEVEL_CHANGED 不转任何宠物为只读。 */
        @Test
        void levelDownNeverDemotes() {
            pets(a, b, c);
            quota(2);

            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.LEVEL_CHANGED);

            assertThat(r.changed()).isFalse();
            assertThat(List.of(a, b, c)).allMatch(p -> p.getStatus() == PetStatus.ACTIVE);
            verify(profiles, never()).saveAll(anyList());
        }

        /** 2 只只读、名额 +1 → 按顺序恢复 1 只（恢复数不超过名额）。 */
        @Test
        void restoresInOrderUpToQuota() {
            c.markReadOnly();
            d.markReadOnly();
            pets(a, b, c, d);
            quota(3);

            for (PetSlotTrigger t : List.of(PetSlotTrigger.RENTAL_ADDED, PetSlotTrigger.RENEWED,
                    PetSlotTrigger.LEVEL_CHANGED, PetSlotTrigger.PET_DELETED)) {
                c.markReadOnly();
                d.markReadOnly();
                ReconcileResult r = service.reconcile(OWNER, t);
                assertThat(ids(r.restored())).as(t.name()).containsExactly(12L);
                assertThat(r.demoted()).isEmpty();
                assertThat(c.getStatus()).isEqualTo(PetStatus.ACTIVE);
                assertThat(d.getStatus()).isEqualTo(PetStatus.READ_ONLY);
            }
        }

        @Test
        void restoreRespectsKeepActiveOrder() {
            c.markReadOnly();
            d.markReadOnly();
            d.setKeepActive(true);
            pets(a, b, c, d);
            quota(3);

            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.RENTAL_ADDED);

            assertThat(ids(r.restored())).containsExactly(13L);
            assertThat(c.getStatus()).isEqualTo(PetStatus.READ_ONLY);
        }

        /** AC6 例：名额 2、A/B 正常、C 只读，删 A（剩 B、C）→ C 恢复正常。 */
        @Test
        void petDeletedFreesSlot() {
            c.markReadOnly();
            pets(b, c);
            quota(2);

            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.PET_DELETED);

            assertThat(ids(r.restored())).containsExactly(12L);
            verify(profiles).saveAll(List.of(c));
        }

        @Test
        void singlePetAlwaysActiveAndNoWrite() {
            pets(a);
            quota(1);
            for (PetSlotTrigger t : PetSlotTrigger.values()) {
                ReconcileResult r = service.reconcile(OWNER, t);
                assertThat(r.changed()).as(t.name()).isFalse();
            }
            assertThat(a.getStatus()).isEqualTo(PetStatus.ACTIVE);
            verify(profiles, never()).saveAll(anyList());
        }

        @Test
        void noPetsIsNoop() {
            pets();
            ReconcileResult r = service.reconcile(OWNER, PetSlotTrigger.LEVEL_CHANGED);
            assertThat(r.changed()).isFalse();
            verify(slots, never()).quotaOf(anyLong());
        }
    }

    @Nested
    class 改选 {

        /** AC13c 续：改选 A、D → A、D 正常，B、C 只读，即时生效。 */
        @Test
        void selectAAndD() {
            c.markReadOnly();
            d.markReadOnly();
            pets(a, b, c, d);
            quota(2);

            ReconcileResult r = service.changeSelection(OWNER, List.of(10L, 13L));

            assertThat(a.getStatus()).isEqualTo(PetStatus.ACTIVE);
            assertThat(d.getStatus()).isEqualTo(PetStatus.ACTIVE);
            assertThat(b.getStatus()).isEqualTo(PetStatus.READ_ONLY);
            assertThat(c.getStatus()).isEqualTo(PetStatus.READ_ONLY);
            assertThat(List.of(a.isKeepActive(), d.isKeepActive())).containsOnly(true);
            assertThat(List.of(b.isKeepActive(), c.isKeepActive())).containsOnly(false);
            assertThat(ids(r.demoted())).containsExactly(11L);
            assertThat(ids(r.restored())).containsExactly(13L);
            verify(lock).lockUser(OWNER);
        }

        @Test
        void duplicatesAreCollapsedThenCounted() {
            pets(a, b, c);
            quota(2);
            assertThatThrownBy(() -> service.changeSelection(OWNER, List.of(10L, 10L)))
                    .isInstanceOfSatisfying(AppException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(422);
                        assertThat(e.getType()).isEqualTo(ErrorTypes.VALIDATION);
                    });
        }

        @Test
        void foreignPetIs404AndNothingChanges() {
            pets(a, b, c);
            quota(2);
            when(profiles.findByIdAndOwnerId(99L, OWNER)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.changeSelection(OWNER, List.of(10L, 99L)))
                    .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
            verify(profiles, never()).saveAll(anyList());
        }

        @Test
        void sameSelectionTwiceWritesNothingSecondTime() {
            pets(a, b, c);
            quota(2);
            service.changeSelection(OWNER, List.of(10L, 12L));
            org.mockito.Mockito.clearInvocations(profiles);

            ReconcileResult r = service.changeSelection(OWNER, List.of(12L, 10L));

            assertThat(r.changed()).isFalse();
            verify(profiles, never()).saveAll(anyList());
        }
    }

    /** 2.1 复审：改选列表超过 5 个直接 422，不进锁、不逐条查归属。 */
    @org.junit.jupiter.api.Test
    void changeSelectionRejectsOversizedList() {
        assertThatThrownBy(() -> service.changeSelection(OWNER, List.of(1L, 2L, 3L, 4L, 5L, 6L)))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        org.mockito.Mockito.verifyNoInteractions(lock);
    }
}
