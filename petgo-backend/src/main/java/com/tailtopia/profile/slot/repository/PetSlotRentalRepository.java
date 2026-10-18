package com.tailtopia.profile.slot.repository;

import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 月租名额（V1.4.0 multi-pet Story 2.2）。名额数一律经 {@code PetSlotService.quotaOf}，别处不得自数。 */
public interface PetSlotRentalRepository extends JpaRepository<PetSlotRental, Long> {

    /** 生效中名额数：{@code status = :status AND expires_at > :now}（{@code quotaOf} 专用）。 */
    long countByUserIdAndStatusAndExpiresAtAfter(long userId, PetSlotRentalStatus status, Instant now);

    /** 生效中名额，按 {@code starts_at, id} 升序（名额管理页「Slot sewa {n}」序号）。 */
    List<PetSlotRental> findByUserIdAndStatusAndExpiresAtAfterOrderByStartsAtAscIdAsc(long userId,
            PetSlotRentalStatus status, Instant now);

    /** 续租寻址：按对外 token + 归属取（非本人 = 查不到 → 404）。 */
    Optional<PetSlotRental> findByPublicTokenAndUserId(String publicToken, long userId);

    // ---------------------------------------------------------------------------------------------
    // Story 2.3 到期扫描（走索引 ix_pet_slot_rentals_status_expires）
    // ---------------------------------------------------------------------------------------------

    /** 有已到期待处理名额（{@code status = :status AND expires_at <= :now}）的用户，按 userId 升序取一批（剩余下一轮）。 */
    @Query("SELECT DISTINCT r.userId FROM PetSlotRental r WHERE r.status = :status AND r.expiresAt <= :now"
            + " ORDER BY r.userId")
    List<Long> findUserIdsWithDueExpiry(@Param("status") PetSlotRentalStatus status, @Param("now") Instant now,
            Pageable page);

    /** 加锁后重读：该用户 {@code status = :status AND expires_at <= :now} 的名额（续租可能已把到期日推后）。 */
    List<PetSlotRental> findByUserIdAndStatusAndExpiresAtLessThanEqualOrderByIdAsc(long userId,
            PetSlotRentalStatus status, Instant now);

    /** 有提醒候选（未提醒、{@code now < expires_at <= until}）名额的用户，按 userId 升序取一批。 */
    @Query("SELECT DISTINCT r.userId FROM PetSlotRental r WHERE r.status = :status AND r.remindedAt IS NULL"
            + " AND r.expiresAt > :now AND r.expiresAt <= :until ORDER BY r.userId")
    List<Long> findUserIdsWithDueReminder(@Param("status") PetSlotRentalStatus status, @Param("now") Instant now,
            @Param("until") Instant until, Pageable page);

    /**
     * 该用户<b>之前轮次已提醒过</b>、仍生效未到期的名额到期时刻（2.3 复审：同一 WIB 到期日跨小时分批进入窗口时，
     * 后一轮不再发同样的一条）。
     */
    @Query("SELECT r.expiresAt FROM PetSlotRental r WHERE r.userId = :userId AND r.status = :status"
            + " AND r.remindedAt IS NOT NULL AND r.expiresAt > :now")
    List<Instant> findRemindedExpiries(@Param("userId") long userId,
            @Param("status") PetSlotRentalStatus status, @Param("now") Instant now);

    /** 该用户的提醒候选名额（同上口径），按到期日升序。 */
    @Query("SELECT r FROM PetSlotRental r WHERE r.userId = :userId AND r.status = :status AND r.remindedAt IS NULL"
            + " AND r.expiresAt > :now AND r.expiresAt <= :until ORDER BY r.expiresAt, r.id")
    List<PetSlotRental> findReminderCandidates(@Param("userId") long userId,
            @Param("status") PetSlotRentalStatus status, @Param("now") Instant now, @Param("until") Instant until);

    /**
     * 条件置 {@code reminded_at}：仍 {@code ACTIVE}、未提醒、且仍在提醒窗口内才更新（并发续租已推后 / 清空则不命中）。
     * 返回 1 = 本次由我置位（应发提醒）；0 = 已被他人处理或已不在窗口。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PetSlotRental r SET r.remindedAt = :now WHERE r.id = :id AND r.status = :status"
            + " AND r.remindedAt IS NULL AND r.expiresAt > :now AND r.expiresAt <= :until")
    int markReminded(@Param("id") long id, @Param("status") PetSlotRentalStatus status, @Param("now") Instant now,
            @Param("until") Instant until);
}
