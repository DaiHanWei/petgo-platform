package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.ConsultStageEvent;
import com.tailtopia.consult.domain.VetDiagnosis;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultOrderStageEventRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultCloseService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1（需 Docker postgres+redis）。Story 3.7 会话完成 → 付费订单 IN_PROGRESS→COMPLETED（补 3-4 缺口，D-1）。
 *
 * <p>核心：付费会话 CLOSED（评分/30min 超时）→ 同事务把 {@code consult_orders} 置 COMPLETED + session_ended_at +
 * 追加 SESSION_ENDED 节点；免费直连流会话（无订单）跳过不报错；退款中订单不被误置 COMPLETED（幂等守卫）。
 */
class ConsultOrderCompletionIntegrationTest extends ApiIntegrationTest {

    private static final VetDiagnosis DIAGNOSIS =
            new VetDiagnosis("Gastritis akut ringan", null, false, null, null, null, null, null);

    @Autowired
    private ConsultCloseService closeService;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private ConsultOrderStageEventRepository stageEvents;

    /** 建付费会话（PENDING_CLOSE）+ 对应 IN_PROGRESS 订单，返回 [userId, vetId, sessionId, orderId]。 */
    private long[] seedPaidPendingClose() {
        long userId = newUser().getId();
        long vetId = 5000L + SEQ.incrementAndGet();
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.attachImConversation("conv-" + SEQ.incrementAndGet());
        s.recordDiagnosis(DIAGNOSIS);
        s.endByVet(); // IN_PROGRESS → PENDING_CLOSE
        s = sessions.save(s);
        ConsultOrder o = ConsultOrder.inProgress("ord-" + SEQ.incrementAndGet(), userId,
                vetId, 1L, 50000L, PayChannel.PAWCOIN, null, 30000L, 60, 50000L, Instant.now());
        // 贴合真实流程：会话开始时回填 consult_session_id（bug 324 后按此列精确定位订单）。
        o.markSessionStarted(Instant.now(), s.getId());
        o = orders.save(o);
        return new long[] {userId, vetId, s.getId(), o.getId()};
    }

    // ---- AC1：评分关闭 → 订单 COMPLETED + session_ended_at + SESSION_ENDED 节点 ----

    @Test
    void ratingCloseCompletesPaidOrder() {
        long[] ids = seedPaidPendingClose();
        long userId = ids[0], orderId = ids[3];

        closeService.submitRating(userId, ids[2], 5, "mantap");

        ConsultOrder after = orders.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ConsultOrderStatus.COMPLETED);
        assertThat(after.getSessionEndedAt()).isNotNull();
        assertThat(stageEvents.findByConsultOrderIdOrderByOccurredAtAsc(orderId))
                .anyMatch(e -> e.getEventType() == ConsultStageEvent.SESSION_ENDED);
    }

    // ---- AC1：30min 评分门超时关闭 → 订单 COMPLETED ----

    @Test
    void gateTimeoutCloseCompletesPaidOrder() {
        long[] ids = seedPaidPendingClose();
        long orderId = ids[3];
        // 把 pending_close 起点拨到 31min 前 → 超时门命中。
        ConsultSession s = sessions.findById(ids[2]).orElseThrow();
        ReflectionTestUtils.setField(s, "pendingCloseStartedAt",
                Instant.now().minus(Duration.ofMinutes(31)));
        sessions.save(s);

        int closed = closeService.closeExpiredGates();

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.COMPLETED);
    }

    // ---- AC1：免费直连流会话（无订单）关闭 → 不报错、不建单 ----

    @Test
    void freeSessionCloseNoOrderNoError() {
        long userId = newUser().getId();
        long vetId = 6000L + SEQ.incrementAndGet();
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.attachImConversation("conv-free-" + SEQ.incrementAndGet());
        s.recordDiagnosis(DIAGNOSIS);
        s.endByVet();
        s = sessions.save(s);

        closeService.submitRating(userId, s.getId(), 4, null); // 无订单 → 应静默完成

        assertThat(orders.findFirstByUserIdAndVetIdAndStatus(userId, vetId,
                ConsultOrderStatus.COMPLETED)).isEmpty();
        assertThat(orders.findFirstByUserIdAndVetIdAndStatus(userId, vetId,
                ConsultOrderStatus.IN_PROGRESS)).isEmpty();
    }

    // ---- bug 20260721-324：另一场会话收尾不得完成本会话的滞留付款单（松匹配→按 session_id 精确）----

    @Test
    void otherSessionCloseDoesNotCompleteThisPaidOrder() {
        long[] a = seedPaidPendingClose(); // 会话A + 付款单A（IN_PROGRESS，consult_session_id=A）
        long userId = a[0], vetId = a[1], orderIdA = a[3];

        // 同一 (user, vet) 的第二场会话 B（免费直连流，无订单），走完收尾。
        ConsultSession b = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        b.markInProgress(vetId);
        b.attachImConversation("conv-b-" + SEQ.incrementAndGet());
        b.recordDiagnosis(DIAGNOSIS);
        b.endByVet(); // → PENDING_CLOSE
        b = sessions.save(b);

        closeService.submitRating(userId, b.getId(), 5, null); // 关闭会话 B

        // 修复前：松匹配 (user,vet,IN_PROGRESS) → 订单A被误标 COMPLETED（断言失败）。
        // 修复后：按 consult_session_id 精确 → 会话B无订单 → 订单A仍 IN_PROGRESS。
        assertThat(orders.findById(orderIdA).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.IN_PROGRESS);
    }

    // ---- AC1：退款中订单不被误置 COMPLETED（幂等守卫，只 IN_PROGRESS 可转）----

    @Test
    void refundingOrderNotCompleted() {
        long[] ids = seedPaidPendingClose();
        long orderId = ids[3];
        ConsultOrder o = orders.findById(orderId).orElseThrow();
        ReflectionTestUtils.setField(o, "status", ConsultOrderStatus.REFUNDING);
        orders.save(o);

        closeService.submitRating(ids[0], ids[2], 5, null);

        // 退款中订单不匹配 IN_PROGRESS 谓词 → 保持 REFUNDING，不被完成。
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDING);
    }
}
