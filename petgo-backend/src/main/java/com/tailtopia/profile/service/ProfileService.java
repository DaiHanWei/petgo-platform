package com.tailtopia.profile.service;

import com.tailtopia.avatarmoderation.domain.AvatarDefaults;
import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;
import com.tailtopia.avatarmoderation.event.AvatarReviewRequestedEvent;
import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.event.NameSubmittedEvent;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.PetProfileCreateRequest;
import com.tailtopia.profile.dto.PetProfileResponse;
import com.tailtopia.profile.dto.PetProfileUpdateRequest;
import com.tailtopia.profile.event.ProfileCreatedEvent;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.PetSlotFullException;
import com.tailtopia.profile.slot.PetSlotLock;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.MediaDeletionService;
import com.tailtopia.shared.media.PersonalMedia;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物档案服务（Story 2.2）：创建（V1.4.0 multi-pet Story 1.4 起按名额，最多 5 只）+ 当前用户档案查询 + 名片 token 生成。
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
    /** V1.3.2 Story 3.3：档案响应带角色小标（tailsonality 只读口，只依赖自己的仓库，无循环）。 */
    private final com.tailtopia.tailsonality.service.TailsonalityBadgeQuery tailsonalityBadges;
    /** V1.4.0 multi-pet：「我的宠物」只经 {@link PetResolver} 取（AD-2）。 */
    private final PetResolver petResolver;
    /** V1.4.0 multi-pet Story 1.4：名额只经 {@link PetSlotService#quotaOf}（架构 §5）。 */
    private final PetSlotService petSlots;
    /** V1.4.0 multi-pet Story 1.4：建档按账号串行化（名额相关写操作的唯一账号锁）。 */
    private final PetSlotLock petSlotLock;

    public ProfileService(PetProfileRepository profiles, CardTokenGenerator tokenGenerator,
            MilestoneService milestoneService, ProfileDeletionService profileDeletion,
            MediaDeletionService mediaDeletion, ApplicationEventPublisher events,
            com.tailtopia.tailsonality.service.TailsonalityBadgeQuery tailsonalityBadges,
            PetResolver petResolver, PetSlotService petSlots, PetSlotLock petSlotLock) {
        this.tailsonalityBadges = tailsonalityBadges;
        this.petResolver = petResolver;
        this.petSlots = petSlots;
        this.petSlotLock = petSlotLock;
        this.profiles = profiles;
        this.tokenGenerator = tokenGenerator;
        this.milestoneService = milestoneService;
        this.profileDeletion = profileDeletion;
        this.mediaDeletion = mediaDeletion;
        this.events = events;
    }

    /**
     * 创建当前用户的宠物档案（V1.4.0 multi-pet Story 1.4 · AD-3）：同一事务内、任何写入之前——
     * ① 按账号加锁（并发建档串行化）；② 已有 5 只 → 409 {@code pet-limit-reached}；
     * ③ 已建只数 ≥ 名额 → 409 {@code pet-slot-full}（带扩展成员）；④ 其余照旧建档。
     */
    @Transactional
    public PetProfileResponse create(long ownerId, PetProfileCreateRequest req) {
        petSlotLock.lockUser(ownerId);
        long count = profiles.countByOwnerId(ownerId);
        if (count >= PetSlotService.MAX_PETS) {
            throw new AppException(HttpStatus.CONFLICT, ErrorTypes.PET_LIMIT_REACHED, "已达宠物数量上限");
        }
        PetSlotQuota quota = petSlots.quotaOf(ownerId);
        if (count >= quota.total()) {
            throw new PetSlotFullException(quota, petSlots.unitPrice());
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
        // Story 6.1：建档时的体重【可跳过】—— 设必填会挡住既有建档转化。
        if (req.weightKg() != null) {
            profile.setWeightKg(req.weightKg());
        }
        if (req.neuterStatus() != null && !req.neuterStatus().isBlank()) {
            profile.setNeuterStatus(parseNeuterStatus(req.neuterStatus()));
        }
        // 性别（bug 20260827）：此前只有编辑接口能填 ⇒ 身份证卡上「性别」永远是空。
        // ⚠️ 取值已由 DTO 的 @Pattern 挡在 422，这里不再重复校验（重复一份迟早与那边走散）。
        if (req.sex() != null && !req.sex().isBlank()) {
            profile.setSex(PetSex.valueOf(req.sex()));
        }
        try {
            PetProfile saved = profiles.save(profile);
            // 建档按 pet_type 自动分配里程碑 roster（Story 8.1，同模块直调，非事件订阅；幂等）。
            milestoneService.assignRoster(saved.getId(), saved.getPetType());
            // 里程碑 C-S1「档案创建完成」自动完成（Story 8.3，AFTER_COMMIT 异步订阅，幂等）。
            events.publishEvent(new ProfileCreatedEvent(ownerId, saved.getId(), Instant.now()));
            // 内容审核 story 4：宠物名首次提交先放行立即生效 + 事务提交后异步送审（§5.3）。
            events.publishEvent(new NameSubmittedEvent(NameTargetType.PET_NAME, saved.getId(), saved.getName()));
            // 内容审核 story 5：宠物头像「先放行」立即生效 + 提交后异步图像送审（§5.1）。可见窗口期为有意权衡
            // （D-CM2 / 方案 §3.4）：违规头像异步审核完成前对所有人可见，靠异步 + 举报兜底，不做「先审后显」。
            // 仅当有头像且非平台默认常量时送审（防自审循环 B12）。
            if (saved.getAvatarUrl() != null && !AvatarDefaults.isDefault(saved.getAvatarUrl())) {
                events.publishEvent(new AvatarReviewRequestedEvent(
                        AvatarSubjectType.PET_AVATAR, saved.getId(), saved.getAvatarUrl()));
            }
            return PetProfileResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束只剩 card_token（理论撞号；并发建档已由 PetSlotLock 串行化）。
            // 仍归一为 409 profile-exists：旧版 App 只认这个 slug。
            throw AppException.profileExists("宠物档案创建冲突，请重试");
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
     * <p>DB 级联由内层 {@code ProfileDeletionService#deleteByPetId} 原子提交；OSS 对象自 2026-08-19（F21）
     * 起<b>保留不删</b>——id_cards 快照可能长期引用头像对象（{@code MediaDeletionService} 只记账）。无档案 → 404。
     */
    public void deleteMyProfile(long ownerId) {
        deleteMyProfile(ownerId, null);
    }

    /**
     * V1.4.0 multi-pet（Story 1.3 · AD-9）：只删指定的一只（{@code petId} 为 null = 默认宠物，兼容旧版 App）；
     * 他人 / 不存在的 petId 与无档案一样 404。不再给身份证打标（按账号打标只在注销路径）。
     */
    public void deleteMyProfile(long ownerId, Long petId) {
        PetProfile pet = petResolver.resolve(ownerId, petId);
        PersonalMedia media = profileDeletion.deleteByPetId(ownerId, pet.getId());
        mediaDeletion.deletePrivateKeys(media.privateKeys());
        mediaDeletion.deletePublicByUrls(media.publicUrls());
    }

    /**
     * 编辑当前用户档案（Story 2.8，部分更新）。无档案 → 404；cardToken 不变；不限次数。
     */
    @Transactional
    public PetProfileResponse update(long ownerId, PetProfileUpdateRequest req) {
        return update(ownerId, null, req);
    }

    /** V1.4.0 multi-pet：编辑指定宠物（petId 为 null = 默认宠物）。不属于本人 → 404。 */
    @Transactional
    public PetProfileResponse update(long ownerId, Long petId, PetProfileUpdateRequest req) {
        PetProfile profile = petResolver.resolve(ownerId, petId);

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
        boolean avatarChanged = false;
        String newAvatar = null;
        if (req.avatarUrl() != null) {
            newAvatar = blankToNull(req.avatarUrl());
            // 内容审核 story 5：仅头像实际变化时先放行立即生效 + 提交后异步图像送审（§5.1，编辑重审 D-CM3）。
            avatarChanged = !java.util.Objects.equals(newAvatar, profile.getAvatarUrl());
            profile.setAvatarUrl(newAvatar);
        }
        if (req.breed() != null) {
            profile.setBreed(blankToNull(req.breed()));
        }
        if (req.birthday() != null) {
            profile.setBirthday(req.birthday());
        }
        // 性别（V1.1.6 Story 1.1）。⚠️ 刻意放在这一组、**不放进上面 name/avatarUrl 那两段** ——
        // 那两段带 nameChanged / avatarChanged 的异步送审分支，性别不需要审核。
        // 传 null = 不改动（本接口统一语义），故无清空路径；取值已由 @Pattern 挡在 422。
        if (req.sex() != null) {
            profile.setSex(PetSex.valueOf(req.sex()));
        }
        // Story 6.1：体重与绝育状态。🔒 体重是 PII 邻近的健康数据 —— 下面任何一行都不得进日志。
        if (req.weightKg() != null) {
            profile.setWeightKg(req.weightKg());
        }
        if (req.neuterStatus() != null && !req.neuterStatus().isBlank()) {
            profile.setNeuterStatus(parseNeuterStatus(req.neuterStatus()));
        }
        if (req.intro() != null) {
            profile.setIntro(blankToNull(req.intro()));
        }
        profiles.save(profile);
        if (nameChanged) {
            events.publishEvent(new NameSubmittedEvent(NameTargetType.PET_NAME, profile.getId(), profile.getName()));
        }
        // 可见窗口期 D-CM2（有意权衡）：非默认头像先放行、异步审核，违规前对所有人可见；重置为默认不再送审（B12）。
        if (avatarChanged && newAvatar != null && !AvatarDefaults.isDefault(newAvatar)) {
            events.publishEvent(new AvatarReviewRequestedEvent(
                    AvatarSubjectType.PET_AVATAR, profile.getId(), newAvatar));
        }
        // 编辑响应同样带小标：App 用它覆盖本地档案，缺了小标会闪掉。
        return PetProfileResponse.from(profile, badgeOf(profile));
    }

    /** 当前用户档案（无则 404）。供「已有档案直达」与后续 Story 复用。 */
    @Transactional(readOnly = true)
    public PetProfileResponse getMyProfile(long ownerId) {
        return getMyProfile(ownerId, null);
    }

    /** V1.4.0 multi-pet：指定宠物档案（petId 为 null = 默认宠物）。 */
    @Transactional(readOnly = true)
    public PetProfileResponse getMyProfile(long ownerId, Long petId) {
        PetProfile p = petResolver.resolve(ownerId, petId);
        return PetProfileResponse.from(p, badgeOf(p));
    }

    /**
     * 账号默认宠物（V1.4.0 multi-pet Story 1.2：取代旧 {@code findByOwnerId} 包装；定义只在 {@link DefaultPetPolicy}）。
     * 供访客主页等「按账号展示一只」的过渡入口用。
     */
    @Transactional(readOnly = true)
    public Optional<PetProfile> findDefault(long ownerId) {
        return profiles.findDefault(ownerId);
    }

    /** V1.4.0 multi-pet Story 1.4：账号全部宠物（按创建时间升序，同刻按 id），经 {@link PetResolver#listOwned}。 */
    public List<PetProfile> listOwned(long ownerId) {
        return petResolver.listOwned(ownerId);
    }

    private String badgeOf(PetProfile p) {
        return p.getId() == null ? null : tailsonalityBadges.badgeOf(p.getId()).orElse(null);
    }

    /**
     * 名片分享信号（Story 8.3）。名片分享在客户端经系统分享面板完成（无服务端分享动作），App 触发分享后
     * 回报此信号 → 发布 {@link ProfileCreatedEvent 同域} {@code CardSharedEvent} 驱动里程碑 C-S3 自动完成
     * （幂等，仅首次有效）。无档案 → 404。
     */
    @Transactional
    public void recordCardShared(long ownerId) {
        recordCardShared(ownerId, null);
    }

    /** V1.4.0 multi-pet：指定宠物的名片分享信号（petId 为 null = 默认宠物）。 */
    @Transactional
    public void recordCardShared(long ownerId, Long petId) {
        PetProfile pet = petResolver.resolve(ownerId, petId);
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
        return profiles.existsByIdAndOwnerId(petId, ownerId);
    }

    /** 解析宠物类型（F6）：必填 + 枚举合法，非法 → 422。@NotBlank 已拦空，此处兜底大小写/非法值。 */
    /**
     * 解析绝育状态。
     *
     * <p>🔴 未知值抛错而不是降级到 UNKNOWN：{@code UNKNOWN} 有它自己的含义
     * （用户明确表示「不知道」，领养的成年宠常见），把解析失败也塞进去会让
     * 「有多少人真的不知道」这个数字失真。
     */
    private static com.tailtopia.profile.domain.NeuterStatus parseNeuterStatus(String raw) {
        for (var v : com.tailtopia.profile.domain.NeuterStatus.values()) {
            if (v.name().equalsIgnoreCase(raw.trim())) {
                return v;
            }
        }
        throw AppException.validation("绝育状态不合法");
    }

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
