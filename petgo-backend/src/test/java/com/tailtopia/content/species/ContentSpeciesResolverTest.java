package com.tailtopia.content.species;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：内容物种推导。
 *
 * <p>V1.4.0 multi-pet Story 1.2（X27，V-1）先保证多宠作者不抛异常；Story 3.2（AD-11 · AC16）把语义做完整：
 * Diary 按关联宠物、Moment / Tips 按作者全部宠物，物种集合 1 种 → 该物种、≥ 2 种 → GENERAL；
 * 行级覆写 / 虚拟号定位仍最高。
 */
class ContentSpeciesResolverTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private UserRepository users;
    private PetProfileRepository pets;
    private ContentPetQuery contentPets;
    private ContentSpeciesResolver resolver;

    // 作者 1：先建 DOG(10) 再建 CAT(11)；作者 2：只有 CAT(20)。
    private PetProfile dog10;
    private PetProfile cat11;
    private PetProfile cat20;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        pets = mock(PetProfileRepository.class);
        contentPets = mock(ContentPetQuery.class);
        resolver = new ContentSpeciesResolver(users, pets, contentPets);
        User multi = realUser(1L);
        User single = realUser(2L);
        when(users.findAllById(anyCollection())).thenReturn(List.of(multi, single));
        dog10 = pet(1L, 10L, PetType.DOG, T0);
        cat20 = pet(2L, 20L, PetType.CAT, T0.plusSeconds(10));
        cat11 = pet(1L, 11L, PetType.CAT, T0.plusSeconds(60));
    }

    private static User realUser(long id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getAccountType()).thenReturn(AccountType.REAL);
        return u;
    }

    private void authorsHave(PetProfile... all) {
        when(pets.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(anyCollection())).thenReturn(List.of(all));
    }

    private static ContentSpeciesResolver.Input moment(long id, long author) {
        return new ContentSpeciesResolver.Input(id, null, author, ContentType.DAILY);
    }

    private static ContentSpeciesResolver.Input diary(long id, long author) {
        return new ContentSpeciesResolver.Input(id, null, author, ContentType.GROWTH_MOMENT);
    }

    private static final ResolvedSpecies CAT = new ResolvedSpecies("CAT", SpeciesSource.PET_PROFILE);
    private static final ResolvedSpecies DOG = new ResolvedSpecies("DOG", SpeciesSource.PET_PROFILE);
    private static final ResolvedSpecies GENERAL =
            new ResolvedSpecies(ContentSpecies.GENERAL, SpeciesSource.PET_PROFILE);

    /**
     * Story 1.2 的 V-1 用例按 Story 3.2 AD-11 <b>按设计改写</b>：猫狗都养的作者（无类型 = 作者规则）
     * → GENERAL（原断言「默认宠物 DOG」被本 story 替换）；单宠作者不变；多宠不抛异常的意图保留。
     */
    @Test
    void multiPetAuthorWithTwoSpeciesIsGeneralAndSinglePetUnchanged() {
        authorsHave(dog10, cat20, cat11);

        var out = resolver.resolveAll(List.of(
                new ContentSpeciesResolver.Input(100L, null, 1L),
                new ContentSpeciesResolver.Input(200L, null, 2L)));

        assertThat(out.get(100L)).isEqualTo(GENERAL);
        assertThat(out.get(200L)).isEqualTo(CAT);
    }

    @Test
    void authorWithoutPetIsNone() {
        authorsHave();

        var out = resolver.resolveAll(List.of(new ContentSpeciesResolver.Input(100L, null, 1L)));

        assertThat(out.get(100L)).isEqualTo(ResolvedSpecies.NONE);
    }

    /** AC1.9：只养猫作者的 Moment → CAT；猫狗都养作者的 Moment → GENERAL。 */
    @Test
    void momentFollowsAllAuthorPets() {
        authorsHave(dog10, cat20, cat11);

        var out = resolver.resolveAll(List.of(moment(100L, 2L), moment(101L, 1L),
                new ContentSpeciesResolver.Input(102L, null, 1L, ContentType.KNOWLEDGE)));

        assertThat(out.get(100L)).isEqualTo(CAT);
        assertThat(out.get(101L)).isEqualTo(GENERAL);
        assertThat(out.get(102L)).isEqualTo(GENERAL);
    }

    /** AC1.9：关联一猫一狗的 Diary → GENERAL；同一猫狗作者「只关联猫」的 Diary → CAT。 */
    @Test
    void diaryFollowsLinkedPets() {
        authorsHave(dog10, cat20, cat11);
        when(contentPets.petsOfAll(anyCollection())).thenReturn(Map.of(
                300L, List.of(10L, 11L),
                301L, List.of(11L)));
        when(pets.findAllById(anyIterable())).thenReturn(List.of(dog10, cat11));

        var out = resolver.resolveAll(List.of(diary(300L, 1L), diary(301L, 1L)));

        assertThat(out.get(300L)).isEqualTo(GENERAL);
        assertThat(out.get(301L)).isEqualTo(CAT);
    }

    /** AC1.4：Diary 关联宠物全被删（content_pets 无行）→ 回落作者全部宠物。 */
    @Test
    void diaryWithoutLinkedPetsFallsBackToAuthorRule() {
        authorsHave(dog10, cat20, cat11);
        when(contentPets.petsOfAll(anyCollection())).thenReturn(Map.of());

        var out = resolver.resolveAll(List.of(diary(300L, 1L), diary(301L, 2L)));

        assertThat(out.get(300L)).isEqualTo(GENERAL);
        assertThat(out.get(301L)).isEqualTo(CAT);
        verify(pets, never()).findAllById(anyIterable());
    }

    /** AC1.5：只读宠物计入。 */
    @Test
    void readOnlyPetsCount() {
        PetProfile readOnlyDog = pet(2L, 21L, PetType.DOG, T0.plusSeconds(90));
        ReflectionTestUtils.setField(readOnlyDog, "status", PetStatus.READ_ONLY);
        authorsHave(cat20, readOnlyDog);
        when(contentPets.petsOfAll(anyCollection())).thenReturn(Map.of(400L, List.of(21L)));
        when(pets.findAllById(anyIterable())).thenReturn(List.of(readOnlyDog));

        var out = resolver.resolveAll(List.of(moment(100L, 2L), diary(400L, 2L)));

        assertThat(out.get(100L)).isEqualTo(GENERAL);
        assertThat(out.get(400L)).isEqualTo(DOG);
    }

    /** AC16：行级覆写最高；虚拟号定位次之（不看宠物、不看关联）。 */
    @Test
    void overrideAndVirtualAccountStillWin() {
        User virtual = mock(User.class);
        when(virtual.getId()).thenReturn(3L);
        when(virtual.getAccountType()).thenReturn(AccountType.VIRTUAL);
        when(virtual.getAccountSpecies()).thenReturn(null);
        User real = realUser(1L);
        when(users.findAllById(anyCollection())).thenReturn(List.of(real, virtual));
        authorsHave(dog10, cat11, pet(3L, 30L, PetType.DOG, T0));
        when(contentPets.petsOfAll(anyCollection())).thenReturn(Map.of(
                500L, List.of(10L, 11L), 501L, List.of(30L)));
        when(pets.findAllById(anyIterable())).thenReturn(List.of(dog10, cat11));

        var out = resolver.resolveAll(List.of(
                new ContentSpeciesResolver.Input(500L, "DOG", 1L, ContentType.GROWTH_MOMENT),
                diary(501L, 3L)));

        assertThat(out.get(500L)).isEqualTo(new ResolvedSpecies("DOG", SpeciesSource.ROW_OVERRIDE));
        assertThat(out.get(501L))
                .isEqualTo(new ResolvedSpecies(ContentSpecies.GENERAL, SpeciesSource.ACCOUNT_SPECIES));
    }

    /** AC1.7：批量不 N+1 —— 作者、作者宠物、关联、关联宠物各一次。 */
    @Test
    void batchQueriesEachOnce() {
        authorsHave(dog10, cat20, cat11);
        when(contentPets.petsOfAll(anyCollection())).thenReturn(Map.of(
                300L, List.of(10L, 11L), 301L, List.of(11L), 302L, List.of(20L)));
        when(pets.findAllById(anyIterable())).thenReturn(List.of(dog10, cat11, cat20));

        resolver.resolveAll(List.of(diary(300L, 1L), diary(301L, 1L), diary(302L, 2L),
                moment(100L, 1L), moment(101L, 2L)));

        verify(users, times(1)).findAllById(anyCollection());
        verify(pets, times(1)).findAllByOwnerIdInOrderByCreatedAtAscIdAsc(anyCollection());
        verify(contentPets, times(1)).petsOfAll(anyCollection());
        verify(pets, times(1)).findAllById(anyIterable());
    }

    /** 无 Diary ⇒ 不查关联表。 */
    @Test
    void noDiaryNoContentPetsQuery() {
        authorsHave(cat20);

        resolver.resolveAll(List.of(moment(100L, 2L)));

        verify(contentPets, never()).petsOfAll(any());
    }

    /** {@code resolve(post)} 便捷方法带类型：Diary 走关联宠物。 */
    @Test
    void resolvePostUsesType() {
        authorsHave(dog10, cat11);
        when(contentPets.petsOfAll(anyCollection())).thenReturn(Map.of(77L, List.of(11L)));
        when(pets.findAllById(anyIterable())).thenReturn(List.of(cat11));
        ContentPost post = mock(ContentPost.class);
        when(post.getId()).thenReturn(77L);
        when(post.getAuthorId()).thenReturn(1L);
        when(post.getType()).thenReturn(ContentType.GROWTH_MOMENT);

        assertThat(resolver.resolve(post)).isEqualTo(CAT);
        // 旧两参入口 = 作者规则
        assertThat(resolver.resolve(null, 1L)).isEqualTo(GENERAL);
    }
}
