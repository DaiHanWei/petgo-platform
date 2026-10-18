package com.tailtopia.profile.slot.web;

import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/v1/pet-slots/me} 响应（V1.4.0 multi-pet Story 1.4 最小版；Story 2.2 AC8.1 在末尾<b>追加</b>字段）。
 *
 * <p>1.4 已有字段名与顺序不变；Story 2.2 追加 {@code readOnlyCount}、{@code shortBy}、{@code renewableMax}、{@code rentals[]}，
 * {@code unitPrice} 改读价格配置（App 无本地兜底价）。名额构成全部来自 {@link PetSlotService#quotaOf}。
 * 宠物列表不重复下发（App 用 {@code GET /pet-profiles/me/list}）。
 *
 * @param total         名额
 * @param cap           只数硬上限（5）
 * @param defaultSlots  默认名额
 * @param levelSlots    等级名额
 * @param rentalSlots   月租名额（有效值）
 * @param petCount      已建只数
 * @param rentable      还能租几个（5 − total，D4 置灰）
 * @param canRent       能否租
 * @param nextLevelSlot 下一个解锁名额的等级，可 null
 * @param unitPrice     月租单价（IDR / 名额 / 30 天）
 * @param days          一次月租天数（30）
 * @param readOnlyCount 只读宠物只数
 * @param shortBy       缺的名额数 = max(0, petCount − total)（D3「Slot kamu kurang {n}」）
 * @param renewableMax  续租默认勾选上限 = max(0, 5 − 1 − levelSlots)（D11）
 * @param rentals       生效中的月租名额，按 {@code starts_at, id} 升序；{@code index} 从 1（D-11「Slot sewa {n}」）
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
        int days,
        int readOnlyCount,
        int shortBy,
        int renewableMax,
        List<Rental> rentals) {

    public PetSlotOverview {
        rentals = rentals == null ? List.of() : List.copyOf(rentals);
    }

    /** 无月租明细 / 只读计数时的形态（1.4 调用形状）。 */
    public static PetSlotOverview of(PetSlotQuota q, long unitPrice) {
        return of(q, unitPrice, 0, List.of(), Instant.now());
    }

    public static PetSlotOverview of(PetSlotQuota q, long unitPrice, int readOnlyCount, List<PetSlotRental> active,
            Instant now) {
        List<Rental> rows = new ArrayList<>();
        int index = 1;
        for (PetSlotRental r : active) {
            rows.add(new Rental(r.getPublicToken(), index++, r.getSource().name(), r.getStartsAt(), r.getExpiresAt(),
                    daysLeft(r.getExpiresAt(), now)));
        }
        return new PetSlotOverview(q.total(), PetSlotService.MAX_PETS, q.defaultSlots(), q.levelSlots(),
                q.rentalSlots(), q.petCount(), q.rentable(), q.canRent(), q.nextLevelSlot(), unitPrice,
                PetSlotService.RENTAL_DAYS, readOnlyCount, Math.max(0, q.petCount() - q.total()),
                PetSlotService.renewableMax(q), rows);
    }

    /** 剩余天数：不足一天按一天算（向上取整），已到期为 0（D-13「还剩 {n} 天」）。 */
    static int daysLeft(Instant expiresAt, Instant now) {
        long seconds = Duration.between(now, expiresAt).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (int) ((seconds + 86_399) / 86_400);
    }

    /**
     * 一个生效中的月租名额。
     *
     * @param token     名额 {@code public_token}（续租寻址，不可枚举）
     * @param index     显示序号（从 1）
     * @param source    RENT / ADMIN_GRANT
     * @param startsAt  生效时刻（UTC）
     * @param expiresAt 到期时刻（UTC）
     * @param daysLeft  剩余天数（向上取整）
     */
    public record Rental(String token, int index, String source, Instant startsAt, Instant expiresAt, int daysLeft) {
    }
}
