package com.tailtopia.profile.service;

import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.event.NameSubmittedEvent;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.PetProfileCreateRequest;
import com.tailtopia.profile.dto.PetProfileResponse;
import com.tailtopia.profile.dto.PetProfileUpdateRequest;
import com.tailtopia.profile.event.ProfileCreatedEvent;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.MediaDeletionService;
import com.tailtopia.shared.media.PersonalMedia;
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
    private final ProfileDeletionService profileDeletion;
    private final MediaDeletionService mediaDeletion;
    private final ApplicationEventPublisher events;

    public ProfileService(PetProfileRepository profiles, CardTokenGenerator tokenGenerator,
            MilestoneService milestoneService, ProfileDeletionService profileDeletion,
            MediaDeletionService mediaDeletion, ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.tokenGenerator = tokenGenerator;
        this.milestoneService = milestoneService;
        this.profileDeletion = profileDeletion;
        this.mediaDeletion = mediaDeletion;
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
            // 内容审核 story 4：宠物名首次提交先放行立即生效 + 事务提交后异步送审（§5.3）。
            events.publishEvent(new NameSubmittedEvent(NameTargetType.PET_NAME, saved.getId(), saved.getName()));
            return PetProfileResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 并发双开窗：唯一约束兜底（owner_id / card_token），归一为 409。
            throw AppException.profileExists("已有宠物档案，V1 暂仅支持单只宠物");
        }
    }

    /**
     * 删除当前用户宠物档案（bug 20260702-237 / 决策 F18）。走 {@code /me}（C1），单账号单宠物。
     *
     * <p>级联物理删派生数据（health_events / pet_milestones / milestone_completions / milestone_shares，
     * 随之名片 card_token 因档案行消失自然失效）—— 复用 {@link ProfileDeletionService} 与注销 7.3 同一套级联，
     * 并清理档案个人 OSS 图（头像 / 名片 OG / 健康图）。<b>UGC（content_posts 成长日历条目）保留</b>，
     * 与注销 7.3「匿名化保留 UGC」一致。<b>petStatus 不改</b>：删后用户仍为 HAS_PET 但无档案，
     * 前端据 GET /me 的 404 落「空档案态」，可重建档案或切换宠物状态（闭合 bug 20260702-237 的困死）。
     *
     * <p>DB 级联由内层 {@code ProfileDeletionService#deleteByUserId} 原子提交；OSS 清理在提交后 best-effort
     * （OSS 故障不回滚已删档案，孤儿对象非正确性问题）。无档案 → 404。
     */
    public void deleteMyProfile(long ownerId) {
        if (!profiles.existsByOwnerId(ownerId)) {
            throw AppException.notFound("尚未创建宠物档案");
        }
        PersonalMedia media = profileDeletion.deleteByUserId(ownerId);
        mediaDeletion.deletePrivateKeys(media.privateKeys());
        mediaDeletion.deletePublicByUrls(media.publicUrls());
    }

    /**
     * 编辑当前用户档案（Story 2.8，部分更新）。无档案 → 404；cardToken 不变；不限次数。
     */
    @Transactional
    public PetProfileResponse update(long ownerId, PetProfileUpdateRequest req) {
        PetProfile profile = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));

        boolean nameChanged = false;
        if (req.name() != null) {
            String n = req.name().trim();
            if (n.isEmpty()) {
                throw AppException.validation("宠物名不能为空");
            }
            // 内容审核 story 4：仅宠物名实际变化时先放行立即生效 + 提交后异步送审（§5.3，编辑重审 D-CM3）。
            nameChanged = !n.equals(profile.getName());
            profile.setName(n);
            if (nameChanged && profile.isSystemDefaultName()) {
                profile.setSystemDefaultName(false); // 用户主动改新名 → 脱离违规重置默认名标记
            }
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
        if (nameChanged) {
            events.publishEvent(new NameSubmittedEvent(NameTargetType.PET_NAME, profile.getId(), profile.getName()));
        }
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
        events.publishEvent(new com.tailtopia.profile.event.CardSharedEvent(
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
