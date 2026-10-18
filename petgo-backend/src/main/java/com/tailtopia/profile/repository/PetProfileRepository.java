package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.PetProfile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {

    boolean existsByOwnerId(long ownerId);

    /** V1.4.0 multi-pet Story 1.4：账号已建宠物只数（名额判定，经 {@code PetSlotService.quotaOf} / 建档上限）。 */
    long countByOwnerId(long ownerId);

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
}
