package com.tailtopia.place.repository;

import com.tailtopia.place.domain.PlaceCheckin;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * App 侧 {@code place_checkins} 仓储（V1.3.2 batch-a Story 1.1）。
 *
 * <p>🔴 <b>名字刻意不叫 {@code PlaceCheckinRepository}</b>：后台已有同名接口
 * {@code com.tailtopia.admin.places.repository.PlaceCheckinRepository}（合并改挂用），
 * 同名会撞 Spring 默认 bean 名。
 *
 * <p>「章」相关的计数一律按<b>当前</b> {@code place_id} 查（合并进来的原场所历史也算，AD-5）。
 */
public interface PlaceVisitRepository extends JpaRepository<PlaceCheckin, Long> {

    /** 该宠物在当前场所某个 WIB 日是否已打卡（「每天限一次」的业务判定，AC2.5）。 */
    @Query("select count(cp) > 0 from PlaceCheckinPet cp, PlaceCheckin c "
            + "where cp.id.checkinId = c.id and cp.id.petProfileId = :petId "
            + "and c.placeId = :placeId and c.visitDate = :day")
    boolean existsForPetOnDay(@Param("petId") long petId, @Param("placeId") long placeId,
            @Param("day") LocalDate day);

    /** 该宠物在当前场所的打卡总次数（= 章的次数，AD-5）。 */
    @Query("select count(cp) from PlaceCheckinPet cp, PlaceCheckin c "
            + "where cp.id.checkinId = c.id and cp.id.petProfileId = :petId and c.placeId = :placeId")
    long countForPetAtPlace(@Param("petId") long petId, @Param("placeId") long placeId);

    /** 本人（任一宠物）今天是否已在该场所打卡 —— 详情页 {@code checkedInToday}（AC3）。 */
    @Query("select count(c) > 0 from PlaceCheckin c where c.userId = :userId "
            + "and c.placeId = :placeId and c.visitDate = :day")
    boolean existsForUserOnDay(@Param("userId") long userId, @Param("placeId") long placeId,
            @Param("day") LocalDate day);

    /** 删档：该用户名下已无任何关联宠物的打卡行 id（AC6.1）。 */
    @Query("select c.id from PlaceCheckin c where c.userId = :userId and not exists "
            + "(select 1 from PlaceCheckinPet cp where cp.id.checkinId = c.id)")
    List<Long> findOrphanIdsOfUser(@Param("userId") long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PlaceCheckin c where c.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
