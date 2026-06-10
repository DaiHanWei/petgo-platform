package com.petgo.profile.service;

import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.domain.PetType;
import com.petgo.profile.dto.PetProfileCreateRequest;
import com.petgo.profile.dto.PetProfileResponse;
import com.petgo.profile.dto.PetProfileUpdateRequest;
import com.petgo.profile.event.ProfileCreatedEvent;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物档案服务（Story 2.2）：创建（单账号单宠物）+ 当前用户档案查询 + 名片 token 生成。
 *
 * <p>{@code ownerId} 一律由调用方从 JWT 取，绝不信任客户端传入（防越权）。
 */
@Service
public class ProfileService {

    private final PetProfileRepository profiles;
    private final CardTokenGenerator tokenGenerator;
    private final MilestoneService milestoneService;
    private final ApplicationEventPublisher events;

    public ProfileService(PetProfileRepository profiles, CardTokenGenerator tokenGenerator,
            MilestoneService milestoneService, ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.tokenGenerator = tokenGenerator;
        this.milestoneService = milestoneService;
        this.events = events;
    }

    /**
     * 创建当前用户的宠物档案。已存在 → 409（单账号单宠物，FR-11）。
     */
    @Transactional
    public PetProfileResponse create(long ownerId, PetProfileCreateRequest req) {
        if (profiles.existsByOwnerId(ownerId)) {
            throw AppException.profileExists("已有宠物档案，V1 暂仅支持单只宠物");
        }
        String name = req.name() == null ? null : req.name().trim();
        if (name == null || name.isEmpty()) {
            throw AppException.validation("宠物名不能为空");
        }
        PetType petType = parsePetType(req.petType());
        PetProfile profile = PetProfile.create(
                ownerId,
                petType,
                name,
                blankToNull(req.avatarUrl()),
                blankToNull(req.breed()),
                req.birthday(),
                blankToNull(req.intro()),
                tokenGenerator.generate());
        try {
            PetProfile saved = profiles.save(profile);
            // 建档按 pet_type 自动分配里程碑 roster（Story 8.1，同模块直调，非事件订阅；幂等）。
            milestoneService.assignRoster(saved.getId(), saved.getPetType());
            // 里程碑 C-S1「档案创建完成」自动完成（Story 8.3，AFTER_COMMIT 异步订阅，幂等）。
            events.publishEvent(new ProfileCreatedEvent(ownerId, saved.getId(), Instant.now()));
            return PetProfileResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 并发双开窗：唯一约束兜底（owner_id / card_token），归一为 409。
            throw AppException.profileExists("已有宠物档案，V1 暂仅支持单只宠物");
        }
    }

    /**
     * 编辑当前用户档案（Story 2.8，部分更新）。无档案 → 404；cardToken 不变；不限次数。
     */
    @Transactional
    public PetProfileResponse update(long ownerId, PetProfileUpdateRequest req) {
        PetProfile profile = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));

        if (req.name() != null) {
            String n = req.name().trim();
            if (n.isEmpty()) {
                throw AppException.validation("宠物名不能为空");
            }
            profile.setName(n);
        }
        if (req.avatarUrl() != null) {
            profile.setAvatarUrl(blankToNull(req.avatarUrl()));
        }
        if (req.breed() != null) {
            profile.setBreed(blankToNull(req.breed()));
        }
        if (req.birthday() != null) {
            profile.setBirthday(req.birthday());
        }
        if (req.intro() != null) {
            profile.setIntro(blankToNull(req.intro()));
        }
        profiles.save(profile);
        return PetProfileResponse.from(profile);
    }

    /** 当前用户档案（无则 404）。供「已有档案直达」与后续 Story 复用。 */
    @Transactional(readOnly = true)
    public PetProfileResponse getMyProfile(long ownerId) {
        return profiles.findByOwnerId(ownerId)
                .map(PetProfileResponse::from)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    @Transactional(readOnly = true)
    public Optional<PetProfile> findByOwnerId(long ownerId) {
        return profiles.findByOwnerId(ownerId);
    }

    /**
     * 名片分享信号（Story 8.3）。名片分享在客户端经系统分享面板完成（无服务端分享动作），App 触发分享后
     * 回报此信号 → 发布 {@link ProfileCreatedEvent 同域} {@code CardSharedEvent} 驱动里程碑 C-S3 自动完成
     * （幂等，仅首次有效）。无档案 → 404。
     */
    @Transactional
    public void recordCardShared(long ownerId) {
        PetProfile pet = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        events.publishEvent(new com.petgo.profile.event.CardSharedEvent(
                ownerId, pet.getId(), Instant.now()));
    }

    /** 按不可枚举名片 token 查档案（Story 2.6 名片 H5）。 */
    @Transactional(readOnly = true)
    public Optional<PetProfile> findByCardToken(String cardToken) {
        return profiles.findByCardToken(cardToken);
    }

    @Transactional(readOnly = true)
    public Optional<PetProfile> findById(long id) {
        return profiles.findById(id);
    }

    @Transactional
    public void updateOgImageUrl(long profileId, String ogImageUrl) {
        profiles.findById(profileId).ifPresent(p -> {
            p.setOgImageUrl(ogImageUrl);
            profiles.save(p);
        });
    }

    public boolean hasProfile(long ownerId) {
        return profiles.existsByOwnerId(ownerId);
    }

    /**
     * 校验 {@code petId} 是否属于 {@code ownerId} 的档案（成长日历绑定用）。
     * 供 content 模块经 service 接口调用，**避免 content 直接读 pet_profiles 表**（架构边界）。
     */
    @Transactional(readOnly = true)
    public boolean ownsPet(long ownerId, long petId) {
        return profiles.findByOwnerId(ownerId)
                .map(p -> p.getId().equals(petId))
                .orElse(false);
    }

    /** 解析宠物类型（F6）：必填 + 枚举合法，非法 → 422。@NotBlank 已拦空，此处兜底大小写/非法值。 */
    private static PetType parsePetType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("宠物类型必选");
        }
        try {
            return PetType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("宠物类型非法，须为 CAT/DOG/OTHER 之一");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
