package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.event.ConsultAcceptedEvent;
import com.tailtopia.consult.event.ConsultRequestQueuedEvent;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.service.VetPresenceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医接单编排（Story 5.5）：CAS 迁移 WAITING→IN_PROGRESS + IM 建会话 + 出队 + 置 BUSY + 接单事件 + 系统消息。
 *
 * <p>并发抢单由 {@code @Version} 乐观锁裁决（仅一人成功，其余「已被接走」）。后端只编排 IM、不持长连接、
 * 不中转聊天媒体；实时收发由客户端腾讯 IM SDK 直连。
 */
@Service
public class ConsultAcceptService {

    private final ConsultSessionRepository repo;
    private final ConsultQueueService queue;
    private final VetPresenceService presence;
    private final TencentImClient imClient;
    private final ApplicationEventPublisher events;
    private final VetQualificationService vetQualificationService;

    public ConsultAcceptService(ConsultSessionRepository repo, ConsultQueueService queue,
            VetPresenceService presence, TencentImClient imClient, ApplicationEventPublisher events,
            VetQualificationService vetQualificationService) {
        this.repo = repo;
        this.queue = queue;
        this.presence = presence;
        this.imClient = imClient;
        this.events = events;
        this.vetQualificationService = vetQualificationService;
    }

    @Transactional
    public ConsultSession accept(long vetId, long sessionId) {
        ConsultSession s = repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        if (s.getStatus() != SessionStatus.WAITING) {
            throw AppException.conflict("该咨询已被接走");
        }
        // Story 2.1：资质门控（权威点）——仅 CERTIFIED/EXPIRING_SOON 可接单；未过资质即拒，
        // 不进 IN_PROGRESS、不占会话、不消队列。跨模块经 service（禁跨 repo）。
        if (!vetQualificationService.canTakeConsult(vetId)) {
            throw AppException.forbidden("资质未通过审核，暂不可接单");
        }
        // CAS：乐观锁裁决并发抢单（仅一人成功）。
        try {
            s.markInProgress(vetId);
            repo.saveAndFlush(s);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw AppException.conflict("该咨询已被接走");
        }

        // 赢家：先幂等 ensure 用户 IM 账号（系统消息要求目标账号存在；导入不计 MAU，绝不替用户 login），
        // 再建 C2C 会话、绑定会话标识。IM 失败不回滚已成接单（见 ensureAccount/createConversation 的非阻断语义）。
        String userImId = ImAccountMapper.userImId(s.getUserId());
        imClient.ensureAccount(userImId, "用户" + s.getUserId());
        String conv = imClient.createConversation(userImId, ImAccountMapper.vetImId(vetId));
        s.attachImConversation(conv);
        repo.save(s);

        // 出待接单队列 + 置兽医 BUSY（ONLINE→BUSY，5.2 在线态）。
        queue.dequeue(sessionId);
        presence.goBusy(vetId);

        // 接单事件（Epic 6 推送「兽医已接受」）+ IM 系统消息（即时，离线由推送补充）。
        events.publishEvent(new ConsultAcceptedEvent(sessionId, s.getUserId(), vetId));
        imClient.sendSystemMessage(conv, "兽医已接受你的问诊，点击开始对话");
        return s;
    }

    /**
     * 兽医退单（Story 5.3 R2，决策 F11）：IN_PROGRESS → WAITING + 重新入队广播 + 兽医转回可接单。
     *
     * <p>仅本次会话的接单兽医可退单。状态迁移并发互斥沿用 {@code @Version} 乐观锁（与接单一致，
     * <b>禁 MQ/分布式锁/任何中间件</b>）。每请求最多正常退单 2 次；{@code release_count > 2}
     * 为异常信号（{@link ConsultSession#isAbnormalReleaseCount()}），由运营人工处理（仍重新入队，用户不被卡住）。
     */
    @Transactional
    public ConsultSession release(long vetId, long sessionId) {
        ConsultSession s = repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        if (s.getVetId() == null || !s.getVetId().equals(vetId)) {
            // 归属不符按「不存在」处理，不泄露他人会话存在性。
            throw AppException.notFound("咨询不存在");
        }
        try {
            s.release(); // IN_PROGRESS → WAITING + 解绑兽医 + release_count+1
            repo.saveAndFlush(s);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw AppException.conflict("会话状态已变更，请刷新");
        }
        // 重新入队 + 广播在线兽医（与发起一致）+ 兽医由 BUSY 转回可接单（ONLINE/AVAILABLE）。
        queue.enqueue(sessionId);
        presence.goAvailable(vetId);
        events.publishEvent(new ConsultRequestQueuedEvent(sessionId));
        return s;
    }
}
