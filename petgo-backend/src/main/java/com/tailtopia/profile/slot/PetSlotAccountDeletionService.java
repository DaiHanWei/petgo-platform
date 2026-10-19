package com.tailtopia.profile.slot;

import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注销级联：宠物名额终止（OCR #2，D1/D2）。幂等可重跑。
 *
 * <p>注销后到期扫描仍会给该账号的 ACTIVE 名额写「即将到期 / 已到期」通知与推送。名额行是付费记录（关联
 * {@code pet_slot_purchases} / 支付意图），照 {@code consult_orders} / {@code shop_orders} 的交易记录口径<b>保留行</b>，
 * 只把 ACTIVE 置 EXPIRED，使其退出扫描。不发任何通知，不 reconcile（宠物已在前一步全部删除）。
 *
 * <p>持 {@link PetSlotLock#lockUser}：与进行中的续租到账 / 月租发放 / 扫描串行，不会被它们的整行写回覆盖。
 * 注销之后才到账的新租赁由到期提醒查询排除已注销账号兜底（{@code findUserIdsWithDueReminder}）。
 */
@Service
public class PetSlotAccountDeletionService {

    private final PetSlotRentalRepository rentals;
    private final PetSlotLock lock;
    private final Clock clock;

    @Autowired
    public PetSlotAccountDeletionService(PetSlotRentalRepository rentals, PetSlotLock lock) {
        this(rentals, lock, Clock.systemUTC());
    }

    PetSlotAccountDeletionService(PetSlotRentalRepository rentals, PetSlotLock lock, Clock clock) {
        this.rentals = rentals;
        this.lock = lock;
        this.clock = clock;
    }

    /** @return 本次置 EXPIRED 的名额数 */
    @Transactional
    public int expireAllForAccount(long userId) {
        lock.lockUser(userId);
        return rentals.expireAllOfUser(userId, PetSlotRentalStatus.ACTIVE, PetSlotRentalStatus.EXPIRED,
                Instant.now(clock));
    }
}
