package com.tailtopia.passport.service;

import com.tailtopia.passport.domain.BoardingPassUnlock;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.dto.BoardingPassDetailResponse;
import com.tailtopia.passport.dto.BoardingPassListResponse;
import com.tailtopia.passport.repository.BoardingPassUnlockRepository;
import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceStampRef;
import com.tailtopia.place.service.PlaceIdentityQuery;
import com.tailtopia.place.service.PlaceStampQueryService;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PayException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登机牌列表 / 详情 / 单张解锁（V1.3.2 Story 3.5 · AC3–AC5）。
 *
 * <p>粒度 = 宠物 × 当前场所：卡就是 {@link PlaceStampQueryService#stampRefsOf} 的一行（按当前 place_id 聚合，
 * 合并后自动一张、下架仍在）；解锁态看 {@code boarding_pass_unlocks}（已付且未 superseded）。
 */
@Service
public class BoardingPassService {

    private final PetProfileQueryService pets;
    private final PetPassportRepository passportRows;
    private final PlaceStampQueryService stamps;
    private final PlaceIdentityQuery places;
    private final BoardingPassUnlockRepository unlocks;
    private final KeepsakePurchaseService purchases;
    private final PassportTokenGenerator tokens;

    public BoardingPassService(PetProfileQueryService pets, PetPassportRepository passportRows,
            PlaceStampQueryService stamps, PlaceIdentityQuery places, BoardingPassUnlockRepository unlocks,
            KeepsakePurchaseService purchases, PassportTokenGenerator tokens) {
        this.pets = pets;
        this.passportRows = passportRows;
        this.stamps = stamps;
        this.places = places;
        this.unlocks = unlocks;
        this.purchases = purchases;
        this.tokens = tokens;
    }

    /** 列表（AC3）：按最近到访倒序；无打卡 → 空表。无宠物 → 404。 */
    @Transactional(readOnly = true)
    public BoardingPassListResponse list(long userId) {
        PetPassportSubject pet = requirePet(userId);
        Set<Long> unlocked = unlocks.findByPetProfileId(pet.petId()).stream()
                .filter(BoardingPassUnlock::isUnlocked).map(BoardingPassUnlock::getPlaceId).collect(Collectors.toSet());
        List<BoardingPassListResponse.Item> items = stamps.stampRefsOf(pet.petId()).stream()
                .sorted(Comparator.comparing(PlaceStampRef::lastVisitDate).reversed())
                .map(r -> {
                    PlaceStamp s = r.stamp();
                    return new BoardingPassListResponse.Item(s.placeToken(), s.placeName(), s.placeType(),
                            s.availability(), s.stampImageUrl(), r.lastVisitDate(), s.visitCount(),
                            unlocked.contains(r.placeId()));
                })
                .toList();
        return new BoardingPassListResponse(pet.name(), passportNoOf(pet.petId()), items);
    }

    /** 详情（AC4）：MERGED → 保留方；DELISTED 照常；该宠物在该场所无打卡 / 未知 token → 404。 */
    @Transactional(readOnly = true)
    public BoardingPassDetailResponse detail(long userId, String placeToken) {
        PetPassportSubject pet = requirePet(userId);
        PlaceStampRef card = requireCard(pet.petId(), placeToken);
        PlaceStamp s = card.stamp();
        boolean active = s.availability() == PlaceAvailability.ACTIVE;
        var info = places.cardInfoOf(card.placeId()).orElse(null);
        BoardingPassUnlock row = unlocks.findByPetProfileIdAndPlaceId(pet.petId(), card.placeId()).orElse(null);
        return new BoardingPassDetailResponse(s.placeToken(), pet.name(), pets.findBreed(userId).orElse(null),
                s.placeName(), passportNoOf(pet.petId()), card.lastVisitDate(), s.firstVisitDate(), s.visitCount(),
                BoardingPassSeat.of(pet.petId(), card.placeId()), s.placeType(), s.availability(),
                info == null ? null : info.firstPhotoUrl(), s.stampImageUrl(),
                active ? s.addressText() : null, active && info != null ? info.city() : null,
                row != null && row.isUnlocked(), row == null ? null : row.getPublicToken());
    }

    /**
     * 单张解锁（AC5）：upsert 解锁行 → 加锁读 → {@link KeepsakePurchaseService#start}。已解锁 → 409。
     * DELISTED 允许购买（卡仍在）。已解锁后再到访只更新聚合次数，不产生任何新购买。
     */
    // noRollbackFor 与 start 一致：网关下单失败时意图 FAILED 行要随事务提交留档。
    @Transactional(noRollbackFor = PayException.class)
    public KeepsakePurchaseResponse unlock(long userId, String placeToken, PayChannel channel) {
        PetPassportSubject pet = requirePet(userId);
        PlaceStampRef card = requireCard(pet.petId(), placeToken);
        // 🔴 复审：与场所合并互斥。先对最终场所行加 FOR SHARE 并复核未被并掉 —— 否则合并把旧行改挂到保留方后，
        // 这里的 insert 不再冲突、在被并方下建出一行新的活动行，用户付了钱卡却挂在保留方上看不到（或重复付款不被标记）。
        if (!places.lockIfFinal(card.placeId())) {
            card = requireCard(pet.petId(), placeToken); // 合并刚提交：重解析到保留方
            if (!places.lockIfFinal(card.placeId())) {
                throw AppException.conflict("场所正在调整，请稍后重试");
            }
        }
        unlocks.insertIfAbsent(pet.petId(), card.placeId(), tokens.generate());
        BoardingPassUnlock row = unlocks.findForUpdate(pet.petId(), card.placeId())
                .orElseThrow(() -> new IllegalStateException("登机牌解锁行插入后回读为空"));
        KeepsakeRef ref = new KeepsakeRef(KeepsakeSku.BOARDING_PASS, row.getId(), row.getPublicToken(), pet.petId(),
                row.isUnlocked());
        return purchases.start(userId, ref, channel);
    }

    private PlaceStampRef requireCard(long petId, String placeToken) {
        long placeId = places.findIdByToken(placeToken).orElseThrow(() -> AppException.notFound("登机牌不存在"));
        long finalId = places.resolveFinal(List.of(placeId)).getOrDefault(placeId, placeId);
        return stamps.stampRefsOf(petId).stream().filter(r -> r.placeId() == finalId).findFirst()
                .orElseThrow(() -> AppException.notFound("登机牌不存在"));
    }

    private String passportNoOf(long petId) {
        return passportRows.findByPetProfileId(petId).map(PetPassport::getPassportNo).orElse(null);
    }

    private PetPassportSubject requirePet(long userId) {
        return pets.findPassportSubject(userId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }
}
