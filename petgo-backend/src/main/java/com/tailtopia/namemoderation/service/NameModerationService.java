package com.tailtopia.namemoderation.service;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.namemoderation.domain.NameDecision;
import com.tailtopia.namemoderation.domain.NameModerationRecord;
import com.tailtopia.namemoderation.domain.NameModerationStatus;
import com.tailtopia.namemoderation.domain.NamePriority;
import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.event.NameResetEvent;
import com.tailtopia.namemoderation.repository.NameModerationRecordRepository;
import com.tailtopia.namemoderation.service.NameModerationRouter.RoutingDecision;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 名称审核状态机服务（内容审核 story 4，§5）。昵称/宠物名「先放行、后异步审核」：先建 {@code SCORING} 记录 →
 * 异步调三方评分（{@link ContentModerationService#evaluate}）按 {@link NameModerationRouter} 分档路由 →
 * 运营在人工队列判 {@link NameDecision#VIOLATION} 时重置为系统默认编码名并推送。
 *
 * <p>护栏：陈旧作废用 {@code revision} 单调版本键（D-CM3，正确处理 A→B→A）；fail-closed 降级重试 ≥3 次仍失败
 * 才入队（绝不自动判过，D-CM5）；违规重置写真实列 + {@code is_system_default_name}（D-CM4，与注销匿名化正交）；
 * 日志禁记名称原文/证据（§5.6）。异步只 {@code @Async} + DB 状态机，禁 MQ/缓存。
 */
@Service
public class NameModerationService {

    private static final Logger log = LoggerFactory.getLogger(NameModerationService.class);

    /** 降级重试上限（初次 + 重试；spec §5.3「重试 ≥3 次」）。 */
    static final int MAX_ATTEMPTS = 4;
    /** 指数退避基数（毫秒）：base * 2^attempt。 */
    static final long BACKOFF_BASE_MILLIS = 100L;

    private final NameModerationRecordRepository records;
    private final ContentModerationService moderation;
    private final DefaultNameGenerator nameGenerator;
    private final UserRepository users;
    private final PetProfileRepository petProfiles;
    private final ApplicationEventPublisher events;

    public NameModerationService(NameModerationRecordRepository records,
            ContentModerationService moderation, DefaultNameGenerator nameGenerator,
            UserRepository users, PetProfileRepository petProfiles, ApplicationEventPublisher events) {
        this.records = records;
        this.moderation = moderation;
        this.nameGenerator = nameGenerator;
        this.users = users;
        this.petProfiles = petProfiles;
        this.events = events;
    }

    // ---------- 送审开局：建记录 + 陈旧作废旧记录 ----------

    /**
     * 开启一次名称审核（§5.3）：把该 target 全部非终态旧记录置 {@code SUPERSEDED}（陈旧作废，若在队列一并移出），
     * 计算 {@code revision = 上一条 + 1}，新建 {@code SCORING} 记录。返回新记录 id。
     *
     * <p>{@code REQUIRES_NEW}：由 {@code @Async} 监听器在无环境事务的新线程调用，独立事务确保 INSERT 落库
     * （[notify AFTER_COMMIT 事务吞写] 教训的防御式实践）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long openReview(NameTargetType targetType, long targetRefId, String value) {
        // 陈旧作废：旧的 SCORING / MANUAL_PENDING 一律 SUPERSEDED（移出队列 + 其在途结果回来时按状态丢弃）。
        List<NameModerationRecord> active = records.findByTargetTypeAndTargetRefIdAndStatusIn(
                targetType, targetRefId, List.of(NameModerationStatus.SCORING, NameModerationStatus.MANUAL_PENDING));
        for (NameModerationRecord old : active) {
            old.supersede();
        }
        long nextRevision = records
                .findTopByTargetTypeAndTargetRefIdOrderByRevisionDesc(targetType, targetRefId)
                .map(r -> r.getRevision() + 1)
                .orElse(1L);
        NameModerationRecord created = records.save(
                NameModerationRecord.scoring(targetType, targetRefId, nextRevision, value, Instant.now()));
        return created.getId();
    }

    // ---------- 异步评分 + 路由 ----------

    /** {@code scoreAndRoute} 的评分结果。由监听器跨 bean 调 {@link #applyScoreOutcome} 落库 —— 若在本类内自调用会绕过事务代理（REQUIRES_NEW 失效 → 更新不 flush，记录卡 SCORING）。 */
    public record ScoredRouting(RoutingDecision decision, int retries) {}

    /**
     * 评分并路由（§5.2）。调三方评分（降级则指数退避重试 ≥3 次），返回路由决策（{@code AUTO_PASSED} 或
     * {@code MANUAL_PENDING}）+ 重试次数。不参与外层事务（含三方网络调用）、**不落库**——
     * 落库由监听器跨 bean 调 {@link #applyScoreOutcome}（独立事务 REQUIRES_NEW），避免自调用绕过代理。
     */
    public ScoredRouting scoreAndRoute(long recordId, String value) {
        ModerationOutcome outcome = null;
        int attempt = 0;
        for (; attempt < MAX_ATTEMPTS; attempt++) {
            outcome = moderation.evaluate(value, List.of());
            if (!outcome.degraded()) {
                break; // 拿到确定评分（含 TEXT_BLOCKED/PASS/RISKY）即止
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                backoff(attempt);
            }
        }
        int retries = Math.min(attempt, MAX_ATTEMPTS - 1); // 重试次数（不含首次）
        if (outcome.degraded()) {
            // fail-closed 告警（护栏：仅 recordId + reason，绝不记名称原文/证据）。
            log.warn("name moderation fail-closed → manual queue: recordId={} degradeReason={} retries={}",
                    recordId, outcome.degradeReason(), retries);
        }
        return new ScoredRouting(NameModerationRouter.route(outcome), retries);
    }

    /**
     * 评分结果落库（§5.4 陈旧作废）。结果落库前校验记录仍为 {@code SCORING}（最新非终态）；否则说明已被更高
     * revision 取代 → 静默丢弃（不改状态、不改名、不通知）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyScoreOutcome(long recordId, RoutingDecision decision, int retryCount) {
        NameModerationRecord record = records.findById(recordId).orElse(null);
        if (record == null || record.getStatus() != NameModerationStatus.SCORING) {
            log.debug("name moderation score dropped (stale/superseded): recordId={}", recordId);
            return; // 陈旧作废：静默丢弃
        }
        record.applyScore(decision.status(), decision.priority(), decision.riskScore(), retryCount);
    }

    // ---------- 运营处置（供 story 8 后台复用） ----------

    /**
     * 运营处置一条人工队列项（§5.8）。仅 {@code MANUAL_PENDING} 可处置（幂等 + 陈旧防御）；
     * {@code PASS} → {@code RESOLVED_PASS}（名称保留、不推送）；{@code VIOLATION} → 重置默认编码名 + 推送。
     */
    @Transactional
    public void decide(long recordId, NameDecision decision, long adminId, String reason) {
        NameModerationRecord record = records.findById(recordId)
                .orElseThrow(() -> AppException.notFound("审核记录不存在"));
        if (record.getStatus() != NameModerationStatus.MANUAL_PENDING) {
            throw AppException.validation("该审核记录已处置或已失效");
        }
        Instant now = Instant.now();
        if (decision == NameDecision.PASS) {
            record.resolve(NameModerationStatus.RESOLVED_PASS, adminId, now, reason);
            return;
        }
        resetToDefault(record, adminId, now, reason);
    }

    /** 违规重置（§5.5）：生成唯一默认编码名 → 写真实列 + {@code is_system_default_name=true} → 终态 + 发事件。 */
    private void resetToDefault(NameModerationRecord record, long adminId, Instant now, String reason) {
        NameTargetType type = record.getTargetType();
        long refId = record.getTargetRefId();
        NameResetEvent event = type == NameTargetType.NICKNAME
                ? resetNickname(refId)
                : resetPetName(refId);
        record.resolve(NameModerationStatus.RESOLVED_VIOLATION, adminId, now, reason);
        if (event != null) {
            // 负向结果 → 推送（D-CM6）；供 notify NAME_RESET + story 9 违规计数订阅。
            events.publishEvent(event);
        }
    }

    private NameResetEvent resetNickname(long userId) {
        Optional<User> found = users.findById(userId);
        if (found.isEmpty()) {
            log.debug("name reset skipped, user gone: userId(hash)={}", Integer.toHexString(Long.hashCode(userId)));
            return null;
        }
        User user = found.get();
        String defaultName = nameGenerator.generate(NameTargetType.NICKNAME, users::existsByNickname);
        user.setNickname(defaultName);
        user.setSystemDefaultName(true);
        users.save(user);
        // recipient = 昵称 target 本身（== users.id == userId），targetRef="NICKNAME" 跳设置昵称页。
        return new NameResetEvent(NameTargetType.NICKNAME, userId, "NICKNAME");
    }

    private NameResetEvent resetPetName(long petProfileId) {
        Optional<PetProfile> found = petProfiles.findById(petProfileId);
        if (found.isEmpty()) {
            log.debug("name reset skipped, pet gone");
            return null;
        }
        PetProfile pet = found.get();
        String defaultName = nameGenerator.generate(NameTargetType.PET_NAME, petProfiles::existsByName);
        pet.setName(defaultName);
        pet.setSystemDefaultName(true);
        petProfiles.save(pet);
        // targetRef = cardToken（不可枚举），客户端跳该宠物改名页；recipient = owner。
        return new NameResetEvent(NameTargetType.PET_NAME, pet.getOwnerId(), pet.getCardToken());
    }

    // ---------- 队列查询（story 8 后台复用） ----------

    @Transactional(readOnly = true)
    public List<NameModerationRecord> pendingQueue() {
        return records.findByStatusOrderBySubmittedAtAsc(NameModerationStatus.MANUAL_PENDING);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MILLIS * (1L << attempt)); // 100 / 200 / 400 ...
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
