package com.tailtopia.profile.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.event.AccountMilestoneCompletedEvent;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import com.tailtopia.profile.slot.PetSlotLock;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号级里程碑（V1.4.2 social-topic Story 2.1 · FR-154 · D-4 / D-12 / G-3）。
 *
 * <ul>
 *   <li>{@link #onFlagTriggered}：账号第一次手动关注一个人 / 第一次收藏帖子 → 登记 flag（账号内一次）→ 所有 ACTIVE
 *       宠物各写一条本物种编码的完成行（{@code celebrated_at = NULL}，进补庆祝）→ 发 1 个
 *       {@link AccountMilestoneCompletedEvent}（通知 / 成长值 / 埋点按账号各 1 次）。</li>
 *   <li>{@link #applyExistingFlagsToPet}：账号已有 flag 时，新建 / 从只读恢复的宠物直接写「已完成且已庆祝」，
 *       <b>不发任何事件</b>（无通知、无成长值、无庆祝）。钩在 {@code MilestoneService.assignRoster} 与
 *       {@code PetSlotStateService.reconcileLocked}。</li>
 * </ul>
 *
 * <p>🔴 不复用 {@code MilestoneCompletionService.complete}：它按宠物发 {@code MilestoneCompletedEvent}、并触发组合依赖与
 * Lulus Pemula 判定 —— 2 只宠物 = 2 条通知 + 2 次成长值（D-12 明令禁止）。直接写 {@link MilestoneCompletionRepository}。
 *
 * <p>依赖方向：只依赖 repository + {@link PetSlotLock}，<b>不</b>注入 {@code MilestoneService} / {@code PetSlotStateService}
 * （两者反向注入本类，避免循环依赖）。日志只记 userId / flagKey / 计数（🔒 不记宠物名、昵称）。
 */
@Service
public class AccountMilestoneService {

    private static final Logger log = LoggerFactory.getLogger(AccountMilestoneService.class);

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final UserMilestoneFlagRepository flags;
    private final PetSlotLock lock;
    private final ApplicationEventPublisher events;
    private final AccountQueryService accounts;
    private final AccountDeletionRepository deletions;
    private final Clock clock;

    @Autowired
    public AccountMilestoneService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, UserMilestoneFlagRepository flags, PetSlotLock lock,
            ApplicationEventPublisher events, AccountQueryService accounts, AccountDeletionRepository deletions) {
        this(profiles, milestones, completions, flags, lock, events, accounts, deletions, Clock.systemUTC());
    }

    AccountMilestoneService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, UserMilestoneFlagRepository flags, PetSlotLock lock,
            ApplicationEventPublisher events, AccountQueryService accounts, AccountDeletionRepository deletions,
            Clock clock) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
        this.flags = flags;
        this.lock = lock;
        this.events = events;
        this.accounts = accounts;
        this.deletions = deletions;
        this.clock = clock;
    }

    /**
     * 账号级 flag 触发（由 {@code AccountMilestoneListener} 在 AFTER_COMMIT 之后异步调入）。
     *
     * <p>{@code REQUIRES_NEW}：AFTER_COMMIT 阶段原事务已提交，沿用它的写入会被静默吞掉（2026-07 事故）；自开新事务。
     * 第一步取账号锁（与建档 / reconcile 同一把），消除「触发与新建宠物并发、两边都没写」的窗口；锁是 MANDATORY，
     * 故必须在本事务内。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFlagTriggered(long userId, AccountMilestoneFlag flag) {
        lock.lockUser(userId);
        // 复审 2-1 #2：@Async 触发可能排在注销之后才跑 —— 已注销 / 注销已受理（account_deletions 有行，含 PENDING）/
        // 已停用 → 不登记 flag，否则会把注销刚删掉的 flag 写回去。放在锁之后：deleteByUserId 同取这把锁，
        // 注销受理行在执行删除前就已提交，锁内读到的一定是最新状态（口径同 PetSlotPurchaseService 后台补发）。
        if (!accounts.isActiveAccount(userId) || deletions.findByUserId(userId).isPresent()) {
            return;
        }
        if (flags.insertIfAbsent(userId, flag.name()) == 0) {
            return; // AC4 / AC8：已触发过（或并发另一条已登记）→ 整条路径 no-op
        }
        List<PetProfile> all = profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId);
        List<PetProfile> active = all.stream()
                .filter(AccountMilestoneService::isActive)
                .toList();
        List<Long> petIds = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (PetProfile p : active) {
            String code = flag.codeOf(p.getPetType());
            // roster 行缺失（极老的无 roster 宠物）→ 跳过；首次打开列表 assignRoster 惰性物化时由钩子补「已完成且已庆祝」。
            milestones.findByPetProfileIdAndCode(p.getId(), code).ifPresent(m -> {
                if (!completions.existsByPetMilestoneId(m.getId())) {
                    completions.save(MilestoneCompletion.of(m.getId(), MilestoneCompletionSource.SYSTEM_AUTO, null));
                    petIds.add(p.getId());
                    codes.add(code);
                }
            });
        }
        String target = targetCodeOf(flag, active, all);
        log.info("account milestone triggered userId={} flag={} activePets={} completed={}",
                userId, flag.name(), active.size(), petIds.size());
        // 无 ACTIVE 宠物也发：成长值监听照样记（G-3）；通知侧按 petIds 判空不发（V1.5.0 起 Lulus Pemula 例外，照发）。
        events.publishEvent(new AccountMilestoneCompletedEvent(userId, flag.name(), petIds, codes, target));
    }

    /**
     * 账号已有 flag → 给该宠物补「已完成且已庆祝」（{@code completed_at = celebrated_at = now}），不发任何事件。
     * 宠物非 ACTIVE / 不存在 / roster 行缺失 / 已有完成行 → 跳过。被调方已在事务中（建档 / reconcile / 惰性物化）。
     */
    @Transactional
    public void applyExistingFlagsToPet(long petProfileId) {
        profiles.findById(petProfileId).ifPresent(this::applyExistingFlagsToPet);
    }

    /** 同 {@link #applyExistingFlagsToPet(long)}，调用方手上已有实体（reconcile 恢复列表）。 */
    @Transactional
    public void applyExistingFlagsToPet(PetProfile pet) {
        if (pet == null || pet.getId() == null || !isActive(pet)) {
            return;
        }
        // 复审 2-1 #1：与 onFlagTriggered 同一把账号锁，读 flag 之前取。否则「老宠物惰性物化 roster」与「首次触发」
        // 并发时两边都看不到对方未提交的写入（触发侧看不到 roster 行、这边看不到 flag），永久缺一条完成行。
        // pg_advisory_xact_lock 同会话可重入：建档 / reconcile 调进来时已持此锁，再取一次直接成功；
        // 锁是 MANDATORY，本方法 @Transactional 且三处调用方（assignRoster / reconcileLocked / getMilestones）均在事务内。
        lock.lockUser(pet.getOwnerId());
        List<String> keys = flags.findFlagKeysByUserId(pet.getOwnerId());
        if (keys.isEmpty()) {
            return;
        }
        Instant at = clock.instant();
        int written = 0;
        for (String key : keys) {
            Optional<AccountMilestoneFlag> flag = parse(key);
            if (flag.isEmpty()) {
                continue;
            }
            String code = flag.get().codeOf(pet.getPetType());
            Optional<PetMilestone> m = milestones.findByPetProfileIdAndCode(pet.getId(), code);
            if (m.isPresent() && !completions.existsByPetMilestoneId(m.get().getId())) {
                completions.save(MilestoneCompletion.ofCelebrated(m.get().getId(),
                        MilestoneCompletionSource.SYSTEM_AUTO, at));
                written++;
            }
        }
        if (written > 0) {
            log.info("account milestone applied to pet userId={} written={}", pet.getOwnerId(), written);
        }
    }

    /** 注销清理（AccountDeletionService 调；D1/D2）：显式删该用户全部 flag 行。 */
    @Transactional
    public int deleteByUserId(long userId) {
        // 复审 2-1 #2：与触发串行 —— 进行中的触发先提交，之后再进锁的触发会读到注销受理行而 no-op。
        lock.lockUser(userId);
        return flags.deleteByUserId(userId);
    }

    private static Optional<AccountMilestoneFlag> parse(String key) {
        try {
            return Optional.of(AccountMilestoneFlag.valueOf(key));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty(); // CHECK 约束兜着，理论不可达；未知 key 不点亮任何东西
        }
    }

    /**
     * 通知 {@code targetRef} 用的编码：最早一只 ACTIVE 宠物的本物种编码；无 ACTIVE 宠物时 →
     * V1.5.0 onboarding-recall Story 2.2（AC7）：Lulus Pemula 取账号最早一只宠物（含只读）的编码，再没有用 CAT 编码
     * （通知照发、不能没有落点）；另两个 flag 维持 null（通知侧 petIds 空不发）。放在这里生成，通知监听不查库。
     */
    static String targetCodeOf(AccountMilestoneFlag flag, List<PetProfile> active, List<PetProfile> all) {
        if (!active.isEmpty()) {
            return flag.codeOf(active.get(0).getPetType());
        }
        if (flag != AccountMilestoneFlag.LULUS_PEMULA) {
            return null;
        }
        return all.isEmpty()
                ? flag.codeOf(com.tailtopia.profile.domain.PetType.CAT)
                : flag.codeOf(all.get(0).getPetType());
    }

    private static boolean isActive(PetProfile p) {
        return p.getStatus() != PetStatus.READ_ONLY;
    }
}
