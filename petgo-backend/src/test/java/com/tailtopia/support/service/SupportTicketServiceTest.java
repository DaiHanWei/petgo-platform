package com.tailtopia.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.RelatedOrderType;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketInternalNoteRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试：建工单前置校验（≤5 附件 / contact_type / label 非法 → 422），均在落库前抛，不触任何 repo。
 */
@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    @Mock private FeedbackTicketRepository tickets;
    @Mock private TicketAttachmentRepository attachments;
    @Mock private TicketLabelRepository labels;
    @Mock private TicketInternalNoteRepository internalNotes;
    @Mock private CardTokenGenerator tokenGenerator;
    @Mock private ConsultOrderRepository orders;
    @Mock private ShopOrderRepository shopOrders;
    @Mock private NotificationService notifications;
    @Mock private AdminAuditService audit;

    @InjectMocks private SupportTicketService service;

    @Test
    void createTicket_rejectsMoreThan5Attachments() {
        List<String> sixKeys = List.of("k1", "k2", "k3", "k4", "k5", "k6");
        assertThatThrownBy(() -> service.createTicket(1L, "主题", "正文",
                "EMAIL", "a@b.com", true, null, List.of("BUG"), sixKeys))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 超限即拒，绝不落库
        verifyNoInteractions(tickets, attachments, labels);
    }

    @Test
    void createTicket_rejectsInvalidContactType() {
        assertThatThrownBy(() -> service.createTicket(1L, "主题", "正文",
                "SMS", "a@b.com", true, null, List.of(), List.of()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(tickets);
    }

    @Test
    void createTicket_rejectsInvalidLabel() {
        assertThatThrownBy(() -> service.createTicket(1L, "主题", "正文",
                "EMAIL", "a@b.com", true, null, List.of("BUG", "NOPE"), List.of()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(tickets);
    }

    // ================================================================
    // Story 3-2 / AC3：relatedOrderToken 解析扩展到电商订单
    //
    // 🔴 顺序固定为「先问诊单、再电商单」—— 先查问诊保证**任何现有 token 的解释结果
    //    一个字都不变**，回归风险归零；电商单只在问诊查不到时才试。
    // 🔴 任何一支都**不抛异常**：App 误传一个 token 不能把建单整个打挂，
    //    用户是来求助的，把他的求助拒之门外是最糟的处置。
    // ================================================================
    @Nested
    @DisplayName("relatedOrderToken 解析（六个分支）")
    class ResolveRelatedOrder {

        private static final long USER = 7L;

        private ConsultOrder consultOrder(long id, long userId) {
            ConsultOrder o = BeanUtils.instantiateClass(ConsultOrder.class);
            ReflectionTestUtils.setField(o, "id", id);
            ReflectionTestUtils.setField(o, "userId", userId);
            return o;
        }

        private ShopOrder shopOrder(long id, long userId) {
            ShopOrder o = BeanUtils.instantiateClass(ShopOrder.class);
            ReflectionTestUtils.setField(o, "id", id);
            ReflectionTestUtils.setField(o, "userId", userId);
            return o;
        }

        /** 直接调私有方法 —— 它是本 AC 的全部逻辑，绕开建单的一堆无关前置更清楚。 */
        private Object resolve(String token) {
            return ReflectionTestUtils.invokeMethod(service, "resolveRelatedOrder", USER, token);
        }

        @Test
        @DisplayName("① 问诊 token 命中且属本人 → (id, CONSULT)")
        void consultTokenResolves() {
            Mockito.when(orders.findByOrderToken("c-tok"))
                    .thenReturn(java.util.Optional.of(consultOrder(42L, USER)));

            assertThat(resolve("c-tok"))
                    .isEqualTo(new FeedbackTicket.ResolvedOrder(42L, RelatedOrderType.CONSULT));
            // 问诊命中就不该再去问电商表。
            Mockito.verifyNoInteractions(shopOrders);
        }

        @Test
        @DisplayName("② 电商 token 命中且属本人 → (id, SHOP)")
        void shopTokenResolves() {
            Mockito.when(orders.findByOrderToken("s-tok")).thenReturn(java.util.Optional.empty());
            Mockito.when(shopOrders.findByPublicTokenAndUserId("s-tok", USER))
                    .thenReturn(java.util.Optional.of(shopOrder(42L, USER)));

            assertThat(resolve("s-tok"))
                    .isEqualTo(new FeedbackTicket.ResolvedOrder(42L, RelatedOrderType.SHOP));
        }

        @Test
        @DisplayName("③ 他人的问诊单 → null（静默，不抛）")
        void otherUsersConsultOrderResolvesToNull() {
            Mockito.when(orders.findByOrderToken("c-tok"))
                    .thenReturn(java.util.Optional.of(consultOrder(42L, USER + 1)));
            // 归属不符时会继续试电商表 —— 那边同样查不到。
            Mockito.when(shopOrders.findByPublicTokenAndUserId("c-tok", USER))
                    .thenReturn(java.util.Optional.empty());

            assertThat(resolve("c-tok")).isNull();
        }

        @Test
        @DisplayName("④ 他人的电商单 → null（findByPublicTokenAndUserId 一次查询即带归属）")
        void otherUsersShopOrderResolvesToNull() {
            Mockito.when(orders.findByOrderToken("s-tok")).thenReturn(java.util.Optional.empty());
            Mockito.when(shopOrders.findByPublicTokenAndUserId("s-tok", USER))
                    .thenReturn(java.util.Optional.empty());

            assertThat(resolve("s-tok")).isNull();
        }

        @Test
        @DisplayName("⑤ 乱码 token → null，不抛")
        void garbageTokenResolvesToNull() {
            Mockito.when(orders.findByOrderToken("!!!")).thenReturn(java.util.Optional.empty());
            Mockito.when(shopOrders.findByPublicTokenAndUserId("!!!", USER))
                    .thenReturn(java.util.Optional.empty());

            assertThat(resolve("!!!")).isNull();
        }

        @Test
        @DisplayName("⑥ 空串 / null → null，且一次库都不查")
        void blankTokenResolvesToNullWithoutQuerying() {
            assertThat(resolve("")).isNull();
            assertThat(resolve(null)).isNull();
            assertThat(resolve("   ")).isNull();

            Mockito.verifyNoInteractions(orders, shopOrders);
        }
    }

    /**
     * bug 20260922-524：「已联系 / 结案 / 忽略」拆成三个独立动作。
     * 🔴 已联系不结案、不发通知；忽略置 CLOSED、不发任何通知；已结案不可再忽略；每个生效的动作都留审计。
     */
    @Nested
    @DisplayName("工单处置拆分（bug 20260922-524）")
    class TicketDisposition {

        private static final long ADMIN = 900L;

        private FeedbackTicket ticket(String token, com.tailtopia.support.domain.TicketStatus status) {
            FeedbackTicket t = FeedbackTicket.create(7L, token, "主题", "正文",
                    com.tailtopia.support.domain.ContactType.EMAIL, "a@b.com", true, null);
            ReflectionTestUtils.setField(t, "status", status);
            Mockito.when(tickets.findByTicketToken(token)).thenReturn(java.util.Optional.of(t));
            return t;
        }

        @Test
        void contacted_marksContactedOnly_noResolve_noNotify_audited() {
            FeedbackTicket t = ticket("tok-c", com.tailtopia.support.domain.TicketStatus.OPEN);

            assertThat(service.markContacted("tok-c", ADMIN)).isTrue();

            assertThat(t.isContactedCustomer()).isTrue();
            // 离开「待联系」但仍在「待处理」：OPEN → IN_PROGRESS，绝不是 RESOLVED/CLOSED
            assertThat(t.getStatus()).isEqualTo(com.tailtopia.support.domain.TicketStatus.IN_PROGRESS);
            assertThat(t.getResolvedAt()).isNull();
            assertThat(t.getCsatDeadline()).isNull();
            verifyNoInteractions(notifications);
            Mockito.verify(audit).record(Mockito.eq(ADMIN),
                    Mockito.eq(com.tailtopia.admin.audit.service.AuditActions.TICKET_CONTACTED),
                    Mockito.eq("feedback_ticket"), Mockito.eq("tok-c"), Mockito.anyString());
        }

        @Test
        void contacted_twice_isIdempotent_auditedOnce() {
            ticket("tok-c2", com.tailtopia.support.domain.TicketStatus.OPEN);

            assertThat(service.markContacted("tok-c2", ADMIN)).isTrue();
            assertThat(service.markContacted("tok-c2", ADMIN)).as("再点返回 false 供控制器给友好提示").isFalse();

            Mockito.verify(audit, Mockito.times(1)).record(Mockito.anyLong(), Mockito.anyString(),
                    Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        }

        @Test
        void contacted_onResolvedTicket_conflict() {
            ticket("tok-c3", com.tailtopia.support.domain.TicketStatus.RESOLVED);
            assertThatThrownBy(() -> service.markContacted("tok-c3", ADMIN))
                    .isInstanceOfSatisfying(AppException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(audit);
        }

        @Test
        void ignore_closesWithoutAnyNotification_audited() {
            FeedbackTicket t = ticket("tok-i", com.tailtopia.support.domain.TicketStatus.IN_PROGRESS);

            service.ignoreTicket("tok-i", ADMIN);

            assertThat(t.getStatus()).isEqualTo(com.tailtopia.support.domain.TicketStatus.CLOSED);
            assertThat(t.getHandledBy()).isEqualTo(ADMIN);
            // 不开 CSAT 窗口（也就不会进 7 天自动关闭扫描集）
            assertThat(t.getCsatDeadline()).isNull();
            assertThat(t.getResolvedAt()).isNull();
            verifyNoInteractions(notifications);
            Mockito.verify(audit).record(Mockito.eq(ADMIN),
                    Mockito.eq(com.tailtopia.admin.audit.service.AuditActions.TICKET_IGNORED),
                    Mockito.eq("feedback_ticket"), Mockito.eq("tok-i"), Mockito.anyString());
        }

        @Test
        void ignore_resolvedOrClosedTicket_conflict_noAudit() {
            ticket("tok-r", com.tailtopia.support.domain.TicketStatus.RESOLVED);
            ticket("tok-x", com.tailtopia.support.domain.TicketStatus.CLOSED);

            for (String tok : List.of("tok-r", "tok-x")) {
                assertThatThrownBy(() -> service.ignoreTicket(tok, ADMIN))
                        .isInstanceOfSatisfying(AppException.class,
                                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            }
            verifyNoInteractions(notifications, audit);
        }

        @Test
        void resolve_noLongerForcesContacted_stillNotifiesAndAudits() {
            FeedbackTicket t = ticket("tok-v", com.tailtopia.support.domain.TicketStatus.OPEN);
            ReflectionTestUtils.setField(service, "csatWindowDays", 7);

            service.resolveTicket("tok-v", ADMIN);

            assertThat(t.getStatus()).isEqualTo(com.tailtopia.support.domain.TicketStatus.RESOLVED);
            assertThat(t.isContactedCustomer()).as("结案不再隐含已联系").isFalse();
            assertThat(t.getCsatDeadline()).isNotNull();
            Mockito.verify(notifications, Mockito.times(2)).send(Mockito.anyLong(), Mockito.any(),
                    Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
            Mockito.verify(audit).record(Mockito.eq(ADMIN),
                    Mockito.eq(com.tailtopia.admin.audit.service.AuditActions.TICKET_RESOLVED),
                    Mockito.eq("feedback_ticket"), Mockito.eq("tok-v"), Mockito.anyString());
        }
    }
}
