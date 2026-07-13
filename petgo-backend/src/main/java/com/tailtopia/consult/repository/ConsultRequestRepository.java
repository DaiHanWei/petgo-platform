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
