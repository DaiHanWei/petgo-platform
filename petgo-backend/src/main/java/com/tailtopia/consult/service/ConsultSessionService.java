package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.event.ConsultRequestFailedEvent;
import com.tailtopia.consult.event.ConsultRequestQueuedEvent;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.dto.TriageUpgradeContext;
import com.tailtopia.triage.service.TriageService;
import com.tailtopia.consult.dto.ConsultSessionResponse.VetPeer;
import com.tailtopia.vet.service.VetAccountService;
import com.tailtopia.vet.service.VetPresenceService;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
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

    /**
     * 排队弃单线（秒，bug 20260803 接单校验）：超时弹层出现后用户可点「继续等待」续期
     * （{@code resetWaiting} 重置计时）；超过本线仍未续期/未取消视为已离开——接单拒绝 +
     * 兽医收件箱隐藏。= 超时线 60s + 弹层决策宽限 240s（用户仍停留在弹层期间被接单是正常路径，
     * 其轮询会自动进入聊天室）。
     */
    public static final long WAITING_ABANDON_SECONDS = WAITING_TIMEOUT_SECONDS + 240;

    private final ConsultSessionRepository repo;
    private final ConsultQueueService queue;
    private final TriageService triageService;
    private final ApplicationEventPublisher events;
    private final VetPresenceService presence;
    private final VetAccountService vetAccounts;

    public ConsultSessionService(ConsultSessionRepository repo, ConsultQueueService queue,
            TriageService triageService, ApplicationEventPublisher events,
            VetPresenceService presence, VetAccountService vetAccounts) {
        this.repo = repo;
        this.queue = queue;
        this.triageService = triageService;
        this.events = events;
        this.presence = presence;
        this.vetAccounts = vetAccounts;
    }

    /**
     * 会话对端（兽医）身份快照，供用户侧会话页顶栏显示「我在跟谁聊」（2026-08-07）。
     *
     * <p>为什么需要它：改前 App 顶栏的兽医名 / 头像首字母 / 在线点全是**写死的占位**，
     * 不管谁接单都显示同一个人。而那个名字恰好是真实存在的兽医账号，于是现象看起来像
     * 「会话串号」，实际代码里根本没读过任何兽医数据 —— 排查绕了很大一圈。
     *
     * <p><b>失败一律降级为 {@link VetPeer#UNKNOWN}，绝不外抛</b>：顶栏是装饰性信息，
     * 不值得让兽医账号查询的抖动把整个会话轮询打成 500（那会直接让用户的聊天页白屏）。
     * 与 {@code ConsultHistoryService#safeVetName} 同口径。
     *
     * <p>🔴 <b>本方法不得加 {@code @Transactional}</b>（2026-08-07 实测，被既有端点测试逼出来的）。
     * 加上之后 catch 会形同虚设：{@code vetAccounts.getById} 查不到兽医时抛异常，已经把当前事务
     * 标记为 <b>rollback-only</b>；异常虽被这里吞掉，方法返回时事务提交仍会抛
     * {@code UnexpectedRollbackException} ⇒ {@code GET /consult-sessions/&#123;id&#125;} 直接 500，
     * 正是本方法要避免的后果。不开事务，则 getById 的异常在它自己的事务内回滚完再传上来，
     * 这里接住即可。本方法只读一个实体 + 一次 Redis，本就不需要事务边界。
     */
    public VetPeer vetPeerOf(ConsultSession s) {
        Long vetId = s == null ? null : s.getVetId();
        if (vetId == null) {
            return VetPeer.UNKNOWN; // WAITING：还没有兽医，前端显示排队态、不显示顶栏身份
        }
        try {
            var vet = vetAccounts.getById(vetId);
            return new VetPeer(vet.getDisplayName(), vet.getAvatarUrl(), presence.isOnline(vetId));
        } catch (RuntimeException e) {
            return VetPeer.UNKNOWN;
        }
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
        return createWaiting(userId, source, null, null);
    }

    /**
     * 发起咨询（Story F 增量：直连问诊可带用户自填病例 —— 症状 + 私密桶图 key）。
     * 病例存进与 AI 上下文同列（{@code ai_symptom_text}/{@code ai_image_refs}），供兽医侧 aiContext 展示；
     * {@code ai_danger_level} 留空（无 AI 评级）。
     */
    public CreateResult createWaiting(long userId, ConsultSource source,
            String symptomText, List<String> imageObjectKeys) {
        Optional<ConsultSession> active = findActiveForUser(userId);
        if (active.isPresent()) {
            return new CreateResult(active.get(), true);
        }
        ConsultSession session = ConsultSession.startWaiting(userId, source);
        if ((symptomText != null && !symptomText.isBlank())
                || (imageObjectKeys != null && !imageObjectKeys.isEmpty())) {
            session.bindDirectCase(symptomText, imageObjectKeys);
        }
        ConsultSession saved = repo.save(session);
        queue.enqueue(saved.getId());
        events.publishEvent(new ConsultRequestQueuedEvent(saved.getId())); // → 推送在线兽医（6.2）
        return new CreateResult(saved, false);
    }

    /**
     * 从 AI 分诊升级发起（Story 5.4，source=AI_UPGRADE）。
     *
     * <p>经 {@link TriageService} 接口拉评级/症状/私密图 key（禁直读 triage repository），
     * 定格为 consult 上下文快照。<b>红线兜底：RED 一律拒绝升级</b>（即便前端误放入口）。
     * 复用「同时仅 1 个」约束（已有占用态 → 返回现有，不重复绑定）。
     */
    @Transactional
    public CreateResult createWaitingFromUpgrade(long userId, long triageTaskId) {
        Optional<ConsultSession> active = findActiveForUser(userId);
        if (active.isPresent()) {
            return new CreateResult(active.get(), true);
        }
        TriageUpgradeContext ctx = triageService.getResultForUpgrade(userId, triageTaskId);
        if (ctx.dangerLevel() == DangerLevel.RED) {
            // 架构不可协商：红色态零兽医引流。
            throw AppException.forbidden("高危情况请立即就医，本通道不提供升级");
        }
        ConsultSession session = ConsultSession.startWaiting(userId, ConsultSource.AI_UPGRADE);
        session.bindAiContext(ctx.triageTaskId(), ctx.dangerLevel().name(),
                ctx.symptomText(), ctx.imageObjectKeys());
        ConsultSession saved = repo.save(session);
        queue.enqueue(saved.getId());
        events.publishEvent(new ConsultRequestQueuedEvent(saved.getId())); // → 推送在线兽医（6.2）
        return new CreateResult(saved, false);
    }

    @Transactional(readOnly = true)
    public Optional<ConsultSession> findActiveForUser(long userId) {
        return repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, SessionStatus.ACTIVE);
    }

    /**
     * Story 5.5 增量：用户 IM UserSig 闸门（控 MAU）。仅当该用户有「已接单进行中/待关闭」会话才放行
     * SDK login——{@code WAITING}（尚无兽医/会话）不放行，避免无关用户吃 MAU。供 IM UserSig 控制器经
     * <b>service 接口</b>调用（不跨模块直访 repository）。
     */
    @Transactional(readOnly = true)
    public boolean hasImLoginEligibleSession(long userId) {
        return repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, SessionStatus.IM_LOGIN_ELIGIBLE)
                .isPresent();
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

    /** 用户主动取消：WAITING → CANCELLED + 出队。取消后可再次发起。默认原因 USER_CANCEL。 */
    @Transactional
    public ConsultSession cancel(long userId, long sessionId) {
        return cancel(userId, sessionId, "USER_CANCEL");
    }

    /**
     * 取消（Story 2.9 重载）：附失败原因（USER_CANCEL/TIMEOUT；App 在超时弹层放弃时传 TIMEOUT——跨产品后续）。
     * 取消后发 {@link ConsultRequestFailedEvent}（admin 落失败请求队列）；onlineVetCount 取失败时刻在线兽医数。
     */
    @Transactional
    public ConsultSession cancel(long userId, long sessionId, String reason) {
        ConsultSession s = loadOwned(userId, sessionId);
        s.cancel();
        ConsultSession saved = repo.save(s);
        queue.dequeue(sessionId);
        String r = (reason == null || reason.isBlank()) ? "USER_CANCEL" : reason;
        events.publishEvent(new ConsultRequestFailedEvent(
                r, userId, sessionId, s.getCreatedAt(), presence.onlineVetIds().size()));
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
