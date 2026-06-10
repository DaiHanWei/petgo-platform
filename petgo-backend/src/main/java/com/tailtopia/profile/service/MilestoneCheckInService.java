package com.tailtopia.profile.service;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneTriggerType;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.MilestoneCheckinCandidateResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 里程碑用户打卡（Story 8.4 · FR-42「已打卡」路径）。仅「用户打卡」类里程碑可关联一条本人成长日历内容；
 * **一条内容至多关联一个里程碑**（DB partial-unique + 此处校验置灰），完成幂等不可撤销。
 *
 * <p>「去发布」路径在客户端发布成长日历成功后**以新内容 id 调本 check-in**（同一 API 收口两路径）。
 * 跨模块取成长日历内容经 {@link ContentService} 接口（禁 profile 直读 content 表）。
 */
@Service
public class MilestoneCheckInService {

    private static final int CANDIDATE_LIMIT = 100;

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final MilestoneCompletionService completionService;
    private final ContentService contentService;

    public MilestoneCheckInService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, MilestoneCompletionService completionService,
            ContentService contentService) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
        this.completionService = completionService;
        this.contentService = contentService;
    }

    /** 内容关联选择器候选：本人成长日历内容，已关联其它里程碑的标 {@code linked}（前端置灰）。 */
    @Transactional(readOnly = true)
    public MilestoneCheckinCandidateResponse.Page candidates(long ownerId) {
        PetProfile pet = requireProfile(ownerId);
        Set<Long> linkedIds = linkedContentIds(pet.getId());
        List<MilestoneCheckinCandidateResponse> items = contentService
                .findRecentGrowthMomentsByEventDate(ownerId, CANDIDATE_LIMIT).stream()
                .map(v -> toCandidate(v, linkedIds.contains(v.id())))
                .toList();
        return new MilestoneCheckinCandidateResponse.Page(items);
    }

    /**
     * 打卡：把一条本人成长日历内容关联到「用户打卡」类里程碑 {@code code} 并完成（USER_CHECKIN）。
     * 返回完成后的里程碑项（供前端触发庆祝 8.5）。
     */
    @Transactional
    public MilestoneItemResponse checkIn(long ownerId, String code, long contentId) {
        PetProfile pet = requireProfile(ownerId);
        PetMilestone milestone = milestones.findByPetProfileIdAndCode(pet.getId(), code)
                .orElseThrow(() -> AppException.notFound("里程碑不存在"));
        if (milestone.getTriggerType() != MilestoneTriggerType.USER_CHECKIN) {
            throw AppException.validation("该里程碑非用户打卡类，不支持手动关联");
        }
        if (completions.existsByPetMilestoneId(milestone.getId())) {
            throw AppException.conflict("该里程碑已完成");
        }
        if (!contentService.isOwnGrowthMoment(ownerId, contentId)) {
            throw AppException.validation("只能关联本人成长日历内容");
        }
        if (completions.existsByLinkedContentId(contentId)) {
            throw AppException.conflict("该内容已关联其它里程碑");
        }
        boolean done = completionService.completeForOwner(
                ownerId, suffixOf(code), MilestoneCompletionSource.USER_CHECKIN, contentId);
        if (!done) {
            // 并发兜底（唯一约束）：已被另一请求完成。
            throw AppException.conflict("该里程碑已完成");
        }
        return new MilestoneItemResponse(
                milestone.getCode(), titleOf(milestone.getCode()),
                milestone.getLevel().name(), milestone.getTriggerType().name(),
                true, completions.findByPetMilestoneId(milestone.getId())
                        .map(c -> c.getCompletedAt()).orElse(null));
    }

    private Set<Long> linkedContentIds(long petProfileId) {
        List<Long> rosterIds = milestones.findByPetProfileIdOrderBySortOrderAsc(petProfileId).stream()
                .map(PetMilestone::getId).toList();
        Set<Long> linked = new HashSet<>();
        if (!rosterIds.isEmpty()) {
            completions.findByPetMilestoneIdIn(rosterIds).forEach(c -> {
                if (c.getLinkedContentId() != null) {
                    linked.add(c.getLinkedContentId());
                }
            });
        }
        return linked;
    }

    private static MilestoneCheckinCandidateResponse toCandidate(GrowthMomentView v, boolean linked) {
        return new MilestoneCheckinCandidateResponse(
                v.id(), v.firstImageUrl(), v.eventDate(), v.text(), linked);
    }

    private PetProfile requireProfile(long ownerId) {
        return profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    private static String suffixOf(String code) {
        int dash = code.indexOf('-');
        return dash >= 0 ? code.substring(dash + 1) : code;
    }

    private static String titleOf(String code) {
        var def = com.tailtopia.profile.domain.MilestoneCatalog.byCode(code);
        return def != null ? def.titleZh() : code;
    }
}
