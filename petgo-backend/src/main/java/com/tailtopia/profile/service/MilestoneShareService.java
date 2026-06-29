package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneShare;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.MilestoneShareRequest;
import com.tailtopia.profile.dto.MilestoneShareResponse;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.MilestoneShareRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 里程碑庆祝对外分享服务（P-35 分享链接）。复用名片不可枚举 token 范式（{@link CardTokenGenerator}）。
 *
 * <p>职责：① 当前用户对某「已完成」里程碑创建 / 刷新分享（{@link #createOrRefresh}，按 {@code (pet,code)} 幂等，
 * 重复分享复用同一 token 仅刷新文案）；② 供公开页按 token 取分享（{@link #findByToken}）。
 * 护栏：owner 取自 JWT，code/petName/level/completedAt 全后端补；未完成里程碑不可分享。
 */
@Service
public class MilestoneShareService {

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final MilestoneShareRepository shares;
    private final CardTokenGenerator tokenGenerator;

    public MilestoneShareService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, MilestoneShareRepository shares,
            CardTokenGenerator tokenGenerator) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
        this.shares = shares;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * 为当前用户的某已完成里程碑创建（或刷新）对外分享，返回不可枚举 token。
     * 无档案 / 无该里程碑 → 404；里程碑未完成 → 422（不信任客户端，后端校验）。
     */
    @Transactional
    public MilestoneShareResponse createOrRefresh(long ownerId, String code, MilestoneShareRequest req) {
        PetProfile pet = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        PetMilestone milestone = milestones.findByPetProfileIdAndCode(pet.getId(), code)
                .orElseThrow(() -> AppException.notFound("里程碑不存在"));
        MilestoneCompletion completion = completions.findByPetMilestoneId(milestone.getId())
                .orElseThrow(() -> AppException.validation("里程碑尚未完成，不能分享"));

        String level = milestone.getLevel().name();
        String petName = pet.getName();
        String body = req.body() == null ? "" : req.body();
        String collectionLevels = req.collectionLevels() == null ? "" : req.collectionLevels();

        Optional<MilestoneShare> existing = shares.findByPetProfileIdAndCode(pet.getId(), code);
        if (existing.isPresent()) {
            MilestoneShare share = existing.get();
            share.refresh(level, petName, req.title(), body, req.locale(), collectionLevels);
            return new MilestoneShareResponse(share.getShareToken());
        }

        String token = tokenGenerator.generate();
        MilestoneShare share = MilestoneShare.create(token, pet.getId(), code, level, petName,
                req.title(), body, req.locale(), collectionLevels, completion.getCompletedAt());
        try {
            shares.save(share);
        } catch (DataIntegrityViolationException e) {
            // 并发双建窗：唯一约束 (pet_profile_id, code) 兜底 → 复用已落库的那条。
            MilestoneShare race = shares.findByPetProfileIdAndCode(pet.getId(), code)
                    .orElseThrow(() -> e);
            race.refresh(level, petName, req.title(), body, req.locale(), collectionLevels);
            return new MilestoneShareResponse(race.getShareToken());
        }
        return new MilestoneShareResponse(token);
    }

    /** 公开页按 token 取分享（不存在 → empty，由页面收敛到失效页）。 */
    @Transactional(readOnly = true)
    public Optional<MilestoneShare> findByToken(String shareToken) {
        return shares.findByShareToken(shareToken);
    }
}
