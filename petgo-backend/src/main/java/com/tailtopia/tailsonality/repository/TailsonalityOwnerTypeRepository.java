package com.tailtopia.tailsonality.repository;

import com.tailtopia.tailsonality.domain.TailsonalityOwnerType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TailsonalityOwnerTypeRepository extends JpaRepository<TailsonalityOwnerType, Long> {

    Optional<TailsonalityOwnerType> findByUserId(long userId);

    /**
     * 保存 / 覆盖（照 {@code UserOnboardingMarkRepository.insertIfAbsent} 的原生写法，本表是 DO UPDATE）。
     *
     * <p>🔴 不用「先查后存」：并发两次 PUT 会撞主键，约束异常让事务 rollback-only。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO tailsonality_owner_types (user_id, type_code) VALUES (:userId, :typeCode) "
            + "ON CONFLICT (user_id) DO UPDATE SET type_code = EXCLUDED.type_code, updated_at = now()",
            nativeQuery = true)
    int upsert(@Param("userId") long userId, @Param("typeCode") String typeCode);

    /** 注销级联（D1）：纯个人数据，物理删除。幂等。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TailsonalityOwnerType t where t.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
