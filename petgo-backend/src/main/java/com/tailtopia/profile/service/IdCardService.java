package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.IdCardType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.StudentVariant;
import com.tailtopia.profile.dto.CreateIdCardRequest;
import com.tailtopia.profile.dto.IdCardCreateResponse;
import com.tailtopia.profile.dto.IdCardDataResponse;
import com.tailtopia.profile.dto.IdCardResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IdCardService.class);

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
     *
     * <p>V1.3.4 ktp（AD-1）：卡种 5 值 + 学生卡配色；intro 不写入；新卡种与旧卡种一样分配 serial + 两号。
     * 本方法是<b>旧接口</b>（1.3.3 及更早 App）：petType / gender / breed 可空（D-20），必填口径见 {@link #createCardV2}。
     * <p>V1.3.4 ktp Story 2.3（AD-5）：本人有已解锁学生卡且生日 / 性别 / 物种快照逐项相同 → 新卡（8 款任一）沿用其
     * card_no / passport_no（多张取最早解锁），{@code numberReused = true}；号仍经 {@code claimNumbers} 登记（AD-3）。
     */
    @Transactional
    public IdCardCreateResponse createCard(long ownerId, CreateIdCardRequest req) {
        return create(ownerId, req, false);
    }

    /**
     * 建卡 V2（V1.3.4 ktp D-20，1.3.4 起 App 调用）：与 {@link #createCard} 同一套建卡逻辑，另加 AD-4 必填——
     * petType / gender 必填，KTP / KITAS 的 breed 必填（缺失 422 {@code validation}）。
     * 旧接口 {@link #createCard} 留给 1.3.3 及更早 App，保持宽松（D-9 / AD-4 对旧版的影响因此不再发生）。
     */
    @Transactional
    public IdCardCreateResponse createCardV2(long ownerId, CreateIdCardRequest req) {
        return create(ownerId, req, true);
    }

    private IdCardCreateResponse create(long ownerId, CreateIdCardRequest req, boolean strict) {
        IdCardType cardType = normalizeCardType(req.cardType());
        String cardVariant = resolveVariant(cardType, req.cardVariant());
        if (strict) {
            requireStrictFields(cardType, req);
        }
        // 取值白名单由 DTO 保证；旧接口 gender 空视同 UNKNOWN（1.3.3 口径），V2 已保证非空。
        String gender = req.gender() == null ? "UNKNOWN" : req.gender();
        // AD-5：按用户串行建卡，再判「编号沿用」——同一用户并发两次建卡判定一致；
        // 解锁到账与建卡并发时以本事务读到的已提交状态为准（T-19）。
        cardNumbers.lockOwnerForCardCreation(ownerId);
        long serial = serialAllocation.allocate(); // serial_id 照常每卡新取（沿用也不例外）
        Optional<IdCard> source =
                idCards.findNumberSourceCard(ownerId, req.birthday(), gender, req.petType());
        // 命中已解锁学生卡（三项相同）→ 复制两个号、不调发号器（计数器不前进）；只共号、不共解锁。
        String cardNo = source.map(IdCard::getCardNo)
                .orElseGet(() -> cardNumbers.allocateCardNo(req.birthday(), gender, req.petType()));
        String passportNo = source.map(IdCard::getPassportNo)
                .orElseGet(() -> cardNumbers.allocatePassportNo(req.petType()));
        // AD-3：登记号的归属（同一用户可共用、跨用户不可），他人已持有 → 抛错整笔回滚（T-17）。
        cardNumbers.claimNumbers(ownerId, cardNo, passportNo);
        // intro：DTO 字段为兼容旧版 App 保留，一律不写入（PRD §3.4 / FR-142D.4）。
        IdCard card = idCards.save(IdCard.snapshot(ownerId, serial, req.name(), req.petType(),
                req.breed(), req.birthday(), req.avatarUrl(), null, gender, cardNo,
                passportNo, blankToNull(req.birthCity()), blankToNull(req.address()),
                blankToNull(req.occupation()), blankToNull(req.maritalStatus()),
                cardType.name(), cardVariant, blankToNull(req.school()),
                blankToNull(req.faculty())));
        source.ifPresent(src -> log.info("身份证编号沿用 card={} source={}", card.getId(), src.getId()));
        return IdCardCreateResponse.of(IdCardResponse.from(card), source.isPresent());
    }

    /** V2 必填（AD-4 / D-17）：物种、性别；KTP / 居住证另加品种。 */
    private static void requireStrictFields(IdCardType cardType, CreateIdCardRequest req) {
        if (req.petType() == null) {
            throw AppException.validation("物种必填");
        }
        if (req.gender() == null) {
            throw AppException.validation("性别必填");
        }
        if ((cardType == IdCardType.KTP || cardType == IdCardType.KITAS)
                && (req.breed() == null || req.breed().isBlank())) {
            throw AppException.validation("该卡种品种必填");
        }
    }

    /**
     * 学生卡配色（AD-1 / D-12）：学生卡缺省 KLASIK（旧版 App 只有紫色一款）；非学生卡带配色 → 422。
     * 白名单由 DTO 的 {@code @Pattern} 保证。
     */
    private static String resolveVariant(IdCardType cardType, String cardVariant) {
        if (cardType == IdCardType.STUDENT) {
            return cardVariant == null ? StudentVariant.KLASIK.name() : cardVariant;
        }
        if (cardVariant != null) {
            throw AppException.validation("仅学生卡可指定配色");
        }
        return null;
    }

    /** cardType 归一化（bug 430）：null 视同 KTP（兼容未升级客户端）。白名单由 {@code @Pattern} 保证。 */
    private static IdCardType normalizeCardType(String cardType) {
        return cardType == null ? IdCardType.KTP : IdCardType.valueOf(cardType);
    }

    /** 趣味字段空串折叠为 null（快照 null = 前端渲染趣味默认，避免空串盖掉默认展示）。 */
    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 当前用户身份证数据（只读，不分配号）。无档案 → 404。老用户 serial=null → {@code generated=false}。
     * V1.4.0 multi-pet（Story 1.2 · P2）：按指定宠物（控制器经 {@code PetResolver} 解析）；高清已购仍按账号。
     */
    @Transactional(readOnly = true)
    public IdCardDataResponse getMyIdCard(long ownerId, long petId) {
        return profiles.findByIdAndOwnerId(petId, ownerId)
                .map(p -> IdCardDataResponse.from(p, hdPurchases.existsPaidByUserId(ownerId)))
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    /**
     * 生成身份证（分配流水号）。幂等：已有号则原样返回（不重复分配、不换号）；无号则分配后落库。无档案 → 404。
     * V1.4.0 multi-pet（Story 1.2 · P3）：流水号挂 {@code pet_profiles.serial_id}，按指定宠物。
     */
    @Transactional
    public IdCardDataResponse generateSerial(long ownerId, long petId) {
        PetProfile pet = profiles.findByIdAndOwnerId(petId, ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        if (pet.getSerialId() == null) {
            long serial = serialAllocation.allocate();
            pet.assignSerial(serial);
            profiles.save(pet);
        }
        return IdCardDataResponse.from(pet, hdPurchases.existsPaidByUserId(ownerId));
    }
}
