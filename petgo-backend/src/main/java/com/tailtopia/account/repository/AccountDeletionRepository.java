package com.tailtopia.account.repository;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 注销作业持久层（Story 7.3）。启动重扫续跑未完成作业（PENDING/PROCESSING）。 */
public interface AccountDeletionRepository extends JpaRepository<AccountDeletion, Long> {

    Optional<AccountDeletion> findByUserId(long userId);

    List<AccountDeletion> findByStatusIn(List<DeletionStatus> statuses);
}
