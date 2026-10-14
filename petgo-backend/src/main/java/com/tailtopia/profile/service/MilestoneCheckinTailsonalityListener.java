package com.tailtopia.profile.service;

import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.tailsonality.service.TailsonalityResultPetQuery;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 场所打卡 / Tailsonality 完整解读解锁 → 里程碑逐宠自动达成（V1.3.4 Story 4.2 · FR-126 · AD-18）。
 *
 * <p>与 {@link MilestoneAutoCompleteListener} 同一护栏：{@code @TransactionalEventListener}（AFTER_COMMIT）
 * + {@code @Async}，幂等完成经 {@link MilestoneCompletionService}。单独成类：后者构造器只有一个参数、
 * 多个既有单测直接 {@code new} 它，这两路需要额外依赖。
 *
 * <p>⚠️ 不得订阅 {@code PaymentIntentPaidEvent}（到账唯一消费者是 {@code KeepsakePaidHandler}）。
 */
@Component
public class MilestoneCheckinTailsonalityListener {

    private final MilestoneCompletionService completion;
    private final TailsonalityResultPetQuery tailsonalityPets;

    public MilestoneCheckinTailsonalityListener(MilestoneCompletionService completion,
            TailsonalityResultPetQuery tailsonalityPets) {
        this.completion = completion;
        this.tailsonalityPets = tailsonalityPets;
    }

    /** 打卡成功 → 本次选中的每只宠物完成其物种的打卡项（PRD「逐宠达成」；本版一人一宠，结构按列表处理）。 */
    @Async
    @TransactionalEventListener
    public void onPlaceCheckedIn(PlaceCheckedInEvent e) {
        if (e.petIds() == null) {
            return;
        }
        for (Long petId : e.petIds()) {
            if (petId != null) {
                completion.completeForPet(petId, MilestoneAutoEvent.PLACE_CHECKED_IN,
                        MilestoneCompletionSource.SYSTEM_AUTO);
            }
        }
    }

    /**
     * 完整解读解锁 → 该结果所属宠物完成性格测试项。🔴 只认 TAILSONALITY（含补差价升级）；
     * TS_MATCH（配型单买）不算（PRD 2026-10-12）；其它 SKU 与本里程碑无关。
     */
    @Async
    @TransactionalEventListener
    public void onKeepsakeUnlocked(KeepsakeUnlockedEvent e) {
        if (e.sku() != KeepsakeSku.TAILSONALITY) {
            return;
        }
        tailsonalityPets.petProfileIdOf(e.refId()).ifPresent(petId ->
                completion.completeForPet(petId, MilestoneAutoEvent.TAILSONALITY_UNLOCKED,
                        MilestoneCompletionSource.SYSTEM_AUTO));
    }
}
