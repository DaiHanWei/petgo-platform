package com.tailtopia.moderation.repository;

import com.tailtopia.moderation.domain.AccountReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 账号举报工单（Story 2.1）。一行 = 一个被举报账号，{@code targetUserId} 唯一。 */
public interface AccountReportRepository extends JpaRepository<AccountReport, Long> {

    Optional<AccountReport> findByTargetUserId(long targetUserId);
}
