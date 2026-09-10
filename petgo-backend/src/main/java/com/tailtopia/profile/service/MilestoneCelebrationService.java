package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.MilestoneCelebrationReportResponse;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 庆祝状态记账（V1.3.0 批次 A · Story 1.4 · FR-111 · AD-A1 / AD-A3）。
 *
 * <p>系统此前**根本不记录「庆祝过没有」**，所以错过的庆祝无从补起：不在场时解锁（被点赞触发）没有庆祝；
 * 存健康记录后 500/800/1200ms 三次短轮询没拉到也永远不弹。本服务只做**记账**与**幂等回报通道**，
 * 补弹本身属 Story 1.5。
 *
 * <p>唯一判据（AD-A1.3）：「已完成且未庆祝」= {@code milestone_completions} 存在行
 * <b>且</b> {@code celebrated_at IS NULL}。全链路只认这一个，前端不得另立本地标记。
 *
 * <p>回报是 best-effort（AD-A3.1）：客户端在庆祝页**展示成功后**异步回一次，失败静默。
 * 失败的代价只是下次进列表页再补弹一次，可接受 —— 所以本服务不做任何重试、也不该被 UI 等待。
 */
@Service
public class MilestoneCelebrationService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneCelebrationService.class);

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;

    public MilestoneCelebrationService(PetProfileRepository profiles,
            PetMilestoneRepository milestones, MilestoneCompletionRepository completions) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
    }

    /**
     * 按客户端回传的 code 列表幂等置位「已庆祝」。
     *
     * <p>🔴 <b>只置位列表里的那些</b>（AD-A2.3b）。这里刻意先把 code 解析成**该宠物自己的** roster 行 id
     * 再交给仓库更新，两个作用：① 天然限定在本人档案内（别人的 code 落不到行上）；
     * ② 让「按列表」这件事在类型上就成立 —— 仓库那层拿到的是一串 id，写不出 mark-all。
     *
     * <p>未知 code / 尚未完成的 code **静默忽略**，不报错：客户端回报的是「我展示了什么」，
     * 它与服务端状态本就可能有几百毫秒的偏差，为此让回报失败只会换来一次多余的补弹。
     */
    @Transactional
    public MilestoneCelebrationReportResponse reportCelebrated(long ownerId, List<String> codes) {
        PetProfile pet = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));

        List<PetMilestone> roster = milestones.findByPetProfileIdOrderBySortOrderAsc(pet.getId());
        if (roster.isEmpty()) {
            return new MilestoneCelebrationReportResponse(0, 0L);
        }
        List<Long> rosterIds = roster.stream().map(PetMilestone::getId).toList();

        Set<String> requested = new LinkedHashSet<>(codes); // 去重，重复 code 不影响结果
        List<Long> targetIds = roster.stream()
                .filter(m -> requested.contains(m.getCode()))
                .map(PetMilestone::getId)
                .toList();

        int marked = targetIds.isEmpty() ? 0 : completions.markCelebrated(targetIds, Instant.now());
        long remaining = completions.countByPetMilestoneIdInAndCelebratedAtIsNull(rosterIds);
        // 不落 PII / 不落 code 明细：条数足够定位问题，且 code 在日志里没有诊断价值。
        log.info("milestone celebration reported: requested={} marked={} remaining={}",
                requested.size(), marked, remaining);
        return new MilestoneCelebrationReportResponse(marked, remaining);
    }

    /**
     * 该宠物「已完成且未庆祝」的条目数（角标用，AD-A2.2）。
     *
     * <p>由成长档案页头部**本来就要发的那个请求**顺带下发 —— 不新开接口、不额外发一次请求。
     * roster 缺失（存量档案还没物化）→ 0。
     */
    @Transactional(readOnly = true)
    public long countUncelebrated(long petProfileId) {
        List<Long> rosterIds = milestones.findByPetProfileIdOrderBySortOrderAsc(petProfileId).stream()
                .map(PetMilestone::getId).toList();
        return rosterIds.isEmpty() ? 0L
                : completions.countByPetMilestoneIdInAndCelebratedAtIsNull(rosterIds);
    }
}
