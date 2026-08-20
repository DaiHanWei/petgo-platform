package com.tailtopia.moderation.repository;

import com.tailtopia.moderation.domain.AccountDisposal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 账号处置记录（Story 3.1 建表 / Story 3.2 写入）。
 *
 * <p>工单列表里的「历史处置次数」不走这里逐行查 —— 那是 N+1；统一工单查询在同一条 SQL 里聚合。
 * 本仓储供工单**详情**（一次一条）与 Story 3.2 的写入使用。
 */
public interface AccountDisposalRepository extends JpaRepository<AccountDisposal, Long> {

    /** 某账号的历史处置，最近的在前（工单详情展示用）。 */
    List<AccountDisposal> findByTargetUserIdOrderByCreatedAtDesc(long targetUserId);

    long countByTargetUserId(long targetUserId);
}
