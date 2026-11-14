package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.PetProfile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {

    boolean existsByOwnerId(long ownerId);

    /** V1.4.0 multi-pet Story 1.4：账号已建宠物只数（名额判定，经 {@code PetSlotService.quotaOf} / 建档上限）。 */
    long countByOwnerId(long ownerId);

    /** V1.4.0 multi-pet Story 2.2：账号某状态的宠物只数（名额管理页 {@code readOnlyCount}）。 */
    long countByOwnerIdAndStatus(long ownerId, com.tailtopia.profile.domain.PetStatus status);

    /** V1.4.0 multi-pet（AD-2）：账号全部宠物，按创建时间升序（同刻按 id 升序）。 */
    List<PetProfile> findAllByOwnerIdOrderByCreatedAtAscIdAsc(long ownerId);

    /**
     * V1.4.0 multi-pet（Story 1.2）：批量取多账号的全部宠物，按创建时间升序（同刻按 id 升序）。
     * 取每账号默认宠物一律再经 {@link com.tailtopia.profile.service.DefaultPetPolicy#pickPerOwner}。
     */
    List<PetProfile> findAllByOwnerIdInOrderByCreatedAtAscIdAsc(Collection<Long> ownerIds);

    /** 按宠物 id 取且校验归属（不属于本人 = 查不到）。 */
    Optional<PetProfile> findByIdAndOwnerId(long id, long ownerId);

    boolean existsByIdAndOwnerId(long id, long ownerId);

    /** 默认宠物（定义只在 {@link com.tailtopia.profile.service.DefaultPetPolicy}）。 */
    default Optional<PetProfile> findDefault(long ownerId) {
        return com.tailtopia.profile.service.DefaultPetPolicy.pick(
                findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId));
    }

    Optional<PetProfile> findByCardToken(String cardToken);

    /** 建档满阈值天数的档案（陪伴里程碑定时扫描，Story 8.3 C-M8 陪伴满 30 天）。 */
    List<PetProfile> findByCreatedAtLessThanEqual(Instant threshold);

    /** 内容审核 story 4：违规重置默认宠物名唯一性查重（DefaultNameGenerator）。 */
    boolean existsByName(String name);

    /**
     * 追更计数 ±1（V1.4.2 social-topic Story 1.1 · D-1，只由 {@code social.service.PetFollowService} 在写入 /
     * 删除追更行的<b>同一事务</b>里调用）。
     *
     * <p>🔴 必须在 SQL 里改，<b>禁止</b>读出来在 Java 里 ±1 再 save（并发丢更新）；
     * 计数列在实体上是只读映射。{@code GREATEST(…, 0)} 兜底，计数永不为负（照 {@code UserRepository#addFollowerCount}）。
     */
    @Modifying
    @Query(value = "UPDATE pet_profiles SET follower_count = GREATEST(follower_count + :delta, 0) WHERE id = :petId",
            nativeQuery = true)
    int addFollowerCount(@Param("petId") long petId, @Param("delta") int delta);

    /** 重新读出 {@code follower_count}（实体值是加载时快照，追更写入后要最新值时用）；宠物不存在为 null。 */
    @Query(value = "SELECT follower_count FROM pet_profiles WHERE id = :petId", nativeQuery = true)
    Integer findFollowerCount(@Param("petId") long petId);

    /**
     * 对宠物行加悲观写锁后读取（V1.5.0 pet-species · D-8：冷却判定是读后写，防两台设备同时改绕过冷却）。
     * 须在事务内调用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PetProfile p where p.id = :id")
    Optional<PetProfile> findByIdForUpdate(@Param("id") long id);
}
