package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物档案只读查询端口（Story 6.7）。供 notify 定时推送扫描跨模块只读取档案快照，
 * 不暴露写逻辑、不让 notify 直访 {@code PetProfileRepository}（保持模块边界）。
 */
@Service
public class PetProfileQueryService {

    private static final Sort CREATED_ASC = Sort.by("createdAt", "id");

    private final PetProfileRepository petProfiles;
    private final IdCardRepository idCards;

    public PetProfileQueryService(PetProfileRepository petProfiles, IdCardRepository idCards) {
        this.petProfiles = petProfiles;
        this.idCards = idCards;
    }

    /**
     * 全量档案快照（定时推送日扫用）。≤500 DAU 单机日扫足够；建档日期按 UTC 折算。
     * V1.4.0 multi-pet（Story 1.2 · Q1）：按 {@code created_at, id} 升序（{@code LifecyclePushJob} 依赖顺序）。
     */
    @Transactional(readOnly = true)
    public List<PetProfileSnapshot> allSnapshots() {
        return petProfiles.findAll(CREATED_ASC).stream()
                .map(p -> new PetProfileSnapshot(
                        p.getId(),
                        p.getOwnerId(),
                        p.getName(),
                        p.getBirthday(),
                        p.getCreatedAt() == null ? null : p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate()))
                .toList();
    }

    /**
     * 每个账号默认宠物的名字（V1.4.0 multi-pet Story 1.2 · AD-13：生命周期推送文案固定用默认宠物）。
     * 全量按创建时间取 + {@link DefaultPetPolicy#pickPerOwner}。<b>键存在即已建档</b>；名字缺失记 {@code ""}。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> defaultPetNameByOwner() {
        Map<Long, String> out = new HashMap<>();
        DefaultPetPolicy.pickPerOwner(petProfiles.findAll(CREATED_ASC))
                .forEach((owner, p) -> out.put(owner, p.getName() == null ? "" : p.getName()));
        return out;
    }

    /**
     * 按宠物 id 取身份摘要（兽医工作台：问诊按单上的宠物，V1.4.0 multi-pet AD-2）。
     * {@code petId} 为 null（单上未挂宠物）或宠物已删（{@code pet_profile_id} 无外键）→ empty，调用方兜底。
     * 月龄按 UTC 今日与生日折算的整月数（生日缺失则 null）。
     */
    @Transactional(readOnly = true)
    public Optional<PetIdentityView> findIdentityByPetId(Long petId) {
        if (petId == null) {
            return Optional.empty();
        }
        return petProfiles.findById(petId).map(PetProfileQueryService::toIdentity);
    }

    /**
     * 账号默认宠物的身份摘要（无单号宠物时的兜底，如 V1.0 无订单的免费会话）。
     * {@code ownerId} 为 null（注销匿名化后会话已剥 user_id）→ empty。
     */
    @Transactional(readOnly = true)
    public Optional<PetIdentityView> findDefaultIdentityByOwner(Long ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        return petProfiles.findDefault(ownerId).map(PetProfileQueryService::toIdentity);
    }

    /**
     * 账号名下全部宠物 id，按创建时间升序（V1.3.2 Story 1.1 场所打卡归属校验；V1.4.0 multi-pet 改列表）。
     * place 包据此校验 {@code petIds} 全部属于本人，不直接注入 {@code PetProfileRepository}。
     */
    @Transactional(readOnly = true)
    public List<Long> findPetIdsByOwner(long ownerId) {
        return petProfiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId).stream()
                .map(PetProfile::getId)
                .toList();
    }

    /**
     * 账号名下正常（{@code ACTIVE}）宠物 id，按创建时间升序（V1.4.0 multi-pet Story 2.1）：场所详情「全部宠物都已打卡」
     * 只算正常宠物（只读宠物不能打卡，不应让按钮永远停在「可打卡」）。单宠物账号恒 = {@link #findPetIdsByOwner}。
     */
    @Transactional(readOnly = true)
    public List<Long> findActivePetIdsByOwner(long ownerId) {
        return petProfiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId).stream()
                .filter(p -> !p.isReadOnly())
                .map(PetProfile::getId)
                .toList();
    }

    /**
     * 账号名下宠物的 id + 物种（V1.3.2 Story 2.1：Tailsonality 按物种选题套）。
     * V1.4.0 multi-pet：{@code petId} 非空 → 校验归属（不属于本人 / 不存在 → empty，调用方 404）；
     * null → 默认宠物。tailsonality 包据此取宠物，不直接注入 {@code PetProfileRepository}。
     */
    @Transactional(readOnly = true)
    public Optional<OwnedPetRef> findOwnedPet(long ownerId, Long petId) {
        return ownedOrDefault(ownerId, petId).map(p -> new OwnedPetRef(p.getId(), p.getPetType()));
    }

    /**
     * 护照签发用的宠物摘要（V1.3.2 Story 1.2 · AD-6）。规则同 {@link #findOwnedPet}：
     * 带 petId 校验归属，不带取默认宠物。
     */
    @Transactional(readOnly = true)
    public Optional<PetPassportSubject> findPassportSubject(long ownerId, Long petId) {
        return ownedOrDefault(ownerId, petId).map(PetProfileQueryService::toSubject);
    }

    /** 按宠物 id 取护照摘要（护照签发按 petId 用，归属由调用方经 {@link #ownsPet} 校验）。 */
    @Transactional(readOnly = true)
    public Optional<PetPassportSubject> findPassportSubjectByPetId(long petId) {
        return petProfiles.findById(petId).map(PetProfileQueryService::toSubject);
    }

    /** 宠物是否属于该账号（tailsonality / passport / place 在 token 反查宠物后校验归属）。 */
    @Transactional(readOnly = true)
    public boolean ownsPet(long ownerId, long petId) {
        return petProfiles.existsByIdAndOwnerId(petId, ownerId);
    }

    /**
     * 可沿用的 KTP 护照号（V1.3.2 Story 1.2 · D-6）：条件全在 SQL 里（见
     * {@link IdCardRepository#findReusableKtpPassportNo}）。<b>只读</b>，不回写 {@code id_cards}。
     * 在调用方（签发）事务内执行。
     */
    @Transactional(readOnly = true)
    public Optional<String> findReusableKtpPassportNo(long userId, java.time.Instant petCreatedAt,
            String petType) {
        return idCards.findReusableKtpPassportNo(userId, petCreatedAt, CardNumberService.speciesCodeOf(petType));
    }

    /**
     * 批量按宠物 id 取身份（兽医工作台列表富化，问诊按单上的宠物，避免逐条 N+1）。返回 {@code petId → 身份}；
     * null id 忽略，查不到的宠物（已删）不入 map（调用方按 key 缺失兜底）。
     */
    @Transactional(readOnly = true)
    public Map<Long, PetIdentityView> findIdentitiesByPetIds(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = petIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, PetIdentityView> out = new HashMap<>();
        for (PetProfile p : petProfiles.findAllById(ids)) {
            out.put(p.getId(), toIdentity(p));
        }
        return out;
    }

    /**
     * 批量取多账号<b>默认宠物</b>身份（无单号宠物时兜底）。返回 {@code ownerId → 身份}；
     * null 账号忽略，无宠物的账号不入 map。默认宠物定义只经 {@link DefaultPetPolicy#pickPerOwner}。
     */
    @Transactional(readOnly = true)
    public Map<Long, PetIdentityView> findDefaultIdentitiesByOwners(Collection<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = ownerIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, PetIdentityView> out = new HashMap<>();
        DefaultPetPolicy.pickPerOwner(petProfiles.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(ids))
                .forEach((owner, p) -> out.put(owner, toIdentity(p)));
        return out;
    }

    private Optional<PetProfile> ownedOrDefault(long ownerId, Long petId) {
        if (petId != null) {
            return petProfiles.findByIdAndOwnerId(petId, ownerId);
        }
        return petProfiles.findDefault(ownerId);
    }

    private static PetPassportSubject toSubject(PetProfile p) {
        return new PetPassportSubject(p.getId(), p.getName(), p.getPetType().name(), p.getCreatedAt(), p.getBreed());
    }

    private static PetIdentityView toIdentity(PetProfile p) {
        return new PetIdentityView(p.getName(), p.getPetType().name(), ageMonths(p.getBirthday()));
    }

    /** 整月龄：UTC 今日与生日之间的完整月数（生日缺失 null；未来生日钳为 0，绝不为负）。 */
    private static Integer ageMonths(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(birthday, LocalDate.now(ZoneOffset.UTC));
        return (int) Math.max(0L, months);
    }
}
