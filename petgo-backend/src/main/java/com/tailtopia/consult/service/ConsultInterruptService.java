package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.InterruptReason;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.event.ConsultAnomalyRaisedEvent;
import com.tailtopia.consult.event.ConsultInterruptedEvent;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.im.TencentImClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 封禁兽医 → 进行中会话批量中断（Story 5.7）。
 *
 * <p>状态机旁路：{@code IN_PROGRESS / PENDING_CLOSE → INTERRUPTED}（终态，不评分、<b>不走存档桥接</b>）。
 * 每个中断会话经 IM 发系统消息提示用户重新发起；发 {@link ConsultInterruptedEvent}（推送/历史，Epic 6）。
 * 用户「同时仅 1 个」占用随中断解除，可立即重新发起。
 */
@Service
public class ConsultInterruptService {

    /** 封禁挂起宽限（秒，15min，服务端权威计时，H-5）。付费会话被封禁不即时中断，挂起此窗供逃生/退款。 */
    public static final long SUSPEND_SECONDS = 15 * 60;

    private final ConsultSessionRepository sessions;
    private final ConsultOrderRepository orders;
    private final TencentImClient imClient;
    private final ApplicationEventPublisher events;

    public ConsultInterruptService(ConsultSessionRepository sessions, ConsultOrderRepository orders,
            TencentImClient imClient, ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.orders = orders;
        this.imClient = imClient;
        this.events = events;
    }

    /**
     * 封禁兽医 → 分流处理进行中（含待关闭）会话（Story 5.7 + 3.8 分流）。封禁与处理同事务保证一致。
     *
     * <p><b>付费会话</b>（有 {@code consult_orders (user_id,vet_id,IN_PROGRESS)}，Story 3.8）→ <b>挂起</b>
     * （{@code session.suspend(+15min)} + IM 告知全额退款 + 逃生，<b>不即时中断</b>）——到期/用户逃生由
     * {@code ConsultSuspensionService} 强制结束+退款。<b>免费直连流会话</b>（无订单）→ 保持既有即时
     * {@code INTERRUPTED} + 无退款 IM（5.7 不变）。返回处理会话数（挂起+中断合计）。
     */
    @Transactional
    public int interruptByVetBan(long vetId) {
        List<ConsultSession> active = sessions.findByVetIdAndStatusIn(
                vetId, List.of(SessionStatus.IN_PROGRESS, SessionStatus.PENDING_CLOSE));
        for (ConsultSession s : active) {
            // bug 20260721-324：按本会话 consult_session_id 精确判是否付费单，不用松匹配。
            boolean paid = s.getStatus() == SessionStatus.IN_PROGRESS
                    && orders.findByConsultSessionIdAndStatus(
                            s.getId(), ConsultOrderStatus.IN_PROGRESS).isPresent();
            if (paid) {
                // Story 3.8（H-5）：付费会话挂起 15min，不即时中断（用户可逃生/等超时强制结束+退款）。
                s.suspend(Instant.now().plus(Duration.ofSeconds(SUSPEND_SECONDS)));
                sessions.save(s);
                if (s.getImConversationId() != null) {
                    imClient.sendSystemMessage(s.getImConversationId(),
                            "兽医已被封禁，本次问诊将在 15 分钟内结束并全额退款。你可以立即结束或上报。");
                }
                continue; // 不发中断/异常事件——挂起未终结，强制结束时再发
            }
            // 免费直连流会话（5.7 不变）：即时中断、无退款。
            s.interrupt(InterruptReason.VET_BANNED);
            sessions.save(s);
            if (s.getImConversationId() != null) {
                imClient.sendSystemMessage(s.getImConversationId(),
                        "兽医已临时下线，本次问诊已中断。你可以重新匹配一位兽医继续，或结束本次问诊。");
            }
            events.publishEvent(new ConsultInterruptedEvent(
                    s.getId(), s.getUserId(), vetId, InterruptReason.VET_BANNED.name()));
            events.publishEvent(new ConsultAnomalyRaisedEvent(
                    s.getId(), s.getUserId(), vetId, s.getCreatedAt(), s.terminalAt(), "VET_BANNED"));
        }
        return active.size();
    }

    /** 停用用户 → 强关其进行中（含待关闭）会话（Story 3.2）。经 service，admin 不直写 consult 表。返回中断数。 */
    @Transactional
    public int interruptByUser(long userId) {
        List<ConsultSession> active = sessions.findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of(SessionStatus.IN_PROGRESS, SessionStatus.PENDING_CLOSE));
        for (ConsultSession s : active) {
            s.interrupt(InterruptReason.USER_DEACTIVATED);
            sessions.save(s);
            if (s.getImConversationId() != null) {
                imClient.sendSystemMessage(s.getImConversationId(),
                        "该用户账号已被停用，本次问诊已结束。");
            }
            events.publishEvent(new ConsultInterruptedEvent(
                    s.getId(), s.getUserId(), s.getVetId() == null ? 0L : s.getVetId(),
                    InterruptReason.USER_DEACTIVATED.name()));
        }
        return active.size();
    }
}
