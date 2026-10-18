package com.tailtopia.profile.slot;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物状态的<b>唯一变更入口</b>（V1.4.0 multi-pet Story 2.1 · AD-4，架构 §5）。
 *
 * <p>进入即 {@link PetSlotLock#lockUser}（与建档 {@code ProfileService.create}、2.2 月租发放同一把账号锁，ns "PETS"），
 * 同一账号的 reconcile / 建档 / 发放互斥、结果确定。顺序键（保留 / 恢复同一把）：
 * {@code keep_active DESC, created_at ASC, id ASC}——未做选择时 =「最早创建优先」。名额只经
 * {@link PetSlotService#quotaOf}（不自算）。
 *
 * <ul>
 *   <li>{@link PetSlotTrigger#RENTAL_EXPIRED}：正常数 &gt; 名额 → 按序保留前「名额」只，其余转只读；</li>
 *   <li>{@link PetSlotTrigger#SELECTION_CHANGED}：按序前「名额」只正常、其余只读（改选见 {@link #changeSelection}）；</li>
 *   <li>其余触发：<b>只恢复不降级</b>——正常数 &lt; 名额时按序把只读恢复为正常，直到正常数 = 名额。</li>
 * </ul>
 * 默认名额恒 ≥ 1，故有宠物的账号恒至少一只正常（decision-log ②「至少有一只可编辑」）。
 * 无变化不写库；日志只记 userId、触发、变更数（🔒 不记宠物名）。
 */
@Service
public class PetSlotStateService {

    private static final Logger log = LoggerFactory.getLogger(PetSlotStateService.class);

    /** keep_active DESC，其后保持仓储给的 created_at ASC, id ASC（List.sort 稳定）。 */
    private static final Comparator<PetProfile> KEEP_ACTIVE_FIRST =
            Comparator.comparing(PetProfile::isKeepActive).reversed();

    private final PetProfileRepository profiles;
    private final PetSlotService slots;
    private final PetSlotLock lock;
    private final PetResolver petResolver;

    public PetSlotStateService(PetProfileRepository profiles, PetSlotService slots, PetSlotLock lock,
            PetResolver petResolver) {
        this.profiles = profiles;
        this.slots = slots;
        this.lock = lock;
        this.petResolver = petResolver;
    }

    /** 按触发原因重算账号全部宠物的状态（加账号锁；须在事务内，锁 MANDATORY）。 */
    @Transactional
    public ReconcileResult reconcile(long userId, PetSlotTrigger trigger) {
        lock.lockUser(userId);
        return reconcileLocked(userId, trigger, List.of());
    }

    /** 改选列表上限（= 宠物总数上限 5）。 */
    static final int MAX_SELECTION = 5;

    /**
     * 取账号级名额锁（与建档 / reconcile / 月租发放同一把）。供需要在改动宠物行<b>之前</b>串行化的调用方
     * （如按只删除）使用；必须在事务内调用。
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockAccount(long userId) {
        lock.lockUser(userId);
    }

    /**
     * 改选「保持正常」的宠物（D13 / D14 · {@code PUT /pet-profiles/me/active-selection}）。同一把锁内：
     * ① 逐只归属校验（非本人 / 不存在 → 404，经 {@link PetResolver}）；② 宠物数 ≤ 名额 → 409
     * {@code pet-selection-not-needed}；③ 去重后只数 ≠ 名额 → 422 {@code validation}（必须恰好选满）；
     * ④ 选中者 {@code keep_active = true}、其余 false，再 {@code reconcile(SELECTION_CHANGED)}。
     */
    @Transactional
    public ReconcileResult changeSelection(long userId, List<Long> petIds) {
        List<Long> requested = petIds == null ? List.of() : petIds;
        if (requested.size() > MAX_SELECTION) { // 2.1 复审：防超长列表在持锁期间逐条查归属
            throw AppException.validation("petIds 最多 " + MAX_SELECTION + " 个");
        }
        if (requested.stream().anyMatch(java.util.Objects::isNull)) { // List.of(..).contains(null) 会 NPE
            throw AppException.validation("petIds 不能含空值");
        }
        lock.lockUser(userId);
        Set<Long> selected = new LinkedHashSet<>();
        for (Long petId : requested) {
            selected.add(petResolver.resolve(userId, petId).getId());
        }
        int quota = slots.quotaOf(userId).total();
        List<PetProfile> pets = profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId);
        if (pets.size() <= quota) {
            throw AppException.petSelectionNotNeeded("宠物数未超过名额，无需选择");
        }
        if (selected.size() != quota) {
            throw AppException.validation("需恰好选择 " + quota + " 只宠物");
        }
        List<PetProfile> flagChanged = new ArrayList<>();
        for (PetProfile p : pets) {
            boolean keep = selected.contains(p.getId());
            if (p.isKeepActive() != keep) {
                p.setKeepActive(keep);
                flagChanged.add(p);
            }
        }
        return reconcileLocked(userId, PetSlotTrigger.SELECTION_CHANGED, flagChanged, pets, quota);
    }

    private ReconcileResult reconcileLocked(long userId, PetSlotTrigger trigger, List<PetProfile> alreadyChanged) {
        List<PetProfile> pets = profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId);
        if (pets.isEmpty()) {
            return ReconcileResult.none();
        }
        return reconcileLocked(userId, trigger, alreadyChanged, pets, slots.quotaOf(userId).total());
    }

    private ReconcileResult reconcileLocked(long userId, PetSlotTrigger trigger, List<PetProfile> alreadyChanged,
            List<PetProfile> pets, int quota) {
        List<PetProfile> ordered = new ArrayList<>(pets);
        ordered.sort(KEEP_ACTIVE_FIRST);
        List<PetProfile> demoted = new ArrayList<>();
        List<PetProfile> restored = new ArrayList<>();
        switch (trigger) {
            case SELECTION_CHANGED -> rearrange(ordered, quota, demoted, restored);
            case RENTAL_EXPIRED -> {
                demoteOverflow(ordered, quota, demoted);
                restoreUpTo(ordered, quota, restored); // 正常数 < 名额时补足（不会与上一步同时生效）
            }
            default -> restoreUpTo(ordered, quota, restored);
        }
        Set<PetProfile> toSave = new LinkedHashSet<>(alreadyChanged);
        toSave.addAll(demoted);
        toSave.addAll(restored);
        if (!toSave.isEmpty()) {
            profiles.saveAll(new ArrayList<>(toSave));
        }
        if (!demoted.isEmpty() || !restored.isEmpty()) {
            log.info("宠物状态 reconcile userId={} trigger={} demoted={} restored={}",
                    userId, trigger, demoted.size(), restored.size());
        }
        return new ReconcileResult(refs(demoted), refs(restored));
    }

    /** 正常数 &gt; 名额：按序保留前 quota 只正常，其余正常的转只读。 */
    private static void demoteOverflow(List<PetProfile> ordered, int quota, List<PetProfile> demoted) {
        int kept = 0;
        for (PetProfile p : ordered) {
            if (!isActive(p)) {
                continue;
            }
            if (kept < quota) {
                kept++;
            } else {
                p.markReadOnly();
                demoted.add(p);
            }
        }
    }

    /** 正常数 &lt; 名额：按序把只读恢复为正常，直到正常数 = 名额。从不把正常改成只读。 */
    private static void restoreUpTo(List<PetProfile> ordered, int quota, List<PetProfile> restored) {
        long active = ordered.stream().filter(PetSlotStateService::isActive).count();
        for (PetProfile p : ordered) {
            if (active >= quota) {
                return;
            }
            if (!isActive(p)) {
                p.markActive();
                restored.add(p);
                active++;
            }
        }
    }

    /** 按序前 quota 只正常、其余只读（改选）。 */
    private static void rearrange(List<PetProfile> ordered, int quota, List<PetProfile> demoted,
            List<PetProfile> restored) {
        for (int i = 0; i < ordered.size(); i++) {
            PetProfile p = ordered.get(i);
            boolean shouldBeActive = i < quota;
            if (shouldBeActive && !isActive(p)) {
                p.markActive();
                restored.add(p);
            } else if (!shouldBeActive && isActive(p)) {
                p.markReadOnly();
                demoted.add(p);
            }
        }
    }

    private static boolean isActive(PetProfile p) {
        return p.getStatus() != PetStatus.READ_ONLY;
    }

    private static List<ReconcileResult.PetRef> refs(List<PetProfile> pets) {
        return pets.stream().map(p -> new ReconcileResult.PetRef(p.getId(), p.getName())).toList();
    }
}
