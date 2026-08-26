package com.tailtopia.admin.virtual.repository;

import com.tailtopia.admin.virtual.domain.SeedRealAccountGrant;
import com.tailtopia.admin.virtual.domain.SeedRealAccountGrant.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 运营发布身份池的真实账号授权（Story 12.1）。 */
public interface SeedRealAccountGrantRepository extends JpaRepository<SeedRealAccountGrant, Long> {

    /** 身份池当前生效的真实账号授权，新纳入的在前。 */
    List<SeedRealAccountGrant> findByStatusOrderByIdDesc(Status status);

    Optional<SeedRealAccountGrant> findByUserIdAndStatus(long userId, Status status);

    boolean existsByUserIdAndStatus(long userId, Status status);
}
