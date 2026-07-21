package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.dto.VetPayoutAggregate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 咨询订单仓储（Story 3.1，支付成功建的持久订单）。 */
public interface ConsultOrderRepository extends JpaRepository<ConsultOrder, Long> {

    Optional<ConsultOrder> findByOrderToken(String orderToken);

    /** 兽医历史「到手金额」（V88）：按 (vetId, 会话 id 批) 批量取订单，service 建 sessionId→order Map 避免 N+1。 */
    List<ConsultOrder> findByVetIdAndConsultSessionIdIn(long vetId, java.util.Collection<Long> consultSessionIds);

    /** 订单中心游标分页（Story 5.1）：本人订单 created_at < cursor 倒序（全 4 态入订单中心）。 */
    List<ConsultOrder> findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, Instant cursor, org.springframework.data.domain.Pageable pageable);

    /**
     * 退款起步 CAS（Story 3.8，H-5 退款幂等唯一闸）：{@code IN_PROGRESS→REFUNDING}。返回行数（1=本路胜、可退款；
     * 0=已被另一路处理，<b>不重复退</b>）。scanner 与用户逃生并发只一方拿到 1（行锁串行化）。
     */
    @Modifying
    @Query("update ConsultOrder o "
            + "set o.status = com.tailtopia.consult.domain.ConsultOrderStatus.REFUNDING, "
            + "o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id "
            + "and o.status = com.tailtopia.consult.domain.ConsultOrderStatus.IN_PROGRESS")
    int markRefunding(@Param("id") long id);

    /**
     * 客服批准退款需求 CAS（Story 4.4，AB-5B）：{@code COMPLETED→REFUNDING}。返回行数（1=已转、可进选方式；
     * 0=订单已非 COMPLETED，幂等跳过不报错）。<b>与 3-8 的 {@link #markRefunding}(IN_PROGRESS→REFUNDING) 两条独立 CAS，
     * 互不影响</b>：退款工单针对已交付完成订单，批准后进 REFUNDING 等用户填收款（4-5）→ 主管审批 + 财务打款（4-6）→ REFUNDED。
     */
    @Modifying
    @Query("update ConsultOrder o "
            + "set o.status = com.tailtopia.consult.domain.ConsultOrderStatus.REFUNDING, "
            + "o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id "
            + "and o.status = com.tailtopia.consult.domain.ConsultOrderStatus.COMPLETED")
    int markRefundingFromCompleted(@Param("id") long id);

    /**
     * 客服驳回退款需求 CAS（Story 4.4，A-2 UX 不撒谎）：订单<b>保持/回落 {@code COMPLETED}</b> 并置
     * {@code refund_rejected=true}（标记「申请过但未通过」，不再假装在退款）。返回行数（1=已标记；0=订单非 COMPLETED，跳过）。
     * <b>不新增订单终态枚举</b>（架构 A-2）。
     */
    @Modifying
    @Query("update ConsultOrder o "
            + "set o.refundRejected = true, "
            + "o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id "
            + "and o.status = com.tailtopia.consult.domain.ConsultOrderStatus.COMPLETED")
    int markRefundRejected(@Param("id") long id);

    /**
     * 主管驳回退款回落 CAS（Story 4.6，A-2 UX 不撒谎）：{@code REFUNDING→COMPLETED} 并置 {@code refund_rejected=true}
     * （用户已填收款进第二段，主管驳回 → 订单退回已完成态、标记申请过但未通过）。返回行数（1=已回落；0=订单非 REFUNDING，跳过）。
     * <b>与 4-4 的 {@link #markRefundRejected}(COMPLETED→COMPLETED) 是不同源态两条 CAS</b>：4-4 客服驳回时订单还是 COMPLETED；本条主管驳回时订单已 REFUNDING。
     */
    @Modifying
    @Query("update ConsultOrder o "
            + "set o.status = com.tailtopia.consult.domain.ConsultOrderStatus.COMPLETED, "
            + "o.refundRejected = true, "
            + "o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id "
            + "and o.status = com.tailtopia.consult.domain.ConsultOrderStatus.REFUNDING")
    int markRefundRejectedFromRefunding(@Param("id") long id);

    /**
     * 退款完成 CAS（Story 3.8，PawCoin 立即到账后）：{@code REFUNDING→REFUNDED}。返回行数（1=完成）。
     * QRIS 留 REFUNDING（实际 Midtrans 打款由 Epic 4 完成 → REFUNDED）。
     */
    @Modifying
    @Query("update ConsultOrder o "
            + "set o.status = com.tailtopia.consult.domain.ConsultOrderStatus.REFUNDED, "
            + "o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id "
            + "and o.status = com.tailtopia.consult.domain.ConsultOrderStatus.REFUNDING")
    int markRefunded(@Param("id") long id);

    /**
     * 会话完成定位待完成订单（Story 3.7）：按 {@code (user_id, vet_id, status)} 取。占用不变量（一人一时仅 1
     * 活跃 consult）保证 IN_PROGRESS 至多 1 单。免费直连流会话无订单 → empty（调用方跳过）。
     *
     * @deprecated bug 20260721-324：该「一人一时一活跃」不变量不成立（V1.0 免费直连流会话不进 consult_requests
     *     占用校验），松匹配会把滞留的已付款单被同一 (user,vet) 的另一场会话收尾误标完成。改用
     *     {@link #findByConsultSessionIdAndStatus} 按会话自身精确定位。
     */
    @Deprecated
    Optional<ConsultOrder> findFirstByUserIdAndVetIdAndStatus(long userId, long vetId,
            ConsultOrderStatus status);

    /**
     * 会话完成/中断/退款定位订单（bug 20260721-324）：按订单自身的 {@code consult_session_id} 精确取，
     * 彻底解耦「哪场会话收尾」与「完成哪笔订单」。会话开始时 {@code markSessionStarted} 已回填该列。
     */
    Optional<ConsultOrder> findByConsultSessionIdAndStatus(long consultSessionId,
            ConsultOrderStatus status);

    /**
     * 月结聚合（Story 3.7）：区间 {@code [start, end)} 内 <b>COMPLETED</b> 订单按 {@code vet_id} 分组聚合
     * （单数 / 成交额合计 / 到手合计）。<b>仅 COMPLETED</b>（REFUNDING/REFUNDED 排除——退款不给分成）；
     * 归月按 {@code session_ended_at}（会话完成时点）。{@code COALESCE} 兜底 null（付费单 vet_payout 恒非 null，防御）。
     */
    @Query("""
            select new com.tailtopia.consult.dto.VetPayoutAggregate(
                o.vetId, count(o), coalesce(sum(o.amount), 0L), coalesce(sum(o.vetPayout), 0L))
            from ConsultOrder o
            where o.status = com.tailtopia.consult.domain.ConsultOrderStatus.COMPLETED
              and o.sessionEndedAt >= :start and o.sessionEndedAt < :end
            group by o.vetId""")
    List<VetPayoutAggregate> aggregateCompletedByVet(@Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * 单兽医区间到手聚合（Story 3.7，收入页「当月待结算」实时聚合）：某兽医 {@code [start, end)} 内 COMPLETED
     * 订单聚合，无则 empty（调用方兜底零值）。
     */
    @Query("""
            select new com.tailtopia.consult.dto.VetPayoutAggregate(
                o.vetId, count(o), coalesce(sum(o.amount), 0L), coalesce(sum(o.vetPayout), 0L))
            from ConsultOrder o
            where o.vetId = :vetId
              and o.status = com.tailtopia.consult.domain.ConsultOrderStatus.COMPLETED
              and o.sessionEndedAt >= :start and o.sessionEndedAt < :end
            group by o.vetId""")
    Optional<VetPayoutAggregate> aggregateCompletedForVet(@Param("vetId") long vetId,
            @Param("start") Instant start, @Param("end") Instant end);
}
