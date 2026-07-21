package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 咨询请求仓储（Story 3.1）。<b>H-4 核心：accept/cancel 竞态走单列 {@code state} compare-and-set</b>
 * （非时间戳比较，防时钟偏移误判）。照 {@code PawCoinWalletRepository.applyDelta} 单列条件 UPDATE 范式：
 * 谓词 {@code WHERE ... AND state=QUEUEING} 在行锁下串行化，恰一方生效、返回受影响行数区分成败。
 * <b>禁应用层「先查 state 再改」</b>（TOCTOU 竞态）。取消/超时=物理删行（无 CANCELLED 态，A-5）。
 */
public interface ConsultRequestRepository extends JpaRepository<ConsultRequest, Long> {

    Optional<ConsultRequest> findByRequestToken(String requestToken);

    /** 占用校验（FR-4B 同时仅 1 个）：consult_requests 内「存在即 live」（取消/超时已物理删）。 */
    boolean existsByUserId(long userId);

    /** 注销清理（Story 7.3 / 决策 D1）：取该用户全部 live 请求，供收集私密图 key 后删行。 */
    List<ConsultRequest> findByUserId(long userId);

    /**
     * 兽医计费队列池（Story 3.6，只读）：处于给定 {@code state}（QUEUEING）的请求按建单时间 FIFO 升序。
     * 过期 QUEUEING 行由 3-2 {@code @Scheduled} 物理删兜底，故通常已无过期行（决策 D-3：读端点不加时间谓词，
     * 依赖 scanner + 前端接单 409 兜底）。V1 规模队列为个位数，全量扫无压力。
     */
    List<ConsultRequest> findByStateOrderByCreatedAtAsc(ConsultRequestState state);

    /**
     * 本兽医当前接单中请求（Story 3.6，只读）：取该兽医处于给定 {@code state}（ACCEPTED_AWAIT_PAY）的请求。
     * 兽医占用互斥（{@code goBusy}）保证接单中恒仅 1 单，故取首条即唯一。供 FR-53A「等待支付」倒计时中间态。
     */
    Optional<ConsultRequest> findFirstByVetIdAndState(long vetId, ConsultRequestState state);

    /** 该用户现有 live 请求（占用命中时返回，供 alreadyActive 语义）。 */
    Optional<ConsultRequest> findFirstByUserIdOrderByCreatedAtAsc(long userId);

    /**
     * 现金到账锚定（Story 3.4）：按 userId + state 取该用户唯一在途请求（占用不变量：一人一 live request）。
     * 供 {@code ConsultPaidHandler} 在 PaymentIntentPaidEvent 无 request ref 时反查待转单请求。
     */
    Optional<ConsultRequest> findFirstByUserIdAndState(long userId, ConsultRequestState state);

    /**
     * 入队超时静默删除（Story 3.2，@Scheduled 调）：物理删 {@code state=QUEUEING AND queue_deadline_at < now}
     * 的行（无痕、不建订单）。<b>state 谓词保护</b>：已接单（ACCEPTED_AWAIT_PAY）不被队列扫描删。返回删除行数。
     */
    @Modifying
    @Query("delete from ConsultRequest r "
            + "where r.state = com.tailtopia.consult.domain.ConsultRequestState.QUEUEING "
            + "and r.queueDeadlineAt < :now")
    int deleteExpiredQueueing(@Param("now") java.time.Instant now);

    /**
     * 兽医接单（CAS）：{@code QUEUEING → ACCEPTED_AWAIT_PAY} + 填 vet_id/pay_deadline。返回受影响行数：
     * 1=接单成功 / 0=已被他人抢或已删（先到先得）。
     */
    @Modifying
    @Query("update ConsultRequest r "
            + "set r.state = com.tailtopia.consult.domain.ConsultRequestState.ACCEPTED_AWAIT_PAY, "
            + "r.vetId = :vetId, r.payDeadlineAt = :payDeadline, r.updatedAt = CURRENT_TIMESTAMP "
            + "where r.id = :id "
            + "and r.state = com.tailtopia.consult.domain.ConsultRequestState.QUEUEING")
    int tryAccept(@Param("id") long id, @Param("vetId") long vetId,
            @Param("payDeadline") Instant payDeadline);

    /**
     * 取消/入队超时（CAS）：仅当仍 {@code QUEUEING} 时删行（防删掉已被接单的行）。返回删除行数（1=删成 / 0=已变态）。
     */
    @Modifying
    @Query("delete from ConsultRequest r where r.id = :id "
            + "and r.state = com.tailtopia.consult.domain.ConsultRequestState.QUEUEING")
    int deleteIfQueueing(@Param("id") long id);

    /**
     * 通用条件删（CAS）：仅当处于给定 {@code state} 时删行（供支付超时删 ACCEPTED_AWAIT_PAY、或支付成功转单后删）。
     */
    @Modifying
    @Query("delete from ConsultRequest r where r.id = :id and r.state = :state")
    int deleteIfState(@Param("id") long id, @Param("state") ConsultRequestState state);

    /**
     * 延长排队 deadline（CAS，bug 20260720-311）：仅当仍 {@code QUEUEING} 时把 {@code queue_deadline_at} 顺延。
     * 返回行数（1=延成 / 0=已变态/已删，防延到已接单/已删的行）。
     */
    @Modifying
    @Query("update ConsultRequest r set r.queueDeadlineAt = :newDeadline, r.updatedAt = CURRENT_TIMESTAMP "
            + "where r.id = :id "
            + "and r.state = com.tailtopia.consult.domain.ConsultRequestState.QUEUEING")
    int extendQueueDeadlineIfQueueing(@Param("id") long id, @Param("newDeadline") Instant newDeadline);

    /**
     * 支付窗过期接单扫描（Story 3.3，@Scheduled 调）：查处于给定 {@code state} 且 {@code pay_deadline_at} 已过期
     * <b>且未暂停</b>（Story 3.4：跳充值暂停中 {@code paused_at IS NOT NULL} 不判超时，防暂停被扫走）的行，
     * 供逐条 CAS 回退（需先读出以拿 {@code vet_id} 释放兽医）。返回 List（数量少，通常个位数）。
     */
    List<ConsultRequest> findByStateAndPayDeadlineAtBeforeAndPausedAtIsNull(ConsultRequestState state,
            Instant payDeadlineBefore);

    /**
     * 跳充值暂停（Story 3.4，A-4，CAS）：仅 {@code ACCEPTED_AWAIT_PAY} 且未暂停时记 {@code paused_at}。
     * 返回行数（1=暂停成功 / 0=状态不符或已暂停）。暂停期间支付窗扫描跳过（服务端权威计时）。
     */
    @Modifying
    @Query("update ConsultRequest r set r.pausedAt = :now, r.updatedAt = CURRENT_TIMESTAMP "
            + "where r.id = :id "
            + "and r.state = com.tailtopia.consult.domain.ConsultRequestState.ACCEPTED_AWAIT_PAY "
            + "and r.pausedAt is null")
    int pauseAcceptance(@Param("id") long id, @Param("now") Instant now);

    /**
     * 跳充值返回续（Story 3.4，A-4，CAS）：清 {@code paused_at} + 按剩余时间顺延 {@code pay_deadline_at}
     * （非重置，调用方算 newDeadline=now+剩余）。返回行数（1=续成功 / 0=未暂停）。
     */
    @Modifying
    @Query("update ConsultRequest r set r.pausedAt = null, r.payDeadlineAt = :newDeadline, "
            + "r.updatedAt = CURRENT_TIMESTAMP "
            + "where r.id = :id "
            + "and r.state = com.tailtopia.consult.domain.ConsultRequestState.ACCEPTED_AWAIT_PAY "
            + "and r.pausedAt is not null")
    int resumeAcceptance(@Param("id") long id, @Param("newDeadline") Instant newDeadline);

    /**
     * 支付窗超时回退（CAS，Story 3.3）：{@code ACCEPTED_AWAIT_PAY → QUEUEING}，清 vet_id/pay_deadline、
     * {@code rebroadcast_count++}、置新 {@code queue_deadline}（重开入队窗）。返回受影响行数（1=回退成功 / 0=已被
     * 其它路径处理）。<b>H-4 单列 state CAS</b>：state 谓词判定归属；{@code pay_deadline_at < now} 仅作过期过滤
     * （防回退掉 SELECT 后已被重新接单、pay_deadline 已刷新的行）。已支付（3-4 转订单删 request）行不匹配 → 0。
     */
    @Modifying
    @Query("update ConsultRequest r "
            + "set r.state = com.tailtopia.consult.domain.ConsultRequestState.QUEUEING, "
            + "r.vetId = null, r.payDeadlineAt = null, "
            + "r.rebroadcastCount = r.rebroadcastCount + 1, "
            + "r.queueDeadlineAt = :newQueueDeadline, r.updatedAt = CURRENT_TIMESTAMP "
            + "where r.id = :id "
            + "and r.state = com.tailtopia.consult.domain.ConsultRequestState.ACCEPTED_AWAIT_PAY "
            + "and r.payDeadlineAt < :now")
    int revertExpiredAcceptance(@Param("id") long id, @Param("now") Instant now,
            @Param("newQueueDeadline") Instant newQueueDeadline);
}
