package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.consult.service.ConsultSuspensionService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1（需 Docker）。Story 3.8 封禁挂起逃生（安全攸关 H-5）。
 *
 * <p>核心：封禁付费会话→挂起（不即时中断）/ 免费会话→即时中断；强制结束按渠道退款（PawCoin 全额退+REFUNDED /
 * QRIS 留 REFUNDING）；15min scanner 强制结束；用户逃生提前结束；<b>退款幂等绝不双退</b>；非本人逃生 404。
 */
class ConsultSuspensionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultInterruptService interruptService;
    @Autowired
    private ConsultSuspensionService suspensionService;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private PawCoinWalletService wallet;

    @BeforeEach
    void clean() {
        // deleteAllInBatch：单条 DELETE，绕过 @Version 乐观锁（ConsultSession 有 version 列，deleteAll 会逐行版本校验）。
        orders.deleteAllInBatch();
        sessions.deleteAllInBatch();
    }

    /** 建付费会话（IN_PROGRESS）+ 对应 IN_PROGRESS 订单。返回 [userId, vetId, sessionId, orderId]。 */
    private long[] seedPaid(PayChannel channel) {
        long userId = newUser().getId();
        long vetId = 7000L + SEQ.incrementAndGet();
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.attachImConversation("conv-" + SEQ.incrementAndGet());
        s = sessions.save(s);
        ConsultOrder o = orders.save(ConsultOrder.inProgress("ord-" + SEQ.incrementAndGet(), userId,
                vetId, 1L, 50000L, channel, null, 30000L, 60, 50000L, Instant.now()));
        return new long[] {userId, vetId, s.getId(), o.getId()};
    }

    // ---- AC2：封禁付费会话 → 挂起（不即时中断）；免费会话 → 即时中断 ----

    @Test
    void banSuspendsPaidSessionInsteadOfInterrupt() {
        long[] ids = seedPaid(PayChannel.PAWCOIN);
        long vetId = ids[1], sessionId = ids[2];

        interruptService.interruptByVetBan(vetId);

        ConsultSession after = sessions.findById(sessionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS); // 未即时中断
        assertThat(after.getSuspendDeadlineAt()).isNotNull();               // 挂起中
        assertThat(after.getSuspendDeadlineAt()).isAfter(Instant.now());    // 15min 窗未过
    }

    @Test
    void banInterruptsFreeSessionImmediately() {
        long userId = newUser().getId();
        long vetId = 7100L + SEQ.incrementAndGet();
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.attachImConversation("conv-free-" + SEQ.incrementAndGet());
        s = sessions.save(s); // 无订单 = 免费会话

        interruptService.interruptByVetBan(vetId);

        assertThat(sessions.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.INTERRUPTED); // 即时中断（5.7 不变）
    }

    // ---- AC3：强制结束 PawCoin → 全额退回 + 订单 REFUNDED + 会话 INTERRUPTED ----

    @Test
    void forceEndPawCoinRefundsFully() {
        long[] ids = seedPaid(PayChannel.PAWCOIN);
        long userId = ids[0], sessionId = ids[2], orderId = ids[3];
        interruptService.interruptByVetBan(ids[1]); // 先挂起
        long balanceBefore = wallet.balanceOf(userId);

        suspensionService.forceEndSuspended(sessions.findById(sessionId).orElseThrow());

        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDED);
        assertThat(wallet.balanceOf(userId)).isEqualTo(balanceBefore + 50000L); // 全额退回
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.INTERRUPTED);
    }

    // ---- AC3：QRIS → 订单留 REFUNDING（实际打款 Epic 4）+ 会话 INTERRUPTED ----

    @Test
    void forceEndQrisMarksRefundingPending() {
        long[] ids = seedPaid(PayChannel.QRIS);
        long userId = ids[0], sessionId = ids[2], orderId = ids[3];
        interruptService.interruptByVetBan(ids[1]);
        long balanceBefore = wallet.balanceOf(userId);

        suspensionService.forceEndSuspended(sessions.findById(sessionId).orElseThrow());

        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDING); // 留 REFUNDING（Epic 4 打款）
        assertThat(wallet.balanceOf(userId)).isEqualTo(balanceBefore); // QRIS 不退 PawCoin
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.INTERRUPTED);
    }

    // ---- AC4：15min 挂起超时扫描强制结束 ----

    @Test
    void scanExpiredSuspensionForceEnds() {
        long[] ids = seedPaid(PayChannel.PAWCOIN);
        long userId = ids[0], sessionId = ids[2];
        interruptService.interruptByVetBan(ids[1]);
        // 挂起截止拨到过去（超 15min）。
        ConsultSession s = sessions.findById(sessionId).orElseThrow();
        ReflectionTestUtils.setField(s, "suspendDeadlineAt", Instant.now().minus(Duration.ofMinutes(1)));
        sessions.save(s);

        int handled = suspensionService.scanExpiredSuspensions();

        assertThat(handled).isGreaterThanOrEqualTo(1);
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(wallet.balanceOf(userId)).isEqualTo(50000L); // 已退款
    }

    // ---- AC5：用户逃生提前强制结束 + 退款 ----

    @Test
    void userEscapeImmediatelyForceEnds() {
        long[] ids = seedPaid(PayChannel.PAWCOIN);
        long userId = ids[0], sessionId = ids[2];
        interruptService.interruptByVetBan(ids[1]);

        suspensionService.escapeByUser(userId, sessionId);

        assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(wallet.balanceOf(userId)).isEqualTo(50000L);
    }

    // ---- AC3：幂等——重复强制结束不双退（订单 CAS 单点）----

    @Test
    void refundIdempotentNoDoubleRefund() {
        long[] ids = seedPaid(PayChannel.PAWCOIN);
        long userId = ids[0], sessionId = ids[2];
        interruptService.interruptByVetBan(ids[1]);

        suspensionService.forceEndSuspended(sessions.findById(sessionId).orElseThrow());
        // 第二次（模拟 scanner 与逃生并发的重复）：会话已 INTERRUPTED → 直接返回，不再退。
        suspensionService.forceEndSuspended(sessions.findById(sessionId).orElseThrow());

        assertThat(wallet.balanceOf(userId)).isEqualTo(50000L); // 只退一次
    }

    // ---- AC5：非本人逃生 → 404 ----

    @Test
    void escapeByNonOwnerNotFound() {
        long[] ids = seedPaid(PayChannel.PAWCOIN);
        interruptService.interruptByVetBan(ids[1]);
        long otherUser = newUser().getId();

        try {
            suspensionService.escapeByUser(otherUser, ids[2]);
            org.assertj.core.api.Assertions.fail("expected 404");
        } catch (AppException e) {
            assertThat(e.getStatus().value()).isEqualTo(404);
        }
    }
}
