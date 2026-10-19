package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthBenefit;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse.CelebrationView;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse.FrameAction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 ① Story 2.1 · AC3 / AC4（L0）：庆祝字段、frameAction、读时校正、ack 竞态与幂等。 */
class GrowthPopupServiceTest {

    private final GrowthAccountRepository accounts = mock(GrowthAccountRepository.class);
    private final GrowthLevelDefRepository levels = mock(GrowthLevelDefRepository.class);
    private final GrowthPageQuery page = mock(GrowthPageQuery.class);
    private final GrowthPopupService service = new GrowthPopupService(accounts, levels, page,
            Clock.fixed(Instant.parse("2026-10-18T05:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        List<GrowthLevelDef> t = new ArrayList<>();
        for (int lv = 1; lv <= 20; lv++) {
            GrowthTier tier = lv <= 4 ? GrowthTier.NEWBIE : lv <= 9 ? GrowthTier.BUDDY
                    : lv <= 14 ? GrowthTier.GUARDIAN : GrowthTier.STAR;
            t.add(GrowthLevelDef.of(lv, lv * 100L, tier));
        }
        when(levels.findAllByOrderByLevelAsc()).thenReturn(t);
        when(page.participating(7L)).thenReturn(true);
    }

    private GrowthAccount account(int level, Integer from, Integer to, FrameChoice frame) {
        GrowthAccount a = GrowthAccount.of(7L, 1234, level);
        ReflectionTestUtils.setField(a, "celebrateFromLevel", from);
        ReflectionTestUtils.setField(a, "celebrateToLevel", to);
        ReflectionTestUtils.setField(a, "frameChoice", frame);
        when(accounts.findById(7L)).thenReturn(Optional.of(a));
        when(accounts.lockById(7L)).thenReturn(Optional.of(a));
        return a;
    }

    private CelebrationView celebration() {
        return service.getPopups(7L).celebration();
    }

    @Test
    void noPendingIsNull() {
        account(5, null, null, null);
        GrowthPopupsResponse r = service.getPopups(7L);
        assertThat(r.celebration()).isNull();
        assertThat(r.backfill()).isNull();
    }

    @Test
    void inTierHasNoBenefitsAndNoFrameAction() {
        account(7, 6, 7, null);
        CelebrationView c = celebration();
        assertThat(c.tierUp()).isFalse();
        assertThat(c.toTier()).isEqualTo(GrowthTier.BUDDY);
        assertThat(c.unlockedBenefits()).isEmpty();
        assertThat(c.frameAction()).isEqualTo(FrameAction.NONE);
        assertThat(c.lifetimeTotal()).isEqualTo(1234);
    }

    @Test
    void tierUpWithAutoFrame() {
        account(5, 4, 5, null);
        CelebrationView c = celebration();
        assertThat(c.tierUp()).isTrue();
        assertThat(c.unlockedBenefits()).containsExactly(GrowthBenefit.PET_SLOT_PLUS_ONE, GrowthBenefit.FRAME_BASIC,
                GrowthBenefit.EXCLUSIVE_GIFTS_BATCH1);
        assertThat(c.frameAction()).isEqualTo(FrameAction.AUTO);
    }

    @Test
    void tierUpWithManualFrameOffersNewFrame() {
        account(10, 9, 10, FrameChoice.BASIC);
        assertThat(celebration().frameAction()).isEqualTo(FrameAction.OFFER_NEW_FRAME);
        account(10, 9, 10, FrameChoice.NONE);
        assertThat(celebration().frameAction()).isEqualTo(FrameAction.OFFER_NEW_FRAME);
    }

    @Test
    void jumpAcrossTwoTiersGivesOnlyFinalTierBenefits() {
        account(11, 4, 11, null);
        CelebrationView c = celebration();
        assertThat(c.toTier()).isEqualTo(GrowthTier.GUARDIAN);
        assertThat(c.unlockedBenefits()).containsExactly(GrowthBenefit.PET_SLOT_PLUS_ONE, GrowthBenefit.FRAME_UPGRADED);
    }

    @Test
    void downgradeIsCorrectedAtReadTime() {
        account(6, 4, 7, null);
        assertThat(celebration().toLevel()).isEqualTo(6);

        account(4, 4, 7, null);
        assertThat(celebration()).isNull();
        org.mockito.Mockito.verify(accounts).clearStaleCelebration(7L);
    }

    @Test
    void staleAckBelowFromIsIgnored() {
        GrowthAccount a = account(7, 5, 7, null);
        service.ackCelebration(7L, 1);
        assertThat(a.getCelebrateFromLevel()).isEqualTo(5);
        assertThat(a.getCelebrateToLevel()).isEqualTo(7);
    }

    @Test
    void ackWithSmallerToLevelOnlyAdvancesFrom() {
        GrowthAccount a = account(7, 4, 7, null);
        service.ackCelebration(7L, 5);
        assertThat(a.getCelebrateFromLevel()).isEqualTo(5);
        assertThat(a.getCelebrateToLevel()).isEqualTo(7);
    }

    @Test
    void ackWithoutParamClearsAndIsIdempotent() {
        GrowthAccount a = account(7, 4, 7, null);
        service.ackCelebration(7L, null);
        service.ackCelebration(7L, 7);
        assertThat(a.getCelebrateFromLevel()).isNull();
        assertThat(a.getCelebrateToLevel()).isNull();
    }

    @Test
    void ackBackfillClearsFlagAndNoAccountIsNoop() {
        GrowthAccount a = account(5, null, null, null);
        ReflectionTestUtils.setField(a, "backfillPopupPending", true);
        service.ackBackfill(7L);
        assertThat(a.isBackfillPopupPending()).isFalse();

        when(accounts.lockById(8L)).thenReturn(Optional.empty());
        service.ackBackfill(8L);
        service.ackCelebration(8L, null);
    }

    // ---------- Story 2.2：存量汇总 ----------

    private GrowthAccount backfillAccount(boolean pending) {
        GrowthAccount a = account(4, null, null, null);
        ReflectionTestUtils.setField(a, "backfillPopupPending", pending);
        ReflectionTestUtils.setField(a, "backfillActiveDays", 60);
        ReflectionTestUtils.setField(a, "backfillMilestoneCount", 13);
        ReflectionTestUtils.setField(a, "backfillMilestoneExp", 95);
        when(levels.findById(4)).thenReturn(Optional.of(GrowthLevelDef.of(4, 250, GrowthTier.NEWBIE)));
        return a;
    }

    @Test
    void pendingBackfillReturnsSnapshot() {
        backfillAccount(true);
        GrowthPopupsResponse.BackfillView b = service.getPopups(7L).backfill();
        assertThat(b).isEqualTo(new GrowthPopupsResponse.BackfillView(4, GrowthTier.NEWBIE, 60, 13, 95, 275));
    }

    @Test
    void notPendingBackfillIsNull() {
        backfillAccount(false);
        assertThat(service.getPopups(7L).backfill()).isNull();
    }

    @Test
    void celebrationAndBackfillTogether() {
        GrowthAccount a = backfillAccount(true);
        ReflectionTestUtils.setField(a, "level", 5);
        ReflectionTestUtils.setField(a, "celebrateFromLevel", 4);
        ReflectionTestUtils.setField(a, "celebrateToLevel", 5);
        when(levels.findById(5)).thenReturn(Optional.of(GrowthLevelDef.of(5, 500, GrowthTier.BUDDY)));
        GrowthPopupsResponse r = service.getPopups(7L);
        assertThat(r.celebration()).isNotNull();
        assertThat(r.backfill().tier()).isEqualTo(GrowthTier.BUDDY);
    }

    @Test
    void nonParticipantGetsNothing() {
        when(page.participating(9L)).thenReturn(false);
        GrowthPopupsResponse r = service.getPopups(9L);
        assertThat(r.celebration()).isNull();
        assertThat(r.backfill()).isNull();
    }

    // ---- V1.4.0 multi-pet Story 4.4 · AC1：petSlotHint ----

    /** 假判定：等级名额 [Lv≥5] + [Lv≥10] 变多 且 hasRental；记录调用参数。 */
    private final List<int[]> hintCalls = new ArrayList<>();

    private GrowthPopupService serviceWithHint(boolean hasRental) {
        CelebrationPetSlotHint hint = (userId, from, to) -> {
            hintCalls.add(new int[] {(int) userId, from, to});
            int fromSlots = (from >= 5 ? 1 : 0) + (from >= 10 ? 1 : 0);
            int toSlots = (to >= 5 ? 1 : 0) + (to >= 10 ? 1 : 0);
            return toSlots > fromSlots && hasRental;
        };
        return new GrowthPopupService(accounts, levels, page, () -> hint,
                Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Lv{0} → Lv{1}，有月租 {2} → {3}")
    @org.junit.jupiter.params.provider.CsvSource({
        "4, 5, true, true",
        "4, 5, false, false",
        "5, 7, true, false",
        "9, 10, true, true",
        "4, 11, true, true",
    })
    void petSlotHintFromInjectedJudge(int from, int to, boolean hasRental, boolean expected) {
        account(to, from, to, null);
        assertThat(serviceWithHint(hasRental).getPopups(7L).celebration().petSlotHint()).isEqualTo(expected);
    }

    @Test
    void petSlotHintUsesReadTimeCorrectedLevels() {
        account(6, 4, 7, null); // 待弹 to 7、当前降到 6 → 按 4 → 6 判定
        CelebrationView c = serviceWithHint(true).getPopups(7L).celebration();
        assertThat(c.toLevel()).isEqualTo(6);
        assertThat(c.petSlotHint()).isTrue();
        assertThat(hintCalls).singleElement().satisfies(a -> assertThat(a).containsExactly(7, 4, 6));
    }

    @Test
    void petSlotHintFalseWithoutImplementation() {
        account(5, 4, 5, null);
        assertThat(celebration().petSlotHint()).isFalse(); // 既有 4 参构造：不注入 ② 的 bean
    }

    @Test
    void petSlotHintNotComputedWithoutCelebration() {
        GrowthPopupService s = serviceWithHint(true);
        account(5, null, null, null);
        assertThat(s.getPopups(7L).celebration()).isNull();
        account(4, 4, 7, null); // 校正后无待弹
        assertThat(s.getPopups(7L).celebration()).isNull();
        assertThat(hintCalls).isEmpty();
    }

    @Test
    void petSlotHintFailureDegradesToFalse() {
        account(5, 4, 5, null);
        GrowthPopupService s = new GrowthPopupService(accounts, levels, page, () -> (u, f, t) -> {
            throw new IllegalStateException("boom");
        }, Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC));
        CelebrationView c = s.getPopups(7L).celebration();
        assertThat(c.tierUp()).isTrue();
        assertThat(c.petSlotHint()).isFalse();
    }
}
