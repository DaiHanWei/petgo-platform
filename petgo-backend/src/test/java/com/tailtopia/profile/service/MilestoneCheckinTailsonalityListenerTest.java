package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.tailsonality.service.TailsonalityResultPetQuery;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * V1.3.4 Story 4.2 · AC3 / AC4（L0）：打卡逐宠达成；完整解读解锁只认 {@code TAILSONALITY}，
 * 配型单买 {@code TS_MATCH} 与其它 SKU 不算；结果已删 → 静默跳过。纯 Mockito。
 */
class MilestoneCheckinTailsonalityListenerTest {

    private MilestoneCompletionService completion;
    private TailsonalityResultPetQuery query;
    private MilestoneCheckinTailsonalityListener listener;

    @BeforeEach
    void setUp() {
        completion = Mockito.mock(MilestoneCompletionService.class);
        query = Mockito.mock(TailsonalityResultPetQuery.class);
        listener = new MilestoneCheckinTailsonalityListener(completion, query);
    }

    private static PlaceCheckedInEvent checkin(List<Long> petIds) {
        return new PlaceCheckedInEvent(7L, "t".repeat(32), PlaceType.PARK, true, 1, petIds);
    }

    private static KeepsakeUnlockedEvent unlocked(KeepsakeSku sku) {
        return new KeepsakeUnlockedEvent(7L, sku, 42L, 5000L, PayChannel.PAWCOIN);
    }

    @Test
    void checkinCompletesEveryPickedPet() {
        listener.onPlaceCheckedIn(checkin(List.of(5L, 6L)));

        verify(completion, times(1)).completeForPet(5L, MilestoneAutoEvent.PLACE_CHECKED_IN,
                MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion, times(1)).completeForPet(6L, MilestoneAutoEvent.PLACE_CHECKED_IN,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void checkinWithNoPetsCompletesNothing() {
        listener.onPlaceCheckedIn(checkin(List.of()));
        listener.onPlaceCheckedIn(checkin(null));

        verify(completion, never()).completeForPet(anyLong(), any(), any());
    }

    @Test
    void fullTailsonalityUnlockCompletesOwningPet() {
        when(query.petProfileIdOf(42L)).thenReturn(Optional.of(9L));

        listener.onKeepsakeUnlocked(unlocked(KeepsakeSku.TAILSONALITY));

        verify(completion, times(1)).completeForPet(9L, MilestoneAutoEvent.TAILSONALITY_UNLOCKED,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    /** 🔴 配型单买不算（PRD 2026-10-12）；护照快照 / 登机牌与本里程碑无关。 */
    @ParameterizedTest
    @EnumSource(value = KeepsakeSku.class, names = "TAILSONALITY", mode = EnumSource.Mode.EXCLUDE)
    void otherSkusAreIgnored(KeepsakeSku sku) {
        listener.onKeepsakeUnlocked(unlocked(sku));

        verify(query, never()).petProfileIdOf(anyLong());
        verify(completion, never()).completeForPet(anyLong(), any(), any());
    }

    @Test
    void tsMatchSpecificallyDoesNotCount() {
        listener.onKeepsakeUnlocked(unlocked(KeepsakeSku.TS_MATCH));

        verify(completion, never()).completeForPet(anyLong(), any(), any());
    }

    @Test
    void resultGoneIsSilentlySkipped() {
        when(query.petProfileIdOf(42L)).thenReturn(Optional.empty());

        listener.onKeepsakeUnlocked(unlocked(KeepsakeSku.TAILSONALITY));

        verify(completion, never()).completeForPet(anyLong(), any(), any());
    }

    @Test
    void bothHandlersAreAsyncAfterCommit() throws NoSuchMethodException {
        for (Method m : List.of(
                MilestoneCheckinTailsonalityListener.class.getMethod("onPlaceCheckedIn", PlaceCheckedInEvent.class),
                MilestoneCheckinTailsonalityListener.class.getMethod("onKeepsakeUnlocked",
                        KeepsakeUnlockedEvent.class))) {
            assertThat(m.isAnnotationPresent(Async.class)).as(m.getName()).isTrue();
            TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
            assertThat(tel).as(m.getName()).isNotNull();
            assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }
}
