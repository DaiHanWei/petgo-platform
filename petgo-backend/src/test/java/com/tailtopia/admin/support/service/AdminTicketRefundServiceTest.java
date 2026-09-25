package com.tailtopia.admin.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.support.domain.ContactType;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.RelatedOrderType;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：工单关联订单与退款判定（Story 3-2 / AD-S7）。
 *
 * <p>🔴 <b>本类是本 story 的主护栏</b>。它守的东西不是「功能对不对」，是
 * <b>「电商订单会不会把退款开到一条不相干的问诊单头上」</b> ——
 * {@code consult_orders.id} 与 {@code shop_orders.id} 都是从 1 开始的自增 bigint，
 * 数值空间完全重叠，所以「挂电商单 42 → 给问诊单 42 建退款请求」是
 * {@code findById} 一行代码的必然结果，不是理论风险。
 *
 * <p>🎯 <b>变异靶子</b>：{@link RefundGuard#shopTicketCannotEnterRefundApproval()} 与
 * {@link RefundGuard#mutationTarget_removingTypeGuardWouldRefundAnUnrelatedConsultOrder()}
 * 必须在删掉 {@code ensureRefundRequest} 入口的类型守卫后变红。
 *
 * <p>⚠️ 本类之前**不存在** —— 既有的 {@code AdminSupportAccessControlTest} 只测
 * {@code @PreAuthorize} 闸门，这一整块业务逻辑此前零单测覆盖。
 */
class AdminTicketRefundServiceTest {

    /** 🔴 两类订单**故意同号**：撞号正是本 story 要防的东西。 */
    private static final long COLLIDING_ID = 42L;

    /**
     * 🔴 <b>刻意取一个落在 Long 缓存区间 [-128,127] 之外的值</b>。
     *
     * <p>归属校验比的是两个 {@code Long} 对象，写成 {@code !=} 时在小 id 下**恰好能通过**
     * （JVM 缓存了同一个 Long 实例），一换成真实 userId 就恒判「不是你的」——
     * 本人的订单永远挂不上，而单测全绿。用 7L 会把这个 bug 放过去。
     */
    private static final long USER = 9_000_001L;

    private static final long ADMIN = 1L;

    private FeedbackTicketRepository tickets;
    private ConsultOrderRepository consultOrders;
    private ShopOrderRepository shopOrders;
    private RefundRequestRepository refunds;
    private RefundService refundService;
    private AdminAuditService audit;
    private AdminTicketRefundService svc;

    @BeforeEach
    void setUp() {
        tickets = mock(FeedbackTicketRepository.class);
        consultOrders = mock(ConsultOrderRepository.class);
        shopOrders = mock(ShopOrderRepository.class);
        refunds = mock(RefundRequestRepository.class);
        refundService = mock(RefundService.class);
        audit = mock(AdminAuditService.class);
        svc = new AdminTicketRefundService(tickets, consultOrders, shopOrders, refunds,
                refundService, audit);
    }

    // ---------- 造数 ----------

    private FeedbackTicket openTicket() {
        FeedbackTicket t = FeedbackTicket.create(USER, "tk-1", "主题", "正文",
                ContactType.WHATSAPP, "0812", true, null);
        ReflectionTestUtils.setField(t, "id", 100L);
        lenient().when(tickets.findByTicketToken("tk-1")).thenReturn(Optional.of(t));
        return t;
    }

    private ConsultOrder consultOrder(long id, long userId) {
        ConsultOrder o = BeanUtils.instantiateClass(ConsultOrder.class);
        ReflectionTestUtils.setField(o, "id", id);
        ReflectionTestUtils.setField(o, "userId", userId);
        ReflectionTestUtils.setField(o, "orderToken", "consult-tok-" + id);
        return o;
    }

    private ShopOrder shopOrder(long id, long userId) {
        ShopOrder o = BeanUtils.instantiateClass(ShopOrder.class);
        ReflectionTestUtils.setField(o, "id", id);
        ReflectionTestUtils.setField(o, "userId", userId);
        ReflectionTestUtils.setField(o, "publicToken", "shop-tok-" + id);
        return o;
    }

    // ---------- link 两支 ----------

    @Test
    @DisplayName("挂问诊单 → 写 id + type=CONSULT，审计 summary 带类型")
    void linkConsultOrderWritesBothIdAndType() {
        FeedbackTicket t = openTicket();
        when(consultOrders.findByOrderToken("consult-tok-42"))
                .thenReturn(Optional.of(consultOrder(COLLIDING_ID, USER)));

        svc.linkConsultOrder("tk-1", "consult-tok-42", ADMIN);

        assertThat(t.getRelatedOrderId()).isEqualTo(COLLIDING_ID);
        assertThat(t.getRelatedOrderType()).isEqualTo(RelatedOrderType.CONSULT);
        verify(audit).record(anyLong(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.contains("type=CONSULT"));
    }

    @Test
    @DisplayName("🔴 挂电商单 → 写 id + type=SHOP，且**完全不碰退款**")
    void linkShopOrderWritesBothIdAndTypeAndNeverTouchesRefunds() {
        FeedbackTicket t = openTicket();
        when(shopOrders.findByPublicToken("shop-tok-42"))
                .thenReturn(Optional.of(shopOrder(COLLIDING_ID, USER)));

        svc.linkShopOrder("tk-1", "shop-tok-42", ADMIN);

        assertThat(t.getRelatedOrderId()).isEqualTo(COLLIDING_ID);
        assertThat(t.getRelatedOrderType()).isEqualTo(RelatedOrderType.SHOP);
        // 第一层守卫（编译期就断开）的运行期体现：挂电商单不产生任何退款副作用。
        verify(refundService, never()).createRefundRequest(anyString(), anyLong(), anyLong());
        verify(refunds, never()).findByOrderId(anyLong());
    }

    @Test
    @DisplayName("订单不属于工单用户 → 422（两支同口径）")
    void notOwnedIsRejectedOnBothPaths() {
        openTicket();
        when(consultOrders.findByOrderToken("consult-tok-42"))
                .thenReturn(Optional.of(consultOrder(COLLIDING_ID, USER + 1)));
        when(shopOrders.findByPublicToken("shop-tok-42"))
                .thenReturn(Optional.of(shopOrder(COLLIDING_ID, USER + 1)));

        assertThatThrownBy(() -> svc.linkConsultOrder("tk-1", "consult-tok-42", ADMIN))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> svc.linkShopOrder("tk-1", "shop-tok-42", ADMIN))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("订单不存在 → 404（两支同口径）")
    void missingOrderIsNotFoundOnBothPaths() {
        openTicket();
        when(consultOrders.findByOrderToken(anyString())).thenReturn(Optional.empty());
        when(shopOrders.findByPublicToken(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.linkConsultOrder("tk-1", "nope", ADMIN))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> svc.linkShopOrder("tk-1", "nope", ADMIN))
                .isInstanceOf(AppException.class);
    }

    // ---------- APPROVED 改挂守卫 ----------

    @Nested
    @DisplayName("APPROVED 改挂守卫（PR#34 finding #5）")
    class RelinkGuard {

        @Test
        @DisplayName("🔴 挂着已批准退款的问诊单时，改挂电商单同样被禁（不能靠换类型绕过）")
        void cannotEscapeTheGuardBySwitchingToAShopOrder() {
            FeedbackTicket t = openTicket();
            t.linkConsultOrder(99L);
            when(refunds.findByOrderId(99L)).thenReturn(Optional.of(approvedRefund()));
            when(shopOrders.findByPublicToken("shop-tok-42"))
                    .thenReturn(Optional.of(shopOrder(COLLIDING_ID, USER)));

            assertThatThrownBy(() -> svc.linkShopOrder("tk-1", "shop-tok-42", ADMIN))
                    .isInstanceOf(AppException.class);
            assertThat(t.getRelatedOrderType()).isEqualTo(RelatedOrderType.CONSULT);
        }

        @Test
        @DisplayName("🔴 当前挂的是电商单时**不去查退款表** —— 查了只会查出同号问诊单的退款")
        void doesNotQueryRefundsWhenCurrentlyLinkedToAShopOrder() {
            FeedbackTicket t = openTicket();
            t.linkShopOrder(COLLIDING_ID);
            when(consultOrders.findByOrderToken("consult-tok-77"))
                    .thenReturn(Optional.of(consultOrder(77L, USER)));

            svc.linkConsultOrder("tk-1", "consult-tok-77", ADMIN);

            // 无条件查会命中同号问诊单的 APPROVED 退款，于是「挂着电商单的工单，
            // 因为某个不相干问诊单有退款而被禁止改挂」—— 症状很怪、很难查。
            verify(refunds, never()).findByOrderId(COLLIDING_ID);
            assertThat(t.getRelatedOrderId()).isEqualTo(77L);
            assertThat(t.getRelatedOrderType()).isEqualTo(RelatedOrderType.CONSULT);
        }

        private com.tailtopia.pay.refund.domain.RefundRequest approvedRefund() {
            var r = BeanUtils.instantiateClass(com.tailtopia.pay.refund.domain.RefundRequest.class);
            ReflectionTestUtils.setField(r, "needDecision",
                    com.tailtopia.pay.refund.domain.NeedDecision.APPROVED);
            return r;
        }
    }

    // ---------- 🎯 退款类型守卫（变异靶子） ----------

    @Nested
    @DisplayName("🎯 退款类型守卫 —— 本 story 唯一真正的护栏")
    class RefundGuard {

        @Test
        @DisplayName("🎯 变异靶子：SHOP 工单点退款判定 → 422，不进 ensureRefundRequest")
        void shopTicketCannotEnterRefundApproval() {
            FeedbackTicket t = openTicket();
            t.linkShopOrder(COLLIDING_ID);

            // 🔴 **必须断言到具体 code**：只断 "throws AppException" 是假绿 ——
            //    删掉守卫后这里照样抛，只不过抛的是下游的「关联订单不存在」
            //    （mock 的 consultOrders 查不到同号问诊单），用例会继续通过。
            assertThatThrownBy(() -> svc.approveRefundNeed("tk-1", ADMIN))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getMessageCode())
                    .isEqualTo("admin.err.ticket.refundNotForShopOrder");
            assertThatThrownBy(() -> svc.rejectRefundNeed("tk-1", ADMIN, "原因"))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getMessageCode())
                    .isEqualTo("admin.err.ticket.refundNotForShopOrder");

            verify(refundService, never()).approveNeed(anyString(), anyLong());
            verify(refundService, never()).rejectNeed(anyString(), anyLong());
            verify(refundService, never()).createRefundRequest(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("🎯 变异靶子：删掉类型守卫就会给**同号的那条问诊单**建出退款请求")
        void mutationTarget_removingTypeGuardWouldRefundAnUnrelatedConsultOrder() {
            FeedbackTicket t = openTicket();
            // 工单挂的是电商单 42。
            t.linkShopOrder(COLLIDING_ID);
            // 库里恰好也有一条 id=42 的问诊单 —— 两表 id 空间重叠，这是常态不是巧合。
            ConsultOrder unrelated = consultOrder(COLLIDING_ID, USER + 99);
            lenient().when(consultOrders.findById(COLLIDING_ID)).thenReturn(Optional.of(unrelated));
            lenient().when(refunds.findByOrderId(COLLIDING_ID)).thenReturn(Optional.empty());
            lenient().when(refundService.createRefundRequest(anyString(), anyLong(), anyLong()))
                    .thenReturn("refund-tok");

            assertThatThrownBy(() -> svc.approveRefundNeed("tk-1", ADMIN))
                    .isInstanceOf(AppException.class);

            // 🔴 删掉守卫后，下面这条会变成「被调用了一次，参数是 consult-tok-42」——
            //    一条跟本工单毫无关系的问诊单被开了退款。这正是用例名说的那件事。
            verify(refundService, never())
                    .createRefundRequest(org.mockito.ArgumentMatchers.eq("consult-tok-42"),
                            anyLong(), anyLong());
            verify(consultOrders, never()).findById(COLLIDING_ID);
        }

        @Test
        @DisplayName("CONSULT 工单的退款路径逐条不变（本 story 不碰它）")
        void consultTicketStillGoesThroughRefundApproval() {
            FeedbackTicket t = openTicket();
            t.linkConsultOrder(COLLIDING_ID);
            when(refunds.findByOrderId(COLLIDING_ID)).thenReturn(Optional.empty());
            when(consultOrders.findById(COLLIDING_ID))
                    .thenReturn(Optional.of(consultOrder(COLLIDING_ID, USER)));
            when(refundService.createRefundRequest(any(), anyLong(), anyLong()))
                    .thenReturn("refund-tok");

            svc.approveRefundNeed("tk-1", ADMIN);

            verify(refundService).createRefundRequest("consult-tok-42", 100L, ADMIN);
            verify(refundService).approveNeed("refund-tok", ADMIN);
        }

        @Test
        @DisplayName("未关联订单 → 仍是「请先关联订单」那条 422（守卫位置在它之后）")
        void unlinkedTicketStillAsksToLinkFirst() {
            openTicket();

            assertThatThrownBy(() -> svc.approveRefundNeed("tk-1", ADMIN))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("请先关联订单");
        }
    }
}
