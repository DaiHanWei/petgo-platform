package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.InterruptReason;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.event.ConsultInterruptedEvent;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.im.TencentImClient;
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

    private final ConsultSessionRepository sessions;
    private final TencentImClient imClient;
    private final ApplicationEventPublisher events;

    public ConsultInterruptService(ConsultSessionRepository sessions, TencentImClient imClient,
            ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.imClient = imClient;
        this.events = events;
    }

    /** 中断某兽医所有进行中（含待关闭）会话。返回中断数量。封禁与中断同事务保证一致。 */
    @Transactional
    public int interruptByVetBan(long vetId) {
        List<ConsultSession> active = sessions.findByVetIdAndStatusIn(
                vetId, List.of(SessionStatus.IN_PROGRESS, SessionStatus.PENDING_CLOSE));
        for (ConsultSession s : active) {
            s.interrupt(InterruptReason.VET_BANNED);
            sessions.save(s);
            if (s.getImConversationId() != null) {
                imClient.sendSystemMessage(s.getImConversationId(),
                        "兽医已临时下线，本次问诊已中断，请重新发起咨询");
            }
            events.publishEvent(new ConsultInterruptedEvent(
                    s.getId(), s.getUserId(), vetId, InterruptReason.VET_BANNED.name()));
        }
        return active.size();
    }
}
