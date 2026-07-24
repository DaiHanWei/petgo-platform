package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.CreateIdCardRequest;
import com.tailtopia.profile.dto.IdCardDataResponse;
import com.tailtopia.profile.dto.IdCardResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
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
    private final IdCardRepository idCards;

    public IdCardService(PetProfileRepository profiles, SerialAllocationService serialAllocation,
            IdCardHdPurchaseRepository hdPurchases, IdCardRepository idCards) {
        this.profiles = profiles;
        this.serialAllocation = serialAllocation;
        this.hdPurchases = hdPurchases;
        this.idCards = idCards;
    }

    // ---- Story 6-7：多卡快照 + 历史列表 + 独立建卡器 ----

    /** 历史卡列表（建卡时刻倒序）。 */
    @Transactional(readOnly = true)
    public List<IdCardResponse> listMyCards(long ownerId) {
        return idCards.findByUserIdOrderByCreatedAtDesc(ownerId).stream()
                .map(IdCardResponse::from).toList();
    }

    /** 单卡详情（归属校验，非本人 404 防枚举）。 */
    @Transactional(readOnly = true)
    public IdCardResponse getMyCard(long ownerId, long cardId) {
        return idCards.findByIdAndUserId(cardId, ownerId)
                .map(IdCardResponse::from)
                .orElseThrow(() -> AppException.notFound("身份证卡不存在"));
    }

    /**
     * 独立建卡器（决策④）：把入参信息冻结成一张新卡快照，分配新 serial（每卡新号，决策②）。初始未解锁。
     * 卡信息与档案解耦——不要求已有档案，也不改档案。ownerId 由 JWT 取。
     */
    @Transactional
    public IdCardResponse createCard(long ownerId, CreateIdCardRequest req) {
        long serial = serialAllocation.allocate();
        IdCard card = idCards.save(IdCard.snapshot(ownerId, serial,
                com.tailtopia.profile.domain.CardType.fromNullable(req.cardType()), req.name(),
                req.petType(), req.breed(), req.birthday(), req.avatarUrl(), req.intro()));
        return IdCardResponse.from(card);
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
