package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.MilestoneShareRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.passport.service.PetPassportDeletionService;
import com.tailtopia.place.service.PlaceCheckinDeletionService;
import com.tailtopia.tailsonality.service.TailsonalityDeletionService;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.PetSlotStateService;
import com.tailtopia.profile.slot.PetSlotTrigger;
import com.tailtopia.shared.media.PersonalMedia;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * profile 模块注销级联删除（Story 7.3）：宠物档案 + 健康事件（含私密桶健康图、公开桶名片图）+ 里程碑
 * （roster + 完成记录，Story 8.1）物理删除。经本 service 接口供 account 编排（禁 account 直 join profile 表）。
 * 返回待删个人图。
 */
@Service
public class ProfileDeletionService {

    private final PetProfileRepository petProfiles;
    private final HealthEventRepository healthEvents;
    private final HealthRecordRepository healthRecords;
    private final PetMilestoneRepository petMilestones;
    private final MilestoneCompletionRepository milestoneCompletions;
    private final MilestoneShareRepository milestoneShares;
    private final ContentPostRepository contentPosts;
    private final SerialAllocationService serialAllocation;
    private final IdCardRepository idCards;
    private final PlaceCheckinDeletionService placeCheckins;
    private final PetPassportDeletionService petPassports;
    private final TailsonalityDeletionService tailsonality;
    /** V1.4.0 multi-pet Story 2.1（AC6）：删一只后释放名额，只读宠物按序恢复（只恢复不降级）。 */
    private final PetSlotStateService petSlotState;

    public ProfileDeletionService(PetProfileRepository petProfiles, HealthEventRepository healthEvents,
            HealthRecordRepository healthRecords, PetMilestoneRepository petMilestones,
            MilestoneCompletionRepository milestoneCompletions,
            MilestoneShareRepository milestoneShares, ContentPostRepository contentPosts,
            SerialAllocationService serialAllocation, IdCardRepository idCards,
            PlaceCheckinDeletionService placeCheckins, PetPassportDeletionService petPassports,
            TailsonalityDeletionService tailsonality, PetSlotStateService petSlotState) {
        this.petProfiles = petProfiles;
        this.healthEvents = healthEvents;
        this.healthRecords = healthRecords;
        this.petMilestones = petMilestones;
        this.milestoneCompletions = milestoneCompletions;
        this.milestoneShares = milestoneShares;
        this.contentPosts = contentPosts;
        this.serialAllocation = serialAllocation;
        this.idCards = idCards;
        this.placeCheckins = placeCheckins;
        this.petPassports = petPassports;
        this.tailsonality = tailsonality;
        this.petSlotState = petSlotState;
    }

    /**
     * 删除本人的一只宠物（V1.4.0 multi-pet Story 1.3 · AD-9 / FR-145.2）：只删这一只及其派生数据，其他宠物不受影响。
     *
     * <p>7 张无 ON DELETE 的子表须先于宠物行删除；{@code content_posts} 只解除绑定（UGC 保留）。
     * 删的不是最后一只时<b>不</b>给身份证打标：{@code id_cards} 不挂宠物，删一只不能给账号全部卡打标。
     * 删的是账号<b>最后一只</b>时照旧打标（1.3 复审：单宠物账号删档后不打标会让旧卡重新出现在新宠物时间线 /
     * 未付费卡重新可见，复现 bug 20260828；最后一只删净时「全部卡都属于已删宠物」与改造前同义）。宠物不存在 / 不属于该用户 → 空媒体（调用方已经 {@code PetResolver} 校验）。
     */
    @Transactional
    public PersonalMedia deleteByPetId(long ownerId, long petId) {
        // 2.1 复审：先取账号锁再删行。否则删行留下的行锁与并发 reconcile / 改选持有的账号锁互相等待 → 死锁（40P01）。
        // xact 锁可重入，后面 reconcile 再取同一把锁不阻塞。
        petSlotState.lockAccount(ownerId);
        Optional<PersonalMedia> media = deletePetRows(ownerId, petId);
        if (media.isEmpty()) {
            return PersonalMedia.empty();
        }
        // 账号最后一只宠物删净 → 按账号打标（与改造前单宠物删档同效；AUTO flush 保证 exists 看到上面的 delete）。
        if (!petProfiles.existsByOwnerId(ownerId)) {
            idCards.markProfileDeleted(ownerId, Instant.now());
        } else {
            // V1.4.0 multi-pet Story 2.1（AC6）：名额释放 → 只读宠物按序恢复（同一事务、同一把账号锁）。
            // 删的是最后一只时无宠物可恢复，不加锁。删除只读宠物照常允许（不经 PetWriteGuard）。
            petSlotState.reconcile(ownerId, PetSlotTrigger.PET_DELETED);
        }
        return media.get();
    }

    /** 删一只宠物及其派生行（不打标）；宠物不存在 / 不属于该用户 → empty。 */
    private Optional<PersonalMedia> deletePetRows(long ownerId, long petId) {
        Optional<PetProfile> profile = petProfiles.findByIdAndOwnerId(petId, ownerId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        PetProfile pet = profile.get();

        List<String> privateKeys = new ArrayList<>();
        for (HealthEvent e : healthEvents.findByPetId(petId)) {
            if (e.getImageKeys() != null) {
                privateKeys.addAll(e.getImageKeys());
            }
        }
        healthEvents.deleteByPetId(petId);

        // 结构化健康记录硬删（Story 7.1 · PDP，个人健康数据不保留）。DB FK ON DELETE CASCADE 兜底，显式删更清晰。
        healthRecords.deleteByPetProfileId(petId);

        // 里程碑级联删除（Story 8.1，归 profile 域）：先删完成记录（FK→roster）→ 再删 roster。
        List<Long> milestoneIds = petMilestones.findByPetProfileIdOrderBySortOrderAsc(petId).stream()
                .map(PetMilestone::getId).toList();
        if (!milestoneIds.isEmpty()) {
            milestoneCompletions.deleteByPetMilestoneIdIn(milestoneIds);
        }
        petMilestones.deleteByPetProfileId(petId);
        // 里程碑对外分享（P-35 分享链接）：随档案删除，token 立即失效（防注销后仍可访问 H5）。
        milestoneShares.deleteByPetProfileId(petId);

        // 解绑成长帖（bug 20260702-237 / F18）：FK fk_content_posts_pet 无 ON DELETE，删 pet 前须先把
        // 引用它的 content_posts.pet_id 置 NULL，否则外键阻断（历史账号注销 deletionId=1 即栽在此）。
        // 帖子本体保留（UGC 保留），仅解除与已删宠物的绑定。
        contentPosts.detachPet(petId);
        // 场所打卡（V1.3.2 Story 1.1 · AD-17）：删该宠物的打卡关联，再删该用户已无关联宠物的打卡行。
        // 🔴 必须在 petProfiles.delete 之前：place_checkin_pets.pet_profile_id 对 pet_profiles 有 FK（无 ON DELETE）。
        // 多宠物下仍正确：只有全部关联宠物都删了的打卡行才是孤儿。
        placeCheckins.deleteForPet(petId, ownerId);
        // 宠物护照（V1.3.2 Story 1.2 · AD-17）：pet_passports.pet_profile_id 对 pet_profiles 有 FK，同样须先删。
        petPassports.deleteForPet(petId);
        // Tailsonality 结果（V1.3.2 Story 2.1 · AD-17）：tailsonality_results.pet_profile_id 对 pet_profiles 有 FK，同样须先删。
        tailsonality.deleteForPet(petId);

        List<String> publicUrls = new ArrayList<>();
        if (pet.getAvatarUrl() != null) {
            publicUrls.add(pet.getAvatarUrl());
        }
        if (pet.getOgImageUrl() != null) {
            publicUrls.add(pet.getOgImageUrl());
        }
        // 流水号回收（Story 6.1，FR-49A）：删除档案前把已分配的号释放回池，供后续档案复用。
        // 与级联删除同一 @Transactional → 原子（档案删了号必回池，不半途丢号 / 不撞号）。
        if (pet.getSerialId() != null) {
            serialAllocation.release(pet.getSerialId());
        }
        petProfiles.delete(pet);

        return Optional.of(new PersonalMedia(privateKeys, publicUrls));
    }

    /**
     * 注销路径（Story 7.3 · V1.4.0 multi-pet AD-9 末句）：逐只删除账号下全部宠物，合并待删个人图，
     * <b>最后</b>按账号给身份证打标。全部宠物删净后注销才不会被 {@code fk_pet_profiles_owner}（V3，无级联）挡住。
     */
    @Transactional
    public PersonalMedia deleteAllForAccount(long userId) {
        PersonalMedia media = PersonalMedia.empty();
        for (PetProfile pet : petProfiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId)) {
            media = media.merge(deletePetRows(userId, pet.getId()).orElse(PersonalMedia.empty()));
        }
        // 身份证卡打标（V108，2026-08-19 决策 F21）：卡快照不删（决策③旧卡保留），但记录「档案已删」。
        // 注销路径下 id_cards 行随 users FK 级联删除，本打标无害（与改造前注销路径同效）。
        idCards.markProfileDeleted(userId, Instant.now());
        return media;
    }
}
