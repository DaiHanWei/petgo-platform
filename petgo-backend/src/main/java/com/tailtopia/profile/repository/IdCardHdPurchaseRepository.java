package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.IdCardHdPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdCardHdPurchaseRepository extends JpaRepository<IdCardHdPurchase, Long> {

    /** 是否已购买（=已永久解锁）。解锁判定 + 购买入口幂等短路（Story 6.3）。 */
    boolean existsByUserId(long userId);
}
