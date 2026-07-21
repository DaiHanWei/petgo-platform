package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1（需 Docker postgres+redis）。Story 3.3 兽医接单与限时支付窗。启动即验 V66 契约（validate）。
 *
 * <p>核心：接单 CAS（H-4 单列 state，并发恰 1 兽医胜）+ 开 5min 支付窗（pay_deadline=+300s，服务端权威）+
 * goBusy 占用互斥；<b>接单不建 consult_orders</b>（A-5 红线）；支付窗超时 → 回退 QUEUEING 重播 + 释放兽医。
 */
class ConsultAcceptIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private ConsultRequestService requestService;
    @Autowired
    private VetPresenceService presence;
    @Autowired
    private VetTestSupport vets;

    private ConsultRequest seedQueueing() {
        long userId = newUser().getId();
        return requests.save(ConsultRequest.queue(userId, 1L,
                "req-" + SEQ.incrementAndGet(), Instant.now().plus(Duration.ofMinutes(1))));
    }

    // ---- AC1/AC2：接单成功（CAS + 支付窗 + goBusy），接单不建订单 ----

    @Test
    void vetAcceptTransitionsToAwaitPayAndOpensPayWindow() throws Exception {
        ConsultRequest req = seedQueueing();
        VetAccount vet = vets.newActiveVet("接单医生");
        long ordersBefore = orders.count();
        Instant before = Instant.now();

        mvc.perform(post("/api/v1/vet/consultations/{token}/accept", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestToken").value(req.getRequestToken()))
                .andExpect(jsonPath("$.state").value("ACCEPTED_AWAIT_PAY"))
                .andExpect(jsonPath("$.payDeadlineAt").isNotEmpty());

        ConsultRequest after = requests.findById(req.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);
        assertThat(after.getVetId()).isEqualTo(vet.getId());
        // 支付窗服务端权威：pay_deadline ≈ 接单时刻 + 300s（5min）。
        assertThat(after.getPayDeadlineAt()).isBetween(
                before.plus(Duration.ofSeconds(290)), before.plus(Duration.ofSeconds(310)));
        assertThat(presence.isBusy(vet.getId())).isTrue();     // 接单占用兽医
        assertThat(orders.count()).isEqualTo(ordersBefore);    // 接单绝不建订单（A-5 红线）
    }

    // ---- AC1：并发两兽医 accept 同一请求恰 1 成功（H-4），另一 409 ----

    @Test
    void concurrentAcceptExactlyOneVetWins() throws Exception {
        ConsultRequest req = seedQueueing();
        long vetA = vets.newActiveVet("竞争医生A").getId();
        long vetB = vets.newActiveVet("竞争医生B").getId();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (long vetId : new long[] {vetA, vetB}) {
            pool.submit(() -> {
                try {
                    start.await();
                    requestService.acceptRequest(vetId, req.getRequestToken());
                    wins.incrementAndGet();
                } catch (AppException e) {
                    conflicts.incrementAndGet(); // 竞争落败 → 409
                } catch (Exception ignored) {
                    // 其它并发异常不计入胜
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(wins.get()).isEqualTo(1);      // 先到先得，恰一兽医胜
        assertThat(conflicts.get()).isEqualTo(1);  // 另一 409
        ConsultRequest after = requests.findById(req.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);
        // 仅胜者被置 BUSY。
        assertThat(presence.isBusy(after.getVetId())).isTrue();
    }

    // ---- AC1：占用互斥（BUSY 兽医不能接新单）----

    @Test
    void busyVetCannotAcceptAnotherRequest() {
        long vetId = vets.newActiveVet("占用医生").getId();
        presence.goBusy(vetId); // 模拟已在进行中的接单
        ConsultRequest req = seedQueueing();

        assertThatConflict(() -> requestService.acceptRequest(vetId, req.getRequestToken()));
        // 请求仍 QUEUEING（未被 BUSY 兽医接走）。
        assertThat(requests.findById(req.getId()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.QUEUEING);
    }

    // ---- AC1/AC4：token 不存在 → 409（与「已被接单」同码防枚举）----

    @Test
    void unknownTokenConflictsForEnumerationDefense() {
        long vetId = vets.newActiveVet("防枚举医生").getId();
        assertThatConflict(() -> requestService.acceptRequest(vetId, "req-nonexistent-xyz"));
    }

    // ---- AC4：非 VET 角色 403、缺 JWT 401 ----

    @Test
    void nonVetForbiddenAndAnonymousUnauthorized() throws Exception {
        ConsultRequest req = seedQueueing();
        long userId = newUser().getId();
        // USER token 打兽医端点 → 403（hasRole VET 门控）。
        mvc.perform(post("/api/v1/vet/consultations/{token}/accept", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isForbidden());
        // 缺 JWT → 401。
        mvc.perform(post("/api/v1/vet/consultations/{token}/accept", req.getRequestToken()))
                .andExpect(status().isUnauthorized());
    }

    // ---- AC3：支付窗超时 → 结束请求（删）+ 释放兽医（bug 20260720-311，反转原回队重播 UX-DR14）----

    @Test
    void expiredPayWindowEndsRequestAndReleasesVet() {
        long vetId = vets.newActiveVet("超时释放医生").getId();
        // seed 已接单且支付窗已过期的行：先接单，再把 pay_deadline 拨回过去。
        ConsultRequest req = seedQueueing();
        requestService.acceptRequest(vetId, req.getRequestToken());
        // 直接 CAS 造过期支付窗（pay_deadline < now），并保持 BUSY。
        expirePayWindow(req.getId());

        int ended = requestService.endExpiredAcceptances();

        assertThat(ended).isGreaterThanOrEqualTo(1);
        assertThat(requests.findById(req.getId())).isEmpty(); // 请求已删（结束，不回队；落 failed(TIMEOUT)）
        assertThat(presence.isBusy(vetId)).isFalse();          // 兽医已释放
    }

    @Test
    void unexpiredAcceptanceAndQueueingUnaffectedByEnd() {
        long vetId = vets.newActiveVet("未过期医生").getId();
        // 未过期 ACCEPTED_AWAIT_PAY（pay_deadline 在未来）→ 不结束。
        ConsultRequest accepted = seedQueueing();
        requestService.acceptRequest(vetId, accepted.getRequestToken());
        // 独立 QUEUEING 行 → 不受支付窗扫描影响（state 谓词）。
        ConsultRequest queueing = seedQueueing();

        requestService.endExpiredAcceptances();

        assertThat(requests.findById(accepted.getId()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY); // 未过期不结束
        assertThat(presence.isBusy(vetId)).isTrue();                // 兽医仍占用
        assertThat(requests.findById(queueing.getId()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.QUEUEING);           // QUEUEING 不受影响
    }

    /** 用 CAS 直接把某已接单请求的 pay_deadline 拨到过去（模拟支付窗超时），state 仍 ACCEPTED_AWAIT_PAY。 */
    private void expirePayWindow(long id) {
        ConsultRequest r = requests.findById(id).orElseThrow();
        // 借 tryAccept 无法回拨 deadline；直接改实体保存（该行 vet_id 已在，state 不变）。
        org.springframework.test.util.ReflectionTestUtils.setField(r, "payDeadlineAt",
                Instant.now().minus(Duration.ofSeconds(10)));
        requests.save(r);
    }

    private static void assertThatConflict(Runnable action) {
        try {
            action.run();
            org.assertj.core.api.Assertions.fail("expected 409 conflict");
        } catch (AppException e) {
            assertThat(e.getStatus().value()).isEqualTo(409);
        }
    }
}
