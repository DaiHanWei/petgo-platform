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
    private final CardNumberService cardNumbers;
    private final IdCardHdPurchaseRepository hdPurchases;
    private final IdCardRepository idCards;

    public IdCardService(PetProfileRepository profiles, SerialAllocationService serialAllocation,
            CardNumberService cardNumbers, IdCardHdPurchaseRepository hdPurchases,
            IdCardRepository idCards) {
        this.profiles = profiles;
        this.serialAllocation = serialAllocation;
        this.cardNumbers = cardNumbers;
        this.hdPurchases = hdPurchases;
        this.idCards = idCards;
    }

    // ---- Story 6-7：多卡快照 + 历史列表 + 独立建卡器 ----

    /** 历史卡列表（建卡时刻倒序）。档案已删的未付费卡不返回（见 {@link #visible}）。 */
    @Transactional(readOnly = true)
    public List<IdCardResponse> listMyCards(long ownerId) {
        return idCards.findByUserIdOrderByCreatedAtDesc(ownerId).stream()
                .filter(IdCardService::visible)
                .map(IdCardResponse::from).toList();
    }

    /** 单卡详情（归属校验，非本人 404 防枚举）。档案已删的未付费卡同 404（不可预览）。 */
    @Transactional(readOnly = true)
    public IdCardResponse getMyCard(long ownerId, long cardId) {
        return idCards.findByIdAndUserId(cardId, ownerId)
                .filter(IdCardService::visible)
                .map(IdCardResponse::from)
                .orElseThrow(() -> AppException.notFound("身份证卡不存在"));
    }

    /**
     * 卡可见性（V108，2026-08-19 决策 F21）：付费卡恒可见（哪怕档案已删/已换宠物，展示快照信息）；
     * 未付费卡（含等待付款/过期等一切非到账态）在档案删除后隐藏。到账回调
     * （{@code IdCardHdService.completeCardByIntent}，按 findById 不走本过滤）翻转 hdUnlocked
     * 后自动重新可见——防「删档时支付在途」的时间差丢卡。
     */
    private static boolean visible(IdCard card) {
        return card.isHdUnlocked() || card.getProfileDeletedAt() == null;
    }

    /**
     * 独立建卡器（决策④）：把入参信息冻结成一张新卡快照，分配新 serial（每卡新号，决策②）。初始未解锁。
     * 卡信息与档案解耦——不要求已有档案，也不改档案。ownerId 由 JWT 取。
     *
     * <p>spec-ktp-pet-idcode-numbering：legacy serial 照旧分配（号池不动），再按《宠物身份码护照编码规则》
     * 生成身份码 + 护照号一起落库；取号与落卡同事务（{@link CardNumberService} join 本事务，行锁保原子）。
     */
    @Transactional
    public IdCardResponse createCard(long ownerId, CreateIdCardRequest req) {
        String gender = normalizeGender(req.gender());
        long serial = serialAllocation.allocate();
        String cardNo = cardNumbers.allocateCardNo(req.birthday(), gender, req.petType());
        String passportNo = cardNumbers.allocatePassportNo(req.petType());
        IdCard card = idCards.save(IdCard.snapshot(ownerId, serial, req.name(), req.petType(),
                req.breed(), req.birthday(), req.avatarUrl(), req.intro(), gender, cardNo,
                passportNo, blankToNull(req.birthCity()), blankToNull(req.address()),
                blankToNull(req.occupation()), blankToNull(req.maritalStatus()),
                normalizeCardType(req.cardType()), blankToNull(req.school()),
                blankToNull(req.faculty())));
        return IdCardResponse.from(card);
    }

    /** gender 归一化：null 视同 UNKNOWN。白名单由 DTO 的 {@code @Pattern} 保证（非法值与其余字段统一 422）。 */
    private static String normalizeGender(String gender) {
        return gender == null ? "UNKNOWN" : gender;
    }

    /** cardType 归一化（bug 430）：null 视同 KTP（兼容未升级客户端）。白名单由 {@code @Pattern} 保证。 */
    private static String normalizeCardType(String cardType) {
        return cardType == null ? "KTP" : cardType;
    }

    /** 趣味字段空串折叠为 null（快照 null = 前端渲染趣味默认，避免空串盖掉默认展示）。 */
    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
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
