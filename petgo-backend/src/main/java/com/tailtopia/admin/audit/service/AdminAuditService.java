package com.tailtopia.admin.audit.service;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.domain.AuditHashing;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一审计入口（Story 1.3，AC2/AC3）。**所有后台写操作的强制副作用**——Epic 2~6 各业务写
 * 必须在同一事务内调用 {@link #record}，禁止各控制器/服务自拼 SQL 或绕过本入口（架构 Enforcement）。
 *
 * <p>{@link #record} 为 {@code @Transactional}（默认 REQUIRED）：被业务事务调用时<b>加入</b>该事务，
 * 业务回滚则审计一并回滚（无悬挂审计）；独立调用（如紧急登录无业务事务）时自开事务提交。
 * 每次写入：advisory 锁串行化 → 取链尾 {@code rowHash} 作本行 {@code prevHash}（首行=创世值）→
 * 算 {@code rowHash} → append。{@code createdAt} 取 UTC 并 {@code truncatedTo(MICROS)}（与列精度一致，
 * 保证回读可逐行复算校验，见 {@link AuditChainVerifier}）。
 */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;
    private final AuditChainLock chainLock;

    public AdminAuditService(AdminAuditLogRepository repository, AuditChainLock chainLock) {
        this.repository = repository;
        this.chainLock = chainLock;
    }

    /**
     * 记录一条审计（哈希链 append-only）。
     *
     * @param actorAccountId 操作人后台账号 id（{@code admin_accounts.id}）；系统发起可为 null
     * @param actionType     动作类型（{@link AuditActions}，UPPER_SNAKE 过去式）
     * @param targetType     目标资源类型（可空）
     * @param targetId       目标外露标识：不可枚举 token 或业务 id 字符串（可空）
     * @param summary        人类可读摘要；<b>严禁含密码/令牌/签名 URL/健康数据</b>
     * @return 落库的审计行（含 id 与链哈希）
     */
    @Transactional
    public AdminAuditLog record(Long actorAccountId, String actionType, String targetType,
            String targetId, String summary) {
        // AG-2：串行化整段临界区，避免并发取到同一链尾导致分叉。
        chainLock.acquire();

        String prevHash = repository.findTopByOrderByIdDesc()
                .map(AdminAuditLog::getRowHash)
                .orElse(AuditHashing.GENESIS_HASH);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String rowHash = AuditHashing.rowHash(prevHash, actorAccountId, actionType, targetType,
                targetId, summary, createdAt);

        return repository.save(AdminAuditLog.create(actorAccountId, actionType, targetType,
                targetId, summary, createdAt, prevHash, rowHash));
    }

    /** 筛选分页查询（AC6）。 */
    @Transactional(readOnly = true)
    public Page<AdminAuditLog> search(Instant from, Instant to, Long actor, String action,
            Pageable pageable) {
        return repository.search(from, to, actor, action, pageable);
    }

    /**
     * 某内容/工单最新下架审计摘要（Bug 20260701-169，举报队列「已下架」展示下架原因/摘要）。只读、不改链。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> takedownSummary(long postId, long reportId) {
        return repository.latestTakedownSummary(postId, reportId);
    }
}
