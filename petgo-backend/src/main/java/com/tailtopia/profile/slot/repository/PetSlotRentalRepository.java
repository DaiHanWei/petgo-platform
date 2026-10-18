package com.tailtopia.profile.slot.repository;

import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 月租名额（V1.4.0 multi-pet Story 2.2）。名额数一律经 {@code PetSlotService.quotaOf}，别处不得自数。 */
public interface PetSlotRentalRepository extends JpaRepository<PetSlotRental, Long> {

    /** 生效中名额数：{@code status = :status AND expires_at > :now}（{@code quotaOf} 专用）。 */
    long countByUserIdAndStatusAndExpiresAtAfter(long userId, PetSlotRentalStatus status, Instant now);

    /** 生效中名额，按 {@code starts_at, id} 升序（名额管理页「Slot sewa {n}」序号）。 */
    List<PetSlotRental> findByUserIdAndStatusAndExpiresAtAfterOrderByStartsAtAscIdAsc(long userId,
            PetSlotRentalStatus status, Instant now);

    /** 续租寻址：按对外 token + 归属取（非本人 = 查不到 → 404）。 */
    Optional<PetSlotRental> findByPublicTokenAndUserId(String publicToken, long userId);
}
