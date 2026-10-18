package com.tailtopia.profile.slot.web;

import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;

/**
 * {@code GET /api/v1/pet-slots/me} 响应（V1.4.0 multi-pet Story 1.4 最小版；Story 2.2 AC8 同一 DTO）。
 *
 * <p>Story 2.2 只<b>追加</b>字段（{@code readOnlyCount}、{@code shortBy}、{@code renewableMax}、{@code rentals[]}）
 * 并把 {@code unitPrice} 改读价格配置，不改本 story 已有字段名。全部取值来自 {@link PetSlotService#quotaOf}。
 *
 * @param total         名额
 * @param cap           只数硬上限（5）
 * @param defaultSlots  默认名额
 * @param levelSlots    等级名额
 * @param rentalSlots   月租名额（本 story 恒 0）
 * @param petCount      已建只数
 * @param rentable      还能租几个
 * @param canRent       能否租
 * @param nextLevelSlot 下一个解锁名额的等级，可 null
 * @param unitPrice     月租单价（IDR）
 * @param days          一次月租天数（30）
 */
public record PetSlotOverview(
        int total,
        int cap,
        int defaultSlots,
        int levelSlots,
        int rentalSlots,
        int petCount,
        int rentable,
        boolean canRent,
        Integer nextLevelSlot,
        long unitPrice,
        int days) {

    public static PetSlotOverview of(PetSlotQuota q, long unitPrice) {
        return new PetSlotOverview(q.total(), PetSlotService.MAX_PETS, q.defaultSlots(), q.levelSlots(),
                q.rentalSlots(), q.petCount(), q.rentable(), q.canRent(), q.nextLevelSlot(), unitPrice,
                PetSlotService.RENTAL_DAYS);
    }
}
