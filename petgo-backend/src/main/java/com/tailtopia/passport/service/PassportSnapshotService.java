package com.tailtopia.passport.service;

import com.tailtopia.passport.domain.FrozenStamp;
import com.tailtopia.passport.domain.PassportSnapshot;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.dto.PassportSnapshotDetailResponse;
import com.tailtopia.passport.dto.PassportSnapshotListResponse;
import com.tailtopia.passport.dto.PassportSnapshotPurchaseResponse;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.place.domain.PlaceStampRef;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.service.PlaceIdentityQuery;
import com.tailtopia.place.service.PlaceStampQueryService;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PayException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 护照快照：发起购买 / 已购列表 / 回看（V1.3.2 Story 3.4 · AC3 / AC7）。
 *
 * <p>冻结在发起时（AD-7）：同一章集合（现算 hash）复用最新一行未付快照，其上的 PENDING 购买由 3-1 复用；
 * 已有同集合的已付快照 → 409 {@code keepsake-already-unlocked}。
 */
@Service
public class PassportSnapshotService {

    private final PetPassportService passports;
    private final PetPassportRepository passportRows;
    private final PassportSnapshotRepository snapshots;
    private final PlaceStampQueryService stamps;
    private final PlaceIdentityQuery places;
    private final PlaceSetHash hash;
    private final PetProfileQueryService pets;
    private final KeepsakePurchaseService purchases;
    private final PassportSnapshotTokenGenerator tokens;
    private final Clock clock;

    @Autowired
    public PassportSnapshotService(PetPassportService passports, PetPassportRepository passportRows,
            PassportSnapshotRepository snapshots, PlaceStampQueryService stamps, PlaceIdentityQuery places,
            PlaceSetHash hash, PetProfileQueryService pets, KeepsakePurchaseService purchases,
            PassportSnapshotTokenGenerator tokens) {
        this(passports, passportRows, snapshots, stamps, places, hash, pets, purchases, tokens, Clock.systemUTC());
    }

    PassportSnapshotService(PetPassportService passports, PetPassportRepository passportRows,
            PassportSnapshotRepository snapshots, PlaceStampQueryService stamps, PlaceIdentityQuery places,
            PlaceSetHash hash, PetProfileQueryService pets, KeepsakePurchaseService purchases,
            PassportSnapshotTokenGenerator tokens, Clock clock) {
        this.passports = passports;
        this.passportRows = passportRows;
        this.snapshots = snapshots;
        this.stamps = stamps;
        this.places = places;
        this.hash = hash;
        this.pets = pets;
        this.purchases = purchases;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 发起（AC3）。无宠物 → 404；无章 → 422；当前版本已买 → 409。
     *
     * <p>并发（AC3.5）：先 {@code SELECT … FOR UPDATE} 该宠物的<b>护照行</b>（{@code pet_passports}，签发后必在）——
     * 同宠两次同时发起串行化，只会建出一行未付快照。选护照行而不是宠物行：护照是本模块自己的表，不去锁别的模块的行。
     */
    // noRollbackFor 与 KeepsakePurchaseService.start 一致：网关下单失败时意图 FAILED 行要随事务提交留档。
    @Transactional(noRollbackFor = PayException.class)
    public PassportSnapshotPurchaseResponse start(long userId, PayChannel channel) {
        long petId = passports.ensureIssuedForOwner(userId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"))
                .subject().petId();
        passportRows.findForUpdateByPetProfileId(petId)
                .orElseThrow(() -> new IllegalStateException("护照签发后回读为空"));
        List<PlaceStampRef> current = stamps.stampRefsOf(petId);
        if (current.isEmpty()) {
            throw AppException.validation("还没有章，不能购买护照");
        }
        String now = hash.of(current.stream().map(PlaceStampRef::placeId).toList());
        for (PassportSnapshot paid : snapshots.findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(petId)) {
            if (hash.of(paid.placeIds()).equals(now)) {
                throw AppException.keepsakeAlreadyUnlocked("当前版本已解锁");
            }
        }
        PassportSnapshot snap = snapshots.findByPetProfileIdAndPaidAtIsNullOrderByCreatedAtDescIdDesc(petId).stream()
                .filter(s -> hash.of(s.placeIds()).equals(now))
                .findFirst()
                .orElseGet(() -> snapshots.saveAndFlush(PassportSnapshot.freeze(tokens.generate(), petId,
                        current.stream().map(PassportSnapshotService::freeze).toList(), now, Instant.now(clock))));
        KeepsakeRef ref = new KeepsakeRef(KeepsakeSku.PASSPORT_SNAP, snap.getId(), snap.getPublicToken(), petId,
                snap.getPaidAt() != null);
        return PassportSnapshotPurchaseResponse.of(purchases.start(userId, ref, channel), snap.getPublicToken());
    }

    /** 已购版本（AC7.1）：只列已付。无宠物 → 404。 */
    @Transactional(readOnly = true)
    public PassportSnapshotListResponse list(long userId) {
        long petId = requirePet(userId).petId();
        return new PassportSnapshotListResponse(
                snapshots.findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(petId).stream()
                        .map(s -> new PassportSnapshotListResponse.Item(s.getPublicToken(), s.getPaidAt(),
                                s.getStampCount()))
                        .toList());
    }

    /** 回看（AC7.2）：非本人 / 未付 / 不存在 → 同一个 404。读快照 JSON，不读实时打卡。 */
    @Transactional(readOnly = true)
    public PassportSnapshotDetailResponse get(long userId, String token) {
        PetPassportSubject pet = requirePet(userId);
        PassportSnapshot s = snapshots.findByPublicTokenAndPetProfileId(token, pet.petId())
                .filter(x -> x.getPaidAt() != null)
                .orElseThrow(() -> AppException.notFound("版本不存在"));
        List<FrozenStamp> frozen = s.frozenStamps();
        Map<Long, Long> finalOf = places.resolveFinal(frozen.stream().map(FrozenStamp::placeId).toList());
        Map<Long, String> urls = places.stampUrlsOf(finalOf.values());
        String passportNo = passportRows.findByPetProfileId(pet.petId()).map(PetPassport::getPassportNo).orElse(null);
        List<PassportSnapshotDetailResponse.Stamp> views = frozen.stream()
                .map(f -> new PassportSnapshotDetailResponse.Stamp(f.placeToken(), f.placeName(), typeOf(f.placeType()),
                        urls.get(finalOf.getOrDefault(f.placeId(), f.placeId())), f.firstVisitDate(), f.visitCount()))
                .toList();
        return new PassportSnapshotDetailResponse(s.getPublicToken(), s.getPaidAt(), s.getStampCount(), pet.name(),
                passportNo, views);
    }

    private PetPassportSubject requirePet(long userId) {
        return pets.findPassportSubject(userId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    private static FrozenStamp freeze(PlaceStampRef r) {
        var s = r.stamp();
        return new FrozenStamp(r.placeId(), s.placeToken(), s.placeName(),
                s.placeType() == null ? null : s.placeType().name(), s.firstVisitDate(), s.visitCount());
    }

    private static PlaceType typeOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PlaceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
