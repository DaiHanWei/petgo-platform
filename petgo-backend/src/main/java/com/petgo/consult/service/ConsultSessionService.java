package com.petgo.consult.service;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.shared.error.AppException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 咨询会话业务（Story 5.3）：发起 WAITING / 同时仅 1 个约束 / 继续等待 / 取消。
 *
 * <p>状态迁移合法性集中在 {@link ConsultSession} 实体方法；本 service 负责事务 + 队列双写一致
 * （DB 权威 + Redis 待接单队列）+ 归属校验。{@code WAITING→IN_PROGRESS}（接单）留待 Story 5.5。
 */
@Service
public class ConsultSessionService {

    /** 无人接单超时阈值（秒）。超时<b>不迁移状态</b>，仅供前端弹「继续等待/转 AI」。 */
    public static final long WAITING_TIMEOUT_SECONDS = 60;

    private final ConsultSessionRepository repo;
    private final ConsultQueueService queue;

    public ConsultSessionService(ConsultSessionRepository repo, ConsultQueueService queue) {
        this.repo = repo;
        this.queue = queue;
    }

    /** 发起结果：新建会话 or 命中已有占用态会话（alreadyActive=true，前端跳「查看进行中 →」）。 */
    public record CreateResult(ConsultSession session, boolean alreadyActive) {
    }

    /**
     * 发起咨询：若已有占用态会话（WAITING/IN_PROGRESS/PENDING_CLOSE）则返回现有（不新建，alreadyActive=true）；
     * 否则建 WAITING + 入队。语义采用「200 + 现有 session」而非 409（前端据 alreadyActive 决定跳转，体验更顺）。
     */
    @Transactional
    public CreateResult createWaiting(long userId, ConsultSource source) {
        Optional<ConsultSession> active = findActiveForUser(userId);
        if (active.isPresent()) {
            return new CreateResult(active.get(), true);
        }
        ConsultSession saved = repo.save(ConsultSession.startWaiting(userId, source));
        queue.enqueue(saved.getId());
        return new CreateResult(saved, false);
    }

    @Transactional(readOnly = true)
    public Optional<ConsultSession> findActiveForUser(long userId) {
        return repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, SessionStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public ConsultSession getForUser(long userId, long sessionId) {
        return loadOwned(userId, sessionId);
    }

    /** 继续等待：重置计时基准，请求保留队列。 */
    @Transactional
    public ConsultSession continueWaiting(long userId, long sessionId) {
        ConsultSession s = loadOwned(userId, sessionId);
        s.resetWaiting();
        return repo.save(s);
    }

    /** 用户主动取消：WAITING → CANCELLED + 出队。取消后可再次发起。 */
    @Transactional
    public ConsultSession cancel(long userId, long sessionId) {
        ConsultSession s = loadOwned(userId, sessionId);
        s.cancel();
        ConsultSession saved = repo.save(s);
        queue.dequeue(sessionId);
        return saved;
    }

    private ConsultSession loadOwned(long userId, long sessionId) {
        ConsultSession s = repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        if (!s.getUserId().equals(userId)) {
            // 归属不符按「不存在」处理，不泄露他人会话存在性。
            throw AppException.notFound("咨询不存在");
        }
        return s;
    }
}
