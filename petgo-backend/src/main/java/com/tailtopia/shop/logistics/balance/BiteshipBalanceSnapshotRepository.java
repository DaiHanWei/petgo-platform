package com.tailtopia.shop.logistics.balance;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Biteship 余额快照（V1.4.2 shop-admin Story 4.1）。 */
public interface BiteshipBalanceSnapshotRepository extends JpaRepository<BiteshipBalanceSnapshot, Long> {

    /** 最新一条（预估起点）；同刻多条取 id 大者。 */
    Optional<BiteshipBalanceSnapshot> findFirstByOrderByRecordedAtDescIdDesc();
}
