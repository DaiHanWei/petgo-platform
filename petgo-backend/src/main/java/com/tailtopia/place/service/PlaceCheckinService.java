package com.tailtopia.place.service;

import com.tailtopia.place.domain.GeoBox;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceCheckin;
import com.tailtopia.place.domain.PlaceCheckinPet;
import com.tailtopia.place.dto.PlaceCheckinRequest;
import com.tailtopia.place.dto.PlaceCheckinResponse;
import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.service.PetPassportService;
import com.tailtopia.place.repository.PlaceCheckinPetRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.place.repository.PlaceVisitRepository;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所打卡（V1.3.2 batch-a Story 1.1 · FR-112 §8 · 架构 delta AD-4）。
 *
 * <p>🔴 <b>不放进 {@code PlaceService}</b>：{@code PlaceControllerNoEditEndpointTest} 用反射禁止
 * {@code PlaceService} 出现 {@code delete*} / {@code remove*} / {@code update*} 公有方法；打卡的删档方法
 * 也在独立类 {@link PlaceCheckinDeletionService}。
 *
 * <h2>🛡 坐标红线（NFR-1 / AD-4）</h2>
 * 请求坐标<b>只在 {@link #checkIn} 里用来算一次距离</b>：不落库、不进日志、不进埋点、不进异常文案。
 * 本类<b>没有任何 logger</b> —— 有一条源码扫描测试钉着（{@code PlaceCheckinPrivacyGuardTest}）。
 *
 * <h2>判定顺序</h2>
 * 场所解析（404）→ 坐标合法（422）→ 宠物归属（422 无宠物 / 403 非本人）→ 距离 ≤500m（422，不回距离）
 * → 今日已打卡（409）→ 写入（唯一约束并发冲突同样转 409）。
 */
@Service
public class PlaceCheckinService {

    /** 到场判定半径（米，D-7：服务端判定）。 */
    static final double MAX_DISTANCE_METERS = 500d;

    /** 「每天限一次」的日界：雅加达自然日（D-7）。 */
    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final PlaceRepository places;
    private final PlaceVisitRepository checkins;
    private final PlaceCheckinPetRepository checkinPets;
    private final PetProfileQueryService pets;
    private final PlaceTokenGenerator tokens;
    private final PetPassportService passports;
    private final PlaceStampQueryService stamps;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public PlaceCheckinService(PlaceRepository places, PlaceVisitRepository checkins,
            PlaceCheckinPetRepository checkinPets, PetProfileQueryService pets,
            PlaceTokenGenerator tokens, PetPassportService passports, PlaceStampQueryService stamps,
            ApplicationEventPublisher events) {
        this(places, checkins, checkinPets, pets, tokens, passports, stamps, events, Clock.systemUTC());
    }

    PlaceCheckinService(PlaceRepository places, PlaceVisitRepository checkins,
            PlaceCheckinPetRepository checkinPets, PetProfileQueryService pets,
            PlaceTokenGenerator tokens, PetPassportService passports, PlaceStampQueryService stamps,
            ApplicationEventPublisher events, Clock clock) {
        this.places = places;
        this.checkins = checkins;
        this.checkinPets = checkinPets;
        this.pets = pets;
        this.tokens = tokens;
        this.passports = passports;
        this.stamps = stamps;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 打卡（AC2）。
     *
     * @param token  场所 token；MERGED → 记到保留方，DELISTED / 删除 / 不存在 → 404（刻意不可区分）
     * @param userId 当前用户（{@code role=USER}，SecurityConfig 已限定）
     */
    @Transactional
    public PlaceCheckinResponse checkIn(String token, long userId, PlaceCheckinRequest req) {
        Place place = places.resolveForView(token)
                .orElseThrow(() -> AppException.notFound("场所不存在"));
        if (req == null || req.latitude() == null || req.longitude() == null
                || !GeoBox.isValidCoordinate(req.latitude(), req.longitude())) {
            // 🔴 固定文案：不回显坐标值。
            throw AppException.validation("坐标缺失或超出合法范围");
        }
        List<Long> petIds = ownedPetIds(userId, req.petIds());

        double meters = GeoBox.distanceMeters(req.latitude(), req.longitude(),
                place.getLatitude(), place.getLongitude());
        if (meters > MAX_DISTANCE_METERS) {
            // 🔴 detail 固定文案、不带距离值（防用户试探边界，AC2.4）。
            throw AppException.checkinTooFar("不在场所范围内");
        }

        Instant now = clock.instant();
        LocalDate today = now.atZone(WIB).toLocalDate();
        long placeId = place.getId();
        // 业务判定按**当前** place_id（含已合并进来的原场所历史）；DB 约束按 origin_place_id 只兜并发。
        for (Long petId : petIds) {
            if (checkins.existsForPetOnDay(petId, placeId, today)) {
                throw AppException.checkinAlreadyToday("今天已在这里打过卡");
            }
        }
        // 本版本单宠：isNewStamp / visitCount 按首只宠物算（多宠界面属 Deferred）。
        long primaryPet = petIds.get(0);
        boolean isNewStamp = checkins.countForPetAtPlace(primaryPet, placeId) == 0;

        PlaceCheckin saved;
        try {
            saved = checkins.saveAndFlush(
                    PlaceCheckin.create(tokens.generate(), placeId, userId, now, today));
            for (Long petId : petIds) {
                checkinPets.saveAndFlush(PlaceCheckinPet.of(saved, petId));
            }
        } catch (DataIntegrityViolationException e) {
            // 🔴 预查挡不住并发（两次点击同时过了预查）：后到的撞
            // uq_place_checkin_pets_pet_place_day。转 409 而不是 500（照 PlaceService.report 的教训）；
            // 抛出即整笔回滚，不留半截打卡行。
            // ⚠️ 只认这一条约束：并发删档撞 FK / token 碰撞等不是「今天已打卡」，照常抛出 ——
            // 否则客户端会把按钮切成「已打卡」而库里什么都没写。
            if (isDailyUniqueViolation(e)) {
                throw AppException.checkinAlreadyToday("今天已在这里打过卡");
            }
            throw e;
        }
        long visitCount = checkins.countForPetAtPlace(primaryPet, placeId);
        // Story 1.2：首次打卡同一事务内签发护照（幂等）；章数 = 不同当前 place_id 数。
        PetPassport passport = passports.ensureIssued(primaryPet, userId);
        int stampCount = stamps.stampCountOf(primaryPet);
        events.publishEvent(new PlaceCheckedInEvent(userId, place.getPublicToken(), place.getType(),
                isNewStamp, stampCount));
        return new PlaceCheckinResponse(saved.getPublicToken(), place.getPublicToken(),
                place.getName(), place.getType(), today, isNewStamp, visitCount,
                passport.getPassportNo(), stampCount, stamps.stampUrlOf(place.getStampObjectKey()));
    }

    /**
     * 详情页「今日已打卡」（AC3）：本人任一宠物今天（WIB）在该场所是否已打卡。
     * 游客不调本方法（字段省略）。
     */
    @Transactional(readOnly = true)
    public boolean checkedInToday(long userId, long placeId) {
        return checkins.existsForUserOnDay(userId, placeId, clock.instant().atZone(WIB).toLocalDate());
    }

    /** 唯一约束名（与迁移 V20260930_1131 同名）。 */
    static final String DAILY_UNIQUE_CONSTRAINT = "uq_place_checkin_pets_pet_place_day";

    /** 异常链上是否是「每日唯一」那条约束（Hibernate 给约束名；拿不到时按消息文本兜底）。 */
    static boolean isDailyUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().equalsIgnoreCase(DAILY_UNIQUE_CONSTRAINT);
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains(DAILY_UNIQUE_CONSTRAINT)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * 校验 petIds（AC2.3）：账号无宠物 → 422 {@code checkin-no-pet}；为空或含非本人宠物 → 403
     * {@code checkin-pet-forbidden}。返回去重后的列表（保持请求顺序）。
     */
    private List<Long> ownedPetIds(long userId, List<Long> requested) {
        Optional<Long> own = pets.findPetIdByOwner(userId);
        if (own.isEmpty()) {
            throw AppException.checkinNoPet("还没有宠物档案");
        }
        if (requested == null || requested.isEmpty()) {
            throw AppException.checkinPetForbidden("宠物不属于当前用户");
        }
        Set<Long> unique = new LinkedHashSet<>(requested);
        if (unique.contains(null) || !Set.of(own.get()).containsAll(unique)) {
            throw AppException.checkinPetForbidden("宠物不属于当前用户");
        }
        return List.copyOf(unique);
    }
}
