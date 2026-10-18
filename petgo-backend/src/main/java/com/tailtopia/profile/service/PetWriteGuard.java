package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只读拦截（V1.4.0 multi-pet Story 2.1 · AD-8）：宠物 {@code READ_ONLY} → 409 {@code pet-read-only}（扩展成员 {@code petId}）。
 *
 * <p>🔴 可写校验<b>只经本类</b>（架构 §5）。调用顺序固定：<b>先</b>归属校验（{@code PetResolver} / {@code requireOwned}，
 * 不属于本人 404）<b>再</b> {@link #requireWritable}，不让只读校验泄露他人宠物存在性。宠物查不到时本类放行
 * （归属 / 存在性由调用方在前面判）。单宠物账号恒 {@code ACTIVE}（默认名额 ≥ 1），本类对其恒通过。
 */
@Service
public class PetWriteGuard {

    private final PetProfileRepository profiles;

    public PetWriteGuard(PetProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** 按宠物 id 校验可写（同一事务内一级缓存命中，通常不多一次查询）。 */
    @Transactional(readOnly = true)
    public void requireWritable(long petId) {
        profiles.findById(petId).ifPresent(this::requireWritable);
    }

    /** 已取到实体时直接校验（不再查库）。 */
    public void requireWritable(PetProfile pet) {
        if (pet != null && pet.getStatus() == PetStatus.READ_ONLY) {
            throw AppException.petReadOnly(pet.getId());
        }
    }
}
