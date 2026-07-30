package com.tailtopia.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketStatus;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.service.SupportTicketCloseScanner;
import com.tailtopia.support.service.SupportTicketService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres+redis）。Story 4.7 工单结案通知与 CSAT。
 *
 * <p>核心：结案→RESOLVED+resolved_at+csat_deadline + TICKET_RESOLVED/CSAT_SURVEY 通知落库；
 * CSAT 提交→CLOSED+分数；非 owner 404；非法态 409；score 越界 400（控制器层，此处验 service 409/状态）；
 * 7 天 scanner 过期→CLOSED。
 */
class SupportTicketResolveCsatIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SupportTicketService support;
    @Autowired
    private FeedbackTicketRepository tickets;
    @Autowired
    private SupportTicketCloseScanner scanner;
    @Autowired
    private JdbcTemplate jdbc;

    private String newTicket(long userId) {
        return support.createTicket(userId, "无法登录", "帮我看看", "EMAIL", "a@b.com",
                true, null, List.of(), List.of());
    }

    private long notifCount(long userId, String type) {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id=? AND type=?",
                Long.class, userId, type);
        return c == null ? 0 : c;
    }

    @Test
    void resolve_setsResolved_deadline_andSendsTwoNotifications() {
        long userId = newUser().getId();
        String token = newTicket(userId);

        support.resolveTicket(token, 700L);

        FeedbackTicket t = tickets.findByTicketToken(token).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(t.isContactedCustomer()).isTrue();
        assertThat(t.getResolvedAt()).isNotNull();
        assertThat(t.getCsatDeadline()).isNotNull();
        assertThat(t.getHandledBy()).isEqualTo(700L);
        assertThat(notifCount(userId, "TICKET_RESOLVED")).isEqualTo(1);
        assertThat(notifCount(userId, "CSAT_SURVEY")).isEqualTo(1);
    }

    @Test
    void doubleResolve_conflict() {
        long userId = newUser().getId();
        String token = newTicket(userId);
        support.resolveTicket(token, 700L);
        assertThatThrownBy(() -> support.resolveTicket(token, 700L)).isInstanceOf(AppException.class);
    }

    @Test
    void submitCsat_closesTicket_storesScore() {
        long userId = newUser().getId();
        String token = newTicket(userId);
        support.resolveTicket(token, 700L);

        support.submitCsat(userId, token, 5, "sangat membantu");

        FeedbackTicket t = tickets.findByTicketToken(token).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(t.getCsatScore()).isEqualTo((short) 5);
        assertThat(t.getCsatComment()).isEqualTo("sangat membantu");
    }

    @Test
    void submitCsat_notOwner_404() {
        long userId = newUser().getId();
        long attacker = newUser().getId();
        String token = newTicket(userId);
        support.resolveTicket(token, 700L);
        assertThatThrownBy(() -> support.submitCsat(attacker, token, 4, null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void submitCsat_notResolved_conflict() {
        long userId = newUser().getId();
        String token = newTicket(userId); // OPEN，未结案
        assertThatThrownBy(() -> support.submitCsat(userId, token, 4, null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void scanner_autoClosesExpiredResolved() {
        long userId = newUser().getId();
        String token = newTicket(userId);
        support.resolveTicket(token, 700L);
        // 手动把 CSAT 死线拨到过去（模拟 7 天后未评）
        jdbc.update("UPDATE feedback_tickets SET csat_deadline = now() - interval '1 day' "
                + "WHERE ticket_token = ?", token);

        scanner.closeExpiredResolved();

        FeedbackTicket t = tickets.findByTicketToken(token).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(t.getCsatScore()).isNull(); // 无 CSAT，静默关闭
    }
}
