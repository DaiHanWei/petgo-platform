package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import java.time.Instant;
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

    /** 该用户现有 live 请求（占用命中时返回，供 alreadyActive 语义）。 */
    Optional<ConsultRequest> findFirstByUserIdOrderByCreatedAtAsc(long userId);

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
}
