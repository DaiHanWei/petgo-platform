package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.CreateIdCardRequest;
import com.tailtopia.profile.dto.IdCardCreateResponse;
import com.tailtopia.profile.dto.IdCardDataResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：身份证数据服务逻辑面（Story 6.1，FR-49A）——惰性分配 / 幂等 / 老用户「尚未生成」标志 / 无档案 404。
 * 号池并发 / 回收 / 从 1 自增等 DB 行为在 {@code SerialAllocationIntegrationTest}（L1）验。
 */
class IdCardServiceTest {

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final SerialAllocationService serialAllocation = mock(SerialAllocationService.class);
    private final CardNumberService cardNumbers = mock(CardNumberService.class);
    private final IdCardHdPurchaseRepository hdPurchases = mock(IdCardHdPurchaseRepository.class);
    private final IdCardRepository idCards = mock(IdCardRepository.class);
    private final IdCardService service =
            new IdCardService(profiles, serialAllocation, cardNumbers, hdPurchases, idCards);

    private static PetProfile pet() {
        return PetProfile.create(7L, PetType.CAT, "Momo", null, "英短", LocalDate.of(2022, 1, 1), "乖", "TOK");
    }

    @Test
    void getMyIdCardOldUserWithoutSerialReturnsNotGenerated() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(pet()));

        IdCardDataResponse res = service.getMyIdCard(7L);

        assertThat(res.generated()).isFalse();
        assertThat(res.serialId()).isNull();
        assertThat(res.name()).isEqualTo("Momo");
        assertThat(res.petType()).isEqualTo("CAT");
    }

    @Test
    void getMyIdCardWithSerialReturnsGeneratedWithSerial() {
        PetProfile p = pet();
        p.assignSerial(42L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));

        IdCardDataResponse res = service.getMyIdCard(7L);

        assertThat(res.generated()).isTrue();
        assertThat(res.serialId()).isEqualTo(42L);
    }

    @Test
    void getMyIdCardNoProfile404() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyIdCard(7L)).isInstanceOf(AppException.class);
    }

    @Test
    void getMyIdCardNeverAllocatesSerial() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(pet()));

        service.getMyIdCard(7L);

        // 惰性分配红线：只读端点绝不分配号（保老用户「尚未生成」引导态持续可见）。
        verify(serialAllocation, never()).allocate();
        verify(profiles, never()).save(any());
    }

    @Test
    void generateAssignsSerialWhenAbsentAndPersists() {
        PetProfile p = pet();
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));
        when(serialAllocation.allocate()).thenReturn(1L);

        IdCardDataResponse res = service.generateSerial(7L);

        verify(serialAllocation).allocate();
        verify(profiles).save(p);
        assertThat(p.getSerialId()).isEqualTo(1L);
        assertThat(res.generated()).isTrue();
        assertThat(res.serialId()).isEqualTo(1L);
    }

    @Test
    void generateIsIdempotentWhenSerialAlreadyAssigned() {
        PetProfile p = pet();
        p.assignSerial(99L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));

        IdCardDataResponse res = service.generateSerial(7L);

        // 幂等：已有号不重复分配、不换号、不再落库（避免 pet_serial_seq 空耗）。
        verify(serialAllocation, never()).allocate();
        verify(profiles, never()).save(any());
        assertThat(res.serialId()).isEqualTo(99L);
    }

    @Test
    void generateNoProfile404() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateSerial(7L)).isInstanceOf(AppException.class);
        verify(serialAllocation, never()).allocate();
    }

    // ---- 卡可见性（V108，2026-08-19 决策 F21）：付费卡恒可见；档案已删的未付费卡隐藏 ----

    /** 快照 stub：id/解锁态/档案删除打标可控（打标列由 repo 批量 update 写，实体无 setter → mock）。 */
    private static IdCard card(long id, boolean hdUnlocked, Instant profileDeletedAt) {
        IdCard c = mock(IdCard.class);
        when(c.getId()).thenReturn(id);
        when(c.isHdUnlocked()).thenReturn(hdUnlocked);
        when(c.getProfileDeletedAt()).thenReturn(profileDeletedAt);
        return c;
    }

    @Test
    void listHidesUnpaidCardsOfDeletedProfileButKeepsPaidOnes() {
        Instant deleted = Instant.parse("2026-08-19T00:36:00Z");
        // 先建 stub 再打桩（Mockito 禁止在 thenReturn 参数里嵌套 when）。
        IdCard active = card(1L, false, null);      // 现档案未付费卡 → 可见
        IdCard orphanUnpaid = card(2L, false, deleted); // 删档未付费卡 → 隐藏
        IdCard orphanPaid = card(3L, true, deleted);    // 删档已付费卡 → 恒可见（快照展示）
        when(idCards.findByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(active, orphanUnpaid, orphanPaid));

        List<Long> ids = service.listMyCards(7L).stream().map(r -> r.id()).toList();

        assertThat(ids).containsExactly(1L, 3L);
    }

    @Test
    void detailOfUnpaidCardWithDeletedProfileIs404() {
        IdCard orphanUnpaid = card(2L, false, Instant.now());
        when(idCards.findByIdAndUserId(2L, 7L)).thenReturn(Optional.of(orphanUnpaid));

        assertThatThrownBy(() -> service.getMyCard(7L, 2L)).isInstanceOf(AppException.class);
    }

    @Test
    void detailOfPaidCardWithDeletedProfileStaysVisible() {
        // 防支付时间差：删档时在途支付到账翻转 hdUnlocked 后，卡必须重新可见。
        IdCard orphanPaid = card(3L, true, Instant.now());
        when(idCards.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(orphanPaid));

        assertThat(service.getMyCard(7L, 3L).id()).isEqualTo(3L);
    }

    // ---- V1.3.4 ktp Story 1.1：卡种 / 配色 / 必填（AD-1 / AD-4 / D-9 / D-12） ----

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static CreateIdCardRequest req(String cardType, String cardVariant, String petType, String gender,
            String breed, String intro) {
        return new CreateIdCardRequest("Momo", petType, breed, LocalDate.of(2024, 3, 10), gender, null, intro,
                null, null, null, null, cardType, null, null, cardVariant);
    }

    private static CreateIdCardRequest req(String cardType, String cardVariant) {
        return req(cardType, cardVariant, "CAT", "FEMALE", "英短", null);
    }

    /** 模拟落库：IDENTITY 主键由库生成，这里补一个 id 后原样返回。 */
    private static IdCard persisted(org.mockito.invocation.InvocationOnMock inv) {
        IdCard c = inv.getArgument(0);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 5L);
        return c;
    }

    /** 建卡并取落库快照（save 原样返回入参）。 */
    private IdCard created(CreateIdCardRequest r) {
        when(serialAllocation.allocate()).thenReturn(11L);
        when(cardNumbers.allocateCardNo(any(), any(), any())).thenReturn("TT60032402" + "0001");
        when(cardNumbers.allocatePassportNo(any())).thenReturn("TT02P2600001");
        when(idCards.save(any())).thenAnswer(IdCardServiceTest::persisted);
        service.createCard(7L, r);
        ArgumentCaptor<IdCard> saved = ArgumentCaptor.forClass(IdCard.class);
        verify(idCards).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void createEachOfFiveCardTypesSnapshotsTypeAndAllocatesNumbers() {
        for (String type : List.of("KTP", "PASSPORT", "KITAS", "SIM")) {
            org.mockito.Mockito.reset(idCards, serialAllocation, cardNumbers);
            IdCard c = created(req(type, null));
            assertThat(c.getCardType()).isEqualTo(type);
            assertThat(c.getCardVariant()).isNull();
            assertThat(c.getSerialId()).isEqualTo(11L);
            assertThat(c.getCardNo()).isEqualTo("TT600324020001");
            assertThat(c.getPassportNo()).isEqualTo("TT02P2600001");
        }
    }

    @Test
    void createStudentCardInEachVariant() {
        for (String v : List.of("BINTANG", "MALAM", "CERIA", "KLASIK")) {
            org.mockito.Mockito.reset(idCards, serialAllocation, cardNumbers);
            IdCard c = created(req("STUDENT", v));
            assertThat(c.getCardType()).isEqualTo("STUDENT");
            assertThat(c.getCardVariant()).isEqualTo(v);
        }
    }

    @Test
    void studentWithoutVariantFallsBackToKlasik() {
        // D-12：旧版 App 只有紫色学生卡，不带 cardVariant → KLASIK，不 422。
        assertThat(created(req("STUDENT", null)).getCardVariant()).isEqualTo("KLASIK");
    }

    @Test
    void nullCardTypeStaysKtp() {
        IdCard c = created(req(null, null));
        assertThat(c.getCardType()).isEqualTo("KTP");
        assertThat(c.getCardVariant()).isNull();
    }

    @Test
    void nonStudentWithVariantIs422AndAllocatesNothing() {
        assertThatThrownBy(() -> service.createCard(7L, req("KITAS", "BINTANG")))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422));
        verify(serialAllocation, never()).allocate();
        verify(idCards, never()).save(any());
    }

    @Test
    void ktpAndKitasRequireBreed() {
        for (String type : List.of("KTP", "KITAS")) {
            for (String breed : java.util.Arrays.asList(null, "  ")) {
                assertThatThrownBy(() -> service.createCard(7L, req(type, null, "CAT", "FEMALE", breed, null)))
                        .isInstanceOf(AppException.class)
                        .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422));
            }
        }
        verify(serialAllocation, never()).allocate();
    }

    @Test
    void breedOptionalForPassportSimStudent() {
        for (String type : List.of("PASSPORT", "SIM", "STUDENT")) {
            org.mockito.Mockito.reset(idCards, serialAllocation, cardNumbers);
            assertThat(created(req(type, null, "DOG", "MALE", null, null)).getBreed()).isNull();
        }
    }

    @Test
    void introIsNeverPersisted() {
        assertThat(created(req("KTP", null, "CAT", "FEMALE", "英短", "乖巧")).getIntro()).isNull();
    }

    @Test
    void genderIsPassedThroughWithoutUnknownFallback() {
        IdCard c = created(req("SIM", null, "OTHER", "UNKNOWN", null, null));
        assertThat(c.getGender()).isEqualTo("UNKNOWN");
        verify(cardNumbers).allocateCardNo(LocalDate.of(2024, 3, 10), "UNKNOWN", "OTHER");
    }

    @Test
    void createResponseCarriesNumberReusedFalseAndVariant() {
        when(serialAllocation.allocate()).thenReturn(11L);
        when(idCards.save(any())).thenAnswer(IdCardServiceTest::persisted);
        IdCardCreateResponse res = service.createCard(7L, req("STUDENT", "CERIA"));
        assertThat(res.numberReused()).isFalse();
        assertThat(res.card().cardVariant()).isEqualTo("CERIA");
        assertThat(res.card().cardType()).isEqualTo("STUDENT");
    }

    // ---- DTO Bean 校验（全局映射 422）：petType / gender 必填且限白名单；卡种 / 配色白名单 ----

    private static Set<String> violatedFields(CreateIdCardRequest r) {
        return VALIDATOR.validate(r).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void dtoRequiresPetTypeAndGender() {
        assertThat(violatedFields(req("KTP", null, null, "FEMALE", "x", null))).containsExactly("petType");
        assertThat(violatedFields(req("KTP", null, "CAT", null, "x", null))).containsExactly("gender");
    }

    @Test
    void dtoRejectsValuesOutsideWhitelists() {
        assertThat(violatedFields(req("KTP", null, "RABBIT", "FEMALE", "x", null))).containsExactly("petType");
        assertThat(violatedFields(req("KTP", null, "CAT", "OTHER", "x", null))).containsExactly("gender");
        assertThat(violatedFields(req("ALIEN", null))).containsExactly("cardType");
        assertThat(violatedFields(req("STUDENT", "UNGU"))).containsExactly("cardVariant");
    }

    @Test
    void dtoAcceptsAllWhitelistedValues() {
        for (String type : List.of("KTP", "PASSPORT", "KITAS", "SIM", "STUDENT")) {
            assertThat(violatedFields(req(type, null))).isEmpty();
        }
        for (String v : List.of("BINTANG", "MALAM", "CERIA", "KLASIK")) {
            assertThat(violatedFields(req("STUDENT", v))).isEmpty();
        }
        for (String pt : List.of("CAT", "DOG", "OTHER")) {
            for (String g : List.of("MALE", "FEMALE", "UNKNOWN")) {
                assertThat(violatedFields(req("KTP", null, pt, g, "x", null))).isEmpty();
            }
        }
    }

    // ---- V1.3.4 ktp Story 2.2（AD-3）：建卡写号先登记归属，再落卡 ----

    @Test
    void createCardClaimsNumbersBeforeSave() {
        when(serialAllocation.allocate()).thenReturn(11L);
        when(cardNumbers.allocateCardNo(any(), any(), any())).thenReturn("TT600324020001");
        when(cardNumbers.allocatePassportNo(any())).thenReturn("TT02P2600001");
        when(idCards.save(any())).thenAnswer(IdCardServiceTest::persisted);

        service.createCard(7L, req("PASSPORT", null));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(cardNumbers, idCards);
        order.verify(cardNumbers).claimNumbers(7L, "TT600324020001", "TT02P2600001");
        order.verify(idCards).save(any());
    }

    @Test
    void claimConflictAbortsWithoutSaving() {
        when(serialAllocation.allocate()).thenReturn(11L);
        when(cardNumbers.allocateCardNo(any(), any(), any())).thenReturn("TT600324020001");
        when(cardNumbers.allocatePassportNo(any())).thenReturn("TT02P2600001");
        org.mockito.Mockito.doThrow(new IllegalStateException("身份证编号已属于其他用户"))
                .when(cardNumbers).claimNumbers(anyLong(), any(), any());

        assertThatThrownBy(() -> service.createCard(7L, req("PASSPORT", null)))
                .isInstanceOf(IllegalStateException.class);
        verify(idCards, never()).save(any());
    }
}
