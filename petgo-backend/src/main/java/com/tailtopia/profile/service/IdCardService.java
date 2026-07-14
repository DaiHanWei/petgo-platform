package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.IdCardDataResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物身份证数据服务（Story 6.1，FR-49A）。
 *
 * <p>惰性分配：{@link #getMyIdCard} 只读、绝不分配号（保证老用户「尚未生成」引导态持续可见，直到主动生成）；
 * {@link #generateSerial} 才分配号，且幂等——已有号则原样返回（不重复分配、不换号，避免 {@code pet_serial_seq}
 * 空耗）。{@code ownerId} 一律由调用方从 JWT 取，绝不信任客户端传入。无档案 → 404。
 */
@Service
public class IdCardService {

    private final PetProfileRepository profiles;
    private final SerialAllocationService serialAllocation;
    private final IdCardHdPurchaseRepository hdPurchases;

    public IdCardService(PetProfileRepository profiles, SerialAllocationService serialAllocation,
            IdCardHdPurchaseRepository hdPurchases) {
        this.profiles = profiles;
        this.serialAllocation = serialAllocation;
        this.hdPurchases = hdPurchases;
    }

    /** 当前用户身份证数据（只读，不分配号）。无档案 → 404。老用户 serial=null → {@code generated=false}。 */
    @Transactional(readOnly = true)
    public IdCardDataResponse getMyIdCard(long ownerId) {
        return profiles.findByOwnerId(ownerId)
                .map(p -> IdCardDataResponse.from(p, hdPurchases.existsByUserId(ownerId)))
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    /**
     * 生成身份证（分配流水号）。幂等：已有号则原样返回（不重复分配、不换号）；无号则分配后落库。无档案 → 404。
     */
    @Transactional
    public IdCardDataResponse generateSerial(long ownerId) {
        PetProfile pet = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        if (pet.getSerialId() == null) {
            long serial = serialAllocation.allocate();
            pet.assignSerial(serial);
            profiles.save(pet);
        }
        return IdCardDataResponse.from(pet, hdPurchases.existsByUserId(ownerId));
    }
}
