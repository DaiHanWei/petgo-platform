package com.tailtopia.passport.service;

import com.tailtopia.passport.domain.PassportSource;
import com.tailtopia.passport.dto.PassportStampView;
import com.tailtopia.passport.dto.PetPassportResponse;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.event.PassportIssuedEvent;
import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.service.CardNumberService;
import com.tailtopia.place.service.PlaceStampQueryService;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物护照签发（V1.3.2 batch-a Story 1.2 · AD-6 / D-6）。
 *
 * <p>唯一入口 {@link #ensureIssued}：<b>首次打开护照页</b>（GET 护照接口）与<b>首次打卡</b>（打卡事务内）
 * 调用同一个方法，已签发则原样返回。
 *
 * <p>号源：本宠 KTP 卡护照号可沿用 → {@code KTP}；否则 {@link CardNumberService#allocatePassportNo}
 * 与 KTP 共用计数器新发 → {@code ISSUED}。<b>只写 {@code pet_passports}，绝不 UPDATE {@code id_cards}</b>。
 */
@Service
public class PetPassportService {

    private final PetPassportRepository passports;
    private final PetProfileQueryService pets;
    private final CardNumberService numbers;
    private final ApplicationEventPublisher events;
    private final PlaceStampQueryService stamps;
    private final Clock clock;

    @Autowired
    public PetPassportService(PetPassportRepository passports, PetProfileQueryService pets,
            CardNumberService numbers, ApplicationEventPublisher events, PlaceStampQueryService stamps) {
        this(passports, pets, numbers, events, stamps, Clock.systemUTC());
    }

    PetPassportService(PetPassportRepository passports, PetProfileQueryService pets,
            CardNumberService numbers, ApplicationEventPublisher events, PlaceStampQueryService stamps,
            Clock clock) {
        this.passports = passports;
        this.pets = pets;
        this.numbers = numbers;
        this.events = events;
        this.stamps = stamps;
        this.clock = clock;
    }

    /**
     * 护照页（AC3）：先签发（幂等）再取章。
     *
     * <p>🔴 <b>写事务、不是 readOnly</b>：GET 里签发（D-6「首次进护照页」）。无宠物 → 404。
     */
    @Transactional
    public PetPassportResponse pageFor(long ownerId) {
        IssuedPassport issued = ensureIssuedForOwner(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        List<PassportStampView> views = stamps.stampsOf(issued.subject().petId()).stream()
                .map(PassportStampView::of).toList();
        return new PetPassportResponse(issued.subject().name(), issued.passport().getPassportNo(),
                views.size(), views);
    }

    /**
     * 确保该宠物已签发护照，返回护照行（幂等）。
     *
     * <p>🔴 <b>写事务（REQUIRED）</b>：护照 GET 接口也调它（D-6「首次进护照页」签发），
     * 所以调用它的查询方法<b>不能</b>是 {@code readOnly}。
     *
     * <p>并发：insert-on-conflict 只产生一行；输掉竞争时已分配的计数器序号作废成空洞（仅展示语义，可接受）。
     *
     * @param petId   宠物 id（须属于 {@code ownerId}，否则 {@link IllegalStateException}——调用方已校验归属）
     * @param ownerId 宠物主人 —— KTP 号源按 {@code id_cards.user_id} 查
     */
    @Transactional
    public PetPassport ensureIssued(long petId, long ownerId) {
        PetPassportSubject subject = pets.findPassportSubject(ownerId)
                .filter(s -> s.petId() == petId)
                .orElseThrow(() -> new IllegalStateException("宠物不属于该用户"));
        return issue(subject, ownerId);
    }

    private PetPassport issue(PetPassportSubject subject, long ownerId) {
        Optional<PetPassport> existing = passports.findByPetProfileId(subject.petId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<String> ktpNo = pets.findReusableKtpPassportNo(ownerId, subject.createdAt(), subject.petType());
        PassportSource source = ktpNo.isPresent() ? PassportSource.KTP : PassportSource.ISSUED;
        String passportNo = ktpNo.orElseGet(() -> numbers.allocatePassportNo(subject.petType()));
        int inserted = passports.insertIfAbsent(subject.petId(), passportNo, source.name(), clock.instant());
        if (inserted == 0) {
            Optional<PetPassport> raced = passports.findByPetProfileId(subject.petId());
            if (raced.isPresent()) {
                return raced.get(); // 并发签发输了：用赢家那一本，本次号作废
            }
            // 同宠无行却插不进 = 护照号撞了唯一约束（KTP 号在查询与插入之间被占）→ 换一个新号重试一次。
            source = PassportSource.ISSUED;
            passportNo = numbers.allocatePassportNo(subject.petType());
            inserted = passports.insertIfAbsent(subject.petId(), passportNo, source.name(), clock.instant());
        }
        PetPassport issued = passports.findByPetProfileId(subject.petId())
                .orElseThrow(() -> new IllegalStateException("护照签发后回读为空"));
        if (inserted > 0) {
            events.publishEvent(new PassportIssuedEvent(ownerId, issued.getSource()));
        }
        return issued;
    }

    /** 按主人取宠物摘要后签发（护照页入口）。无宠物 → empty（调用方 404）。 */
    @Transactional
    public Optional<IssuedPassport> ensureIssuedForOwner(long ownerId) {
        return pets.findPassportSubject(ownerId)
                .map(s -> new IssuedPassport(s, issue(s, ownerId)));
    }

    /** 签发结果 + 宠物摘要（护照页页眉要宠物名）。 */
    public record IssuedPassport(PetPassportSubject subject, PetPassport passport) {
    }
}
