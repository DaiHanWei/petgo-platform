package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.dto.TailsonalityResultResponse;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 2.1 · AC4 / AC5 / AC7.1：提交校验、题套选择、列表序号、防枚举（L0，mock 仓库）。 */
class TailsonalityResultServiceTest {

    private static final long USER = 7L;
    private static final long PET = 70L;

    private TailsonalityResultRepository repo;
    private PetProfileQueryService pets;
    private TailsonalityTokenGenerator tokens;
    private TailsonalityResultService service;
    private com.tailtopia.tailsonality.repository.TailsonalityBadgeRepository badges;
    private final List<TailsonalityResult> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(TailsonalityResultRepository.class);
        pets = mock(PetProfileQueryService.class);
        tokens = mock(TailsonalityTokenGenerator.class);
        when(tokens.generate()).thenReturn("t".repeat(32));
        when(pets.findOwnedPet(USER, null)).thenReturn(Optional.of(new OwnedPetRef(PET, PetType.CAT)));
        when(pets.ownsPet(USER, PET)).thenReturn(true);
        when(repo.save(any())).thenAnswer(inv -> {
            TailsonalityResult r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", (long) stored.size() + 1);
            stored.add(0, r);
            return r;
        });
        when(repo.findByPetProfileIdOrderByCreatedAtDescIdDesc(PET)).thenAnswer(inv -> List.copyOf(stored));
        badges = mock(com.tailtopia.tailsonality.repository.TailsonalityBadgeRepository.class);
        when(badges.findResultIdByPetProfileId(PET)).thenReturn(Optional.empty());
        service = new TailsonalityResultService(repo, pets, tokens, badges,
                mock(TailsonalityUpgradePricing.class), Clock.fixed(Instant.parse("2026-09-30T08:00:00Z"), ZoneOffset.UTC));
    }

    private static Map<String, Object> body(int idx) {
        Map<String, Object> m = new LinkedHashMap<>();
        TailsonalityCatalog.QUESTION_IDS.forEach(q -> m.put(q, idx));
        return m;
    }

    private static void assert422(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void submitScoresAndPersistsOneRow() {
        TailsonalityResultResponse r = service.submit(USER, null, body(0));
        assertThat(r.typeCode()).isEqualTo("ENFJ-H");
        assertThat(r.letters()).isEqualTo("ENFJ");
        assertThat(r.energy()).isEqualTo("H");
        assertThat(r.questionSet()).isEqualTo("CAT");
        assertThat(r.resultIndex()).isEqualTo(1);
        assertThat(r.unlocked()).isFalse();
        assertThat(r.unlockedAt()).isNull();
        assertThat(r.contentVersion()).isEqualTo(1);
        ArgumentCaptor<TailsonalityResult> cap = ArgumentCaptor.forClass(TailsonalityResult.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getAnswers()).hasSize(18);
        assertThat(cap.getValue().getUserId()).isEqualTo(USER);
        assertThat(cap.getValue().getPetProfileId()).isEqualTo(PET);
    }

    @Test
    void otherPetUsesGeneralSet() {
        when(pets.findOwnedPet(USER, null)).thenReturn(Optional.of(new OwnedPetRef(PET, PetType.OTHER)));
        assertThat(service.submit(USER, null, body(3)).questionSet()).isEqualTo("GENERAL");
    }

    @Test
    void keySetMustBeExactly18() {
        Map<String, Object> missing = body(0);
        missing.remove("P3");
        assert422(() -> service.submit(USER, null, missing));

        Map<String, Object> extra = body(0);
        extra.put("questionSet", 0);
        assert422(() -> service.submit(USER, null, extra));

        Map<String, Object> unknown = body(0);
        unknown.remove("Q1");
        unknown.put("Q16", 0);
        assert422(() -> service.submit(USER, null, unknown));
        verify(repo, never()).save(any());
    }

    @Test
    void valuesMustBeIntegers0to3() {
        for (Object bad : new Object[] {-1, 4, 1.5, "1", null, true}) {
            Map<String, Object> b = body(0);
            b.put("Q5", bad);
            assert422(() -> service.submit(USER, null, b));
        }
        verify(repo, never()).save(any());
    }

    @Test
    void invalidAnswersDetailDoesNotEchoValues() {
        Map<String, Object> b = body(0);
        b.put("Q5", 99);
        assertThatThrownBy(() -> service.submit(USER, null, b)).hasMessageNotContaining("99").hasMessageNotContaining("Q5");
    }

    @Test
    void noPetIs404() {
        when(pets.findOwnedPet(USER, null)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(USER, null, body(0))).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.list(USER, null)).isInstanceOf(AppException.class);
    }

    @Test
    void retakeAddsARowAndListIsNewestFirstWithAscendingIndex() {
        when(tokens.generate()).thenReturn("a".repeat(32), "b".repeat(32), "c".repeat(32));
        service.submit(USER, null, body(0));
        service.submit(USER, null, body(3));
        TailsonalityResultResponse third = service.submit(USER, null, body(0));
        assertThat(third.resultIndex()).isEqualTo(3);

        var items = service.list(USER, null).items();
        assertThat(items).extracting(TailsonalityResultResponse::token)
                .containsExactly("c".repeat(32), "b".repeat(32), "a".repeat(32));
        assertThat(items).extracting(TailsonalityResultResponse::resultIndex).containsExactly(3, 2, 1);
    }

    @Test
    void submitIndexIsTheRowsOwnPositionNotTheTotalCount() {
        // 模拟并发：本行提交后、读序号前，另一台设备又插入了一条更新的行。
        when(tokens.generate()).thenReturn("a".repeat(32));
        when(repo.findByPetProfileIdOrderByCreatedAtDescIdDesc(PET)).thenAnswer(inv -> {
            TailsonalityResult concurrent = TailsonalityResult.create("z".repeat(32), PET, USER,
                    TailsonalityQuestionSet.CAT, Map.of(), new TailsonalityCode("ENFJ", "H"), 1,
                    Instant.parse("2026-09-30T08:00:01Z"));
            ReflectionTestUtils.setField(concurrent, "id", 99L);
            List<TailsonalityResult> rows = new ArrayList<>(stored);
            rows.add(0, concurrent);
            return rows;
        });
        assertThat(service.submit(USER, null, body(0)).resultIndex()).isEqualTo(1);
    }

    @Test
    void getComputesIndexAndForeignOrUnknownTokenIs404() {
        when(tokens.generate()).thenReturn("a".repeat(32), "b".repeat(32));
        service.submit(USER, null, body(0));
        service.submit(USER, null, body(0));
        TailsonalityResult first = stored.get(1);
        when(repo.findByPublicToken("a".repeat(32))).thenReturn(Optional.of(first));
        assertThat(service.get(USER, "a".repeat(32)).resultIndex()).isEqualTo(1);

        when(repo.findByPublicToken("z".repeat(32))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(USER, "z".repeat(32))).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void entityKeepsCodeParts() {
        TailsonalityResult r = TailsonalityResult.create("x", 1, 2, TailsonalityQuestionSet.DOG,
                Map.of("Q1", 0), new TailsonalityCode("ISTP", "L"), 1, Instant.EPOCH);
        assertThat(r.code().full()).isEqualTo("ISTP-L");
    }

    /** V1.3.2 Story 3.3 · AC2.5：列表与单条的 equipped = 佩戴行 result_id 等于本结果 id。 */
    @Test
    void equippedFlagFollowsBadgeRow() {
        service.submit(USER, null, body(0));
        service.submit(USER, null, body(1));
        long firstId = stored.get(1).getId();
        when(badges.findResultIdByPetProfileId(PET)).thenReturn(Optional.of(firstId));
        var items = service.list(USER, null).items();
        assertThat(items.get(0).equipped()).isFalse();
        assertThat(items.get(1).equipped()).isTrue();
        when(repo.findByPublicToken("x")).thenReturn(Optional.of(stored.get(1)));
        assertThat(service.get(USER, "x").equipped()).isTrue();
    }

    // ===== V1.4.0 multi-pet Story 1.2 · X12 =====

    /** token 寻址：结果行上的宠物不属于本人 → 同一个 404（不依赖 petId）。 */
    @Test
    void foreignTokenIs404() {
        TailsonalityResult foreign = TailsonalityResult.create("f".repeat(32), 99L, 8L, TailsonalityQuestionSet.CAT,
                Map.of(), new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(foreign, "id", 500L);
        when(repo.findByPublicToken("f".repeat(32))).thenReturn(Optional.of(foreign));
        when(pets.ownsPet(USER, 99L)).thenReturn(false);
        assertThatThrownBy(() -> service.get(USER, "f".repeat(32))).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** petId=B：提交挂到 B、列表只含 B 的结果；token 读 B 的结果按 B 的序号。 */
    @Test
    void secondPetSubmitAndListStayOnThatPet() {
        long petB = 71L;
        List<TailsonalityResult> storedB = new ArrayList<>();
        when(pets.findOwnedPet(USER, petB)).thenReturn(Optional.of(new OwnedPetRef(petB, PetType.DOG)));
        when(pets.ownsPet(USER, petB)).thenReturn(true);
        when(badges.findResultIdByPetProfileId(petB)).thenReturn(Optional.empty());
        when(repo.findByPetProfileIdOrderByCreatedAtDescIdDesc(petB)).thenAnswer(inv -> List.copyOf(storedB));
        // doAnswer 而不是 when(...)：setUp 里已有 save 的 answer，when 会带 null 参数先调一次旧 answer。
        org.mockito.Mockito.doAnswer(inv -> {
            TailsonalityResult r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 900L + storedB.size());
            if (r.getPetProfileId() == petB) {
                storedB.add(0, r);
            } else {
                stored.add(0, r);
            }
            return r;
        }).when(repo).save(any());

        service.submit(USER, null, body(0)); // 默认宠物 A
        TailsonalityResultResponse b1 = service.submit(USER, petB, body(3));

        assertThat(b1.questionSet()).isEqualTo("DOG");
        assertThat(service.list(USER, petB).items()).hasSize(1);
        assertThat(service.list(USER, null).items()).hasSize(1);
        when(repo.findByPublicToken("tokB")).thenReturn(Optional.of(storedB.get(0)));
        assertThat(service.get(USER, "tokB").resultIndex()).isEqualTo(1);
        when(pets.findOwnedPet(USER, 99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(USER, 99L)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
